package com.fleet.dms;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The part of the driver app that keeps working when the app itself does not.
 *
 * Android freezes the JavaScript of an app that is not on screen and stops its
 * timers, so a page cannot watch a driver who has picked up his phone to answer a
 * call, and it certainly cannot watch one behind a locked screen. Everything that
 * has to go on in those moments - opening the camera, reading the face, deciding
 * whether that is a warning, making the sound and keeping the seconds of picture -
 * is here instead, in a service of type camera, which is the one form of background
 * work Android allows an app that holds the lens to do at all.
 *
 * The page is still the only thing in this app that talks to the database. What it
 * gets back from here is the same out-of-coverage queue the vehicle's own alerts
 * already use, so the driver's rights and the row guards on the events table are what
 * decide what is stored, and a phone with no signal loses nothing.
 */
public class DmsService extends Service implements LifecycleOwner {

    public static final String ACTION_START = "com.fleet.dms.START";
    public static final String ACTION_STOP = "com.fleet.dms.STOP";

    private static final int NOTICE_TRIP = 1;
    // What the lens is asked for. Small on purpose: the face is measured from ratios
    // of the driver's own features, so a bigger picture buys nothing but battery.
    private static final int ANALYSIS_W = 320, ANALYSIS_H = 240;
    // The preview box is a courtesy to the driver, not a reading; once a second is as
    // often as it needs to move, and every hand-over allocates a picture.
    private static final int UI_EVERY_MS = 1000;

    /**
     * What the page can hear. Only ever the plugin of the app's own process, which is
     * why this is a plain reference rather than a bound connection - the alternative is
     * a hundred lines of plumbing to say the same thing, and the service and the page
     * die together. The page says when it is not the screen to be looked at.
     */
    public interface Host {
        boolean uiVisible();

        void notice(String type, String detail, double value);

        /** False when the driver has put his own picture box away. */
        boolean previewWanted();

        /**
         * One picture for the driver's own preview box. The bitmap handed over is new
         * each time and becomes the receiver's to keep or recycle; it is never one of
         * the two the camera thread fills, because those are overwritten every frame.
         */
        void frame(Bitmap upright);

        /** Something is waiting in the queue that the page can send off. */
        void ping();
    }

    private static volatile DmsService instance;
    private static volatile Host host;

    static DmsService get() {
        return instance;
    }

    static void setHost(Host h) {
        host = h;
    }

    private final LifecycleRegistry lc = new LifecycleRegistry(this);
    private final Handler main = new Handler(Looper.getMainLooper());

    // One analysis thread, because the face model is asked one picture at a time and
    // two of them reading the same camera buffer is a crash waiting to happen.
    private ExecutorService analysis;
    private FaceReader reader;
    private Rules rules;
    private Clip clip;
    private Outbox outbox;
    private Alerts alerts;
    private PowerManager.WakeLock wake;

    private volatile Config cfg = new Config();
    private volatile double kmh;
    private volatile double[] fix;
    private volatile String reason = "";
    private long lastFrame, lastUi;

    // The two pictures the camera thread fills and re-fills: one as the lens
    // delivered it, one turned upright. Reused every frame so a trip that runs for
    // eight hours does not spend itself on allocations.
    private Bitmap flat, turn;
    private int[] pix;
    private byte[] brow;
    private boolean cameraOn;
    // Set in onDestroy, before anything is put down. Every thread but this one is
    // stopped by it, because it is the answer the frame callback gives on its way in.
    private volatile boolean dead;

    /**
     * The executor CameraX is allowed to hand frames to.
     *
     * Passing it the pool itself is a crash with the lens still open: the service shuts
     * the pool down while the camera is finishing what it had already started to feed,
     * and a refused task throws inside CameraX's own thread, where nobody is there to
     * catch it and the whole app goes down with the exception. This is the catcher. A
     * frame that arrives after the service has said goodbye is a frame nobody asked for,
     * so it is dropped rather than thrown.
     */
    private final Executor frameExec = task -> {
        if (dead) return;
        ExecutorService e = analysis;
        if (e == null) return;
        try {
            e.execute(task);
        } catch (Throwable ignore) {
            // the last frames of a dying trip cost nothing to lose
        }
    };

    // ---- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        cfg = Config.load(this);
        outbox = new Outbox(this);
        alerts = new Alerts(this);
        alerts.setMessages(cfg.msgs);
        clip = new Clip(this);
        clip.setRate(cfg.fps);
        clip.age(cfg.keepD);
        reader = new FaceReader(this);
        rules = new Rules(this);
        analysis = Executors.newSingleThreadExecutor();
        // A Service is not an activity, so nothing drives its lifecycle for it and CameraX
        // would never be told to open. These two events are the whole of what it needs, and
        // they are sent once, here: a lens closed and opened again inside one trip is an
        // unbind and a bind, not a second round of events, and a registry that is made to
        // go back over states it has already passed is how the main thread ends up throwing.
        lc.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        lc.handleLifecycleEvent(Lifecycle.Event.ON_START);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // the screen is off a lot of the working day, and a sleeping CPU stops reading
        // the camera with it; a partial wake lock costs battery and is the only way to
        // keep the analysis going while the driver's screen is dark
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fleet:dms");
            wake.setReferenceCounted(false);
        }
        foreground("Waiting for the trip to start");
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (dead) {
            // Android can hand a fresh start to an instance that has already been told to
            // go away, and opening a lens into a service that is on its way out is not a
            // camera, it is a crash. The page is told by its own status call, and asking
            // again a moment later reaches the new instance that replaces this one.
            return START_NOT_STICKY;
        }
        String act = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(act)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        trip(true);
        // Android may hand back a null intent when it has had to restart this service
        // after a kill. Whether it should be running again is then the page's own last
        // instruction, which is what the trip flag on disk records.
        if (intent == null && (!tripWanted(this) || !cfg.on)) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // First, and before anything is put down: every thread but the main one is
        // stopped by this flag, because it is the answer the frame callback gives on its
        // way in and it is what the executor wrapper reads.
        dead = true;
        instance = null;
        cameraOn = false;
        tripSet(this, false);
        closeCamera();
        alerts.stopVoice();
        if (wake != null && wake.isHeld()) wake.release();
        lc.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
        lc.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignore) {
            // a service Android already took off the foreground has nothing to take down
        }
        // The model, the ring of pictures and the two bitmaps belong to the analysis
        // thread, and are put down on it, behind whatever frame it is still working on.
        // Closing the native face graph beside a frame that is reading it is a crash no
        // Java catch can see coming, and recycling a bitmap the camera thread is drawing
        // with is the black picture that gives the rules nothing to judge.
        ExecutorService e = analysis;
        if (e != null) {
            try {
                e.execute(this::teardown);
            } catch (Throwable t) {
                teardown();
            }
            e.shutdown();
        } else {
            teardown();
        }
        super.onDestroy();
    }

    /** The last thing the analysis thread ever does for this trip. */
    private void teardown() {
        // Everything the camera thread owns is put down here, behind the last frame it
        // was working on: the events still open are closed, the breaches are forgotten,
        // the native face graph is released and the two pictures are given back. Done on
        // any other thread, each of them is a read or a free beside the frame that was
        // still using it - and a face graph taken away under a detection is a crash that
        // no catch in this file can see coming.
        try {
            rules.closeOpen(cfg, System.currentTimeMillis());
        } catch (Throwable ignore) {
            // the last events of a trip are a courtesy; nothing here may throw
        }
        try {
            rules.reset();
        } catch (Throwable ignore) {
            // as above
        }
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignore) {
            // a graph that refuses to close is already gone
        }
        try {
            if (clip != null) clip.close();
        } catch (Throwable ignore) {
            // an unfinished clip costs an event its video and nothing else
        }
        recycleBoth();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public Lifecycle getLifecycle() {
        return lc;
    }

    // ---- what the page asks for

    /** The trip has begun: take the wake lock, open the lens and start judging frames. */
    void trip(boolean yes) {
        tripSet(this, yes);
        if (dead) return;
        if (!yes) {
            stopSelf();
            return;
        }
        if (wake != null && !wake.isHeld()) wake.acquire();
        if (cfg.on && !cameraOn) openCamera();
        foreground(cfg.on ? "Watching the driver" : "Driver monitoring is switched off");
    }

    void applyNewConfig(Config next) {
        cfg = next;
        if (dead) return;
        alerts.setMessages(next.msgs);
        clip.setRate(next.fps);
        clip.age(next.keepD);
        if (next.on && !cameraOn) openCamera();
        if (!next.on) {
            // switched off mid-trip: the lens goes back, because one camera can only be
            // held by one app and a driver who has turned this off should not be blocking
            // the camera for anybody else
            closeCamera();
            ExecutorService e = analysis;
            if (e != null) {
                try {
                    // the open breaches and the ring of pictures belong to the camera
                    // thread, so they are cleared on it rather than beside a frame
                    e.execute(() -> {
                        rules.reset();
                        clip.finish();
                    });
                } catch (Throwable ignore) {
                    // a thread that will not take the chore has already been told to stop
                }
            }
        }
        foreground(next.on ? "Watching the driver" : "Driver monitoring is switched off");
    }

    /** The fix the app already has, so an event can say where the vehicle was. */
    void feed(double lat, double lng, double acc, double alt, double heading, double speedKmh, long atMs) {
        kmh = speedKmh;
        fix = new double[]{lat, lng, acc, alt, heading, atMs};
    }

    int outboxSize() {
        return outbox.size();
    }

    JSONArray drain() {
        return outbox.drain();
    }

    void clearAll() {
        outbox.clear();
        clip.clearAll();
        ExecutorService e = analysis;
        if (e == null) {
            rules.reset();
            return;
        }
        try {
            // an open breach is the camera thread's own list, so it is cleared on that
            // thread rather than beside a frame that is still reading it
            e.execute(rules::reset);
        } catch (Throwable t) {
            rules.reset();
        }
    }

    // ---- the status the page shows

    JSONObject statusJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("running", true).put("camera", cameraOn)
                    .put("on", cfg.on).put("model", reader.ready())
                    .put("reason", reason).put("error", str(reader.error()))
                    .put("kmh", Math.round(kmh)).put("queued", outbox.size())
                    .put("breaching", rules.active())
                    .put("notices", alerts.blocked())
                    .put("preview", cfg.preview)
                    .put("clips", clip.count()).put("clipBytes", clip.bytes());
        } catch (Exception ignore) {
            // JSONObject only throws on a null key, and none of these are null
        }
        return j;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    // ---- the camera

    private void openCamera() {
        if (dead || cameraOn) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            reason = "The camera permission has not been given to this app.";
            return;
        }
        androidx.camera.lifecycle.ProcessCameraProvider p = providerNow();
        if (p != null) {
            bind(p);
            return;
        }
        try {
            // Only the first open of a process has to wait for CameraX to build its
            // provider. Every open after that finds one already made and is bound on the
            // spot, which is what keeps a close and a re-open from racing a listener that
            // would arrive after the service asking for it has gone.
            androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this)
                    .addListener(() -> {
                        androidx.camera.lifecycle.ProcessCameraProvider q = providerNow();
                        if (q != null) bind(q);
                    }, ContextCompat.getMainExecutor(this));
        } catch (Throwable t) {
            reason = "Camera: " + name(t);
        }
    }

    /** The provider, if this handset has finished building it, and null while it has not. */
    private androidx.camera.lifecycle.ProcessCameraProvider providerNow() {
        try {
            java.util.concurrent.Future<androidx.camera.lifecycle.ProcessCameraProvider> f =
                    androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this);
            // asked only when it is already finished, so this never waits for a lens
            if (!f.isDone()) return null;
            return f.get();
        } catch (Throwable t) {
            reason = "Camera: " + name(t);
            return null;
        }
    }

    /** Whether this handset has a lens the given selector can reach at all. */
    private static boolean hasLens(androidx.camera.lifecycle.ProcessCameraProvider p,
                                   androidx.camera.core.CameraSelector s) {
        try {
            return p.hasCamera(s);
        } catch (Throwable e) {
            return false;
        }
    }

    private void bind(androidx.camera.lifecycle.ProcessCameraProvider p) {
        if (dead) return;
        try {
            androidx.camera.core.CameraSelector sel = "back".equals(cfg.lens)
                    ? androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                    : androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA;
            // a tablet in a cradle may only have one lens at all; asking for the front
            // of a phone that has none is an exception, not a different picture
            if (!hasLens(p, sel)) {
                sel = sel == androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                        ? androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
                        : androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA;
                if (!hasLens(p, sel)) {
                    reason = "This handset has no camera that can be opened.";
                    return;
                }
                reason = "No lens on the side the settings asked for: using the other one.";
            }
            androidx.camera.core.ImageAnalysis a = new androidx.camera.core.ImageAnalysis.Builder()
                    .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetResolution(new android.util.Size(ANALYSIS_W, ANALYSIS_H))
                    .build();
            a.setAnalyzer(frameExec, this::analyse);
            // The owner's lifecycle was driven once when this service was made; a lens
            // that was put away is opened again by binding into the state it is already
            // in, which is the supported way to do it and the only way that never asks
            // CameraX to move through states a hand-built registry has passed already.
            p.unbindAll();
            p.bindToLifecycle(this, sel, a);
            cameraOn = true;
            reason = "";
        } catch (Throwable t) {
            reason = "Camera: " + name(t);
            cameraOn = false;
        }
    }

    private void closeCamera() {
        if (!cameraOn) return;
        cameraOn = false;
        // Unbound here, on the thread that owns the lens, rather than from a listener
        // that may not run until the service asking for it has gone: what is left bound
        // after that feeds a thread that no longer exists, and that is the crash.
        // The breaches and the ring are the camera thread's own business and are put
        // down by it, not from here.
        androidx.camera.lifecycle.ProcessCameraProvider p = providerNow();
        if (p == null) return;
        try {
            p.unbindAll();
        } catch (Throwable ignore) {
            // a provider that cannot be reached has already let the lens go
        }
    }

    /**
     * One picture from the lens. Called on the analysis thread, which is the only
     * thread allowed to touch the reader, the ring and the rules here.
     */
    private void analyse(androidx.camera.core.ImageProxy proxy) {
        try {
            // A frame that was already queued when the trip ended must not be judged:
            // the model it reads with is put down by the task behind this one, and a
            // picture taken after that has nobody left to look at it.
            if (dead || !cfg.on) return;
            long now = System.currentTimeMillis();
            long every = Math.max(40, (long) (1000.0 / cfg.fps));
            if (now - lastFrame < every) return;
            lastFrame = now;
            Bitmap b = upright(proxy);
            if (b == null) return;
            FaceReader.Reading r = reader.read(b, now);
            rules.frame(r, kmh, cfg);
            clip.push(b, now * 1000L, cfg.preFrames());
            Host h = host;
            // the driver decides whether he wants to see himself; the dashboard only
            // decides what that choice is when the trip begins
            if (h != null && now - lastUi >= UI_EVERY_MS && h.previewWanted()) {
                lastUi = now;
                // handed over as its own picture: the receiver keeps it until the next
                // one arrives, and nothing here is allowed to recycle it
                h.frame(scaled(b));
            }
        } catch (Throwable t) {
            // a frame that fails is a frame nobody is warned about: the camera and the
            // trip both stay alive, and the reason is what the page is allowed to see
            if (reason.isEmpty()) reason = "Frame: " + name(t);
        } finally {
            try {
                proxy.close();
            } catch (Throwable ignore) {
                // a proxy the camera already gave back has nothing left to release
            }
        }
    }

    /**
     * The frame as it should be looked at: rows copied out with the camera's own row
     * padding respected, then turned upright so the face points the same way in every
     * picture whatever the handset's sensor did.
     */
    private Bitmap upright(androidx.camera.core.ImageProxy img) {
        int w = img.getWidth(), h = img.getHeight();
        // CameraX hands the planes over as an array, and an RGBA frame has one
        androidx.camera.core.ImageProxy.PlaneProxy[] pl = img.getPlanes();
        if (w < 16 || h < 16 || pl.length == 0) return null;
        if (img.getFormat() != PixelFormat.RGBA_8888) return null;
        androidx.camera.core.ImageProxy.PlaneProxy plane = pl[0];
        ByteBuffer buf = plane.getBuffer();
        if (buf == null) return null;
        int stride = Math.max(plane.getRowStride(), w * 4);
        if (pix == null || pix.length < w * h) pix = new int[w * h];
        if (brow == null || brow.length < stride) brow = new byte[stride];
        int mark = buf.position(), limit = buf.limit();
        try {
            buf.position(mark);
            for (int y = 0; y < h; y++) {
                if (buf.remaining() < stride) break;
                buf.get(brow, 0, stride);
                int row = y * w, src = 0;
                for (int x = 0; x < w; x++, src += 4) {
                    // the bytes are red, green, blue, unused; a Bitmap int is ARGB, so
                    // the alpha is set by hand rather than read from a padding byte
                    pix[row + x] = 0xFF000000
                            | (brow[src] & 255) << 16
                            | (brow[src + 1] & 255) << 8
                            | (brow[src + 2] & 255);
                }
            }
        } finally {
            buf.position(mark);
            buf.limit(limit);
        }
        if (flat == null || flat.getWidth() != w || flat.getHeight() != h) {
            if (flat != null) flat.recycle();
            flat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        flat.setPixels(pix, 0, w, 0, 0, w, h);
        int deg = img.getImageInfo() == null ? 0 : img.getImageInfo().getRotationDegrees();
        deg = ((deg % 360) + 360) % 360;
        if (deg == 0) return flat;
        int tw = deg == 180 ? w : h, th = deg == 180 ? h : w;
        if (turn == null || turn.getWidth() != tw || turn.getHeight() != th) {
            if (turn != null) turn.recycle();
            turn = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        }
        // Rotate about the picture's own centre, then drop its new centre where the
        // canvas centre is. Turning about the origin instead would throw the whole
        // rotated frame off the canvas, and a clipped draw onto a bitmap this object
        // reuses paints the driver's box - and the face reading - solid black.
        Matrix m = new Matrix();
        m.postTranslate(-w / 2f, -h / 2f);
        m.postRotate(deg);
        m.postTranslate(tw / 2f, th / 2f);
        new Canvas(turn).drawBitmap(flat, m, null);
        return turn;
    }

    /** A small picture of its own for the driver's preview box. */
    private Bitmap scaled(Bitmap b) {
        int tw = 160, th = Math.max(2, Math.round(tw * (float) b.getHeight() / b.getWidth()));
        Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Matrix m = new Matrix();
        m.setScale((float) tw / b.getWidth(), (float) th / b.getHeight());
        new Canvas(out).drawBitmap(b, m, null);
        return out;
    }

    private void recycleBoth() {
        if (flat != null) {
            flat.recycle();
            flat = null;
        }
        if (turn != null) {
            turn.recycle();
            turn = null;
        }
    }

    private static String name(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ---- the notice that keeps the service alive

    private void foreground(String detail) {
        try {
            Notification n = alerts.tripNotification(detail);
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceCompat.startForeground(this, NOTICE_TRIP, n,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(NOTICE_TRIP, n);
            }
        } catch (Throwable t) {
            // Android refuses a camera service whose permission was revoked while it was
            // running. Say so, because the silent version of this is a driver who is not
            // being watched and does not know it - and go down at once rather than stay
            // a started service that never declared itself: the second refusal is the one
            // that is not thrown back at us, it is Android killing the app a few seconds
            // later for a foreground service that never came up.
            reason = "Foreground: " + name(t);
            closeCamera();
            cameraOn = false;
            if (!dead) stopSelf();
        }
    }

    // ---- helpers shared with Rules

    Config cfg() {
        return cfg;
    }

    Clip clip() {
        return clip;
    }

    Outbox outbox() {
        return outbox;
    }

    /**
     * A rule has decided the driver needs telling. On screen the page does it, because
     * it has the banner on the map and the buzzer already wired up; anywhere else it is
     * a notification on a loud channel and a spoken warning, which are the only two
     * things Android lets through to a locked screen.
     *
     * This arrives on the analysis thread, and both the notice and the voice belong on
     * the main one, so the work is handed over rather than done here.
     */
    void announced(String type, String detail, double peak, Config.Rule rule) {
        if (dead) return;
        final boolean ui = host != null && host.uiVisible();
        final boolean voice = rule.voice && cfg.voiceOn;
        final String lang = rule.voiceLang;
        final boolean mute = cfg.mute;
        main.post(() -> {
            // the trip can have ended between the frame that decided this and the main
            // thread getting round to it, and a notice for a dead service is a sound
            // from an app that is no longer watching anybody
            if (dead) return;
            Host h = host;
            if (h != null && ui) {
                h.notice(type, detail, peak);
            } else {
                alerts.breach(type, detail, rule, mute);
                if (voice) alerts.speak(type, lang, mute);
            }
            foreground(detail);
        });
    }

    /** Tell the page there is something in the queue worth sending. */
    void pageEvent() {
        Host h = host;
        if (h != null) main.post(h::ping);
    }

    /**
     * The event row, as complete as the phone can make it without a database. The page
     * overwrites the four identity fields with its own live ones when it puts this into
     * its out-of-coverage queue, so a phone that is still holding an old assignment
     * cannot misfile an event.
     */
    JSONObject row(String type, String cid, long at, Double value, String details) {
        JSONObject p = new JSONObject();
        try {
            p.put("cid", cid);
            if (!cfg.userId.isEmpty()) p.put("user_id", cfg.userId);
            if (!cfg.vehicleId.isEmpty()) p.put("vehicle_id", cfg.vehicleId);
            if (!cfg.driverId.isEmpty()) p.put("driver_id", cfg.driverId);
            if (!cfg.vehicleName.isEmpty()) p.put("vehicle_name", cfg.vehicleName);
            if (!cfg.driverName.isEmpty()) p.put("driver_name", cfg.driverName);
            p.put("type", type);
            p.put("recorded_at", Outbox.iso(at));
            p.put("details", details);
            p.put("speed", (double) Math.round(kmh));
            if (value != null) p.put("value", value);
            double[] f = fix;
            if (f != null) {
                p.put("lat", f[0]).put("lng", f[1]);
                if (!Double.isNaN(f[2])) p.put("accuracy", (double) Math.round(f[2]));
                if (!Double.isNaN(f[3])) p.put("altitude", f[3]);
                if (!Double.isNaN(f[4])) p.put("heading", (double) Math.round(f[4]));
            }
            double[] b = battery();
            if (b != null) {
                p.put("battery_pct", Math.round(b[0] * 10) / 10.0);
                p.put("charging", b[1] > 0);
            }
            p.put("ignition", true);
        } catch (Exception ignore) {
            // JSONObject only throws on a null key, and none of these are null
        }
        return p;
    }

    /** The charge on the phone at the moment of the event, from the system's own copy. */
    private double[] battery() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return null;
            int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return null;
            int plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0);
            return new double[]{100.0 * level / scale, plugged};
        } catch (Exception e) {
            return null;
        }
    }

    // the trip flag is what lets a service Android restarted by itself know whether it
    // should be running at all: the page sets it by starting, and clears it by stopping
    static boolean tripWanted(Context c) {
        return c.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).getBoolean("trip", false);
    }

    static void tripSet(Context c, boolean yes) {
        c.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE).edit().putBoolean("trip", yes).apply();
    }
}
