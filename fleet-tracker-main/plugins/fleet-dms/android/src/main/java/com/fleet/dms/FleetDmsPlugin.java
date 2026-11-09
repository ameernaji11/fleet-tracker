package com.fleet.dms;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * The door between the driver page and the camera service.
 *
 * Everything the page can ask for is one method here, and every answer is plain JSON:
 * it sends the rules it read from the Configuration page, feeds the position fixes the
 * background watcher already produces, and takes back the events the service decided on
 * while the page was frozen. What becomes of those events - which driver they are
 * filed against, whether this phone may file them at all - stays the page's decision,
 * because the page is the only thing in this app holding a database session.
 *
 * The driver's own picture box is the page's too. A second of still life is pushed here
 * as a small JPEG, and the page puts it in a corner of its own map, which is the one
 * place on the screen where covering something costs nothing. A box drawn over the page
 * instead would float on top of the Start and End buttons a driver has to press, and it
 * would disappear when he left the app anyway - with another app in front there is no
 * screen of ours to show his face on.
 */
@CapacitorPlugin(
        name = "FleetDms",
        permissions = {
                @Permission(alias = "camera", strings = {Manifest.permission.CAMERA})
        }
)
public class FleetDmsPlugin extends Plugin implements DmsService.Host {

    private static final String CAMERA = "camera";
    // The picture is a courtesy, not a reading: 160 px wide at a modest quality is a
    // few kilobytes a second over the bridge, which is what lets it travel as text.
    private static final int JPEG_QUALITY = 55;

    /** False while the app is in the background; the page says whether it is the screen. */
    private volatile boolean resumed = true;
    private volatile boolean pageVisible = true;
    private volatile boolean wantBox;
    // Which of the page's two buttons last asked for the lens: Start trip, or the driver's
    // own camera view. It decides whether frames are judged, so it is kept here rather than
    // only passed on: Android's permission prompt can come back minutes after the call that
    // asked for it, and the answer has to carry the same instruction.
    private volatile boolean wantTrip;

    @Override
    public void load() {
        super.load();
        // From the first moment this plugin exists it is the page the service may talk
        // to, which is what lets a breach notify the driver inside the app rather than
        // through the shade. handleOnPause takes it back.
        DmsService.setHost(this);
    }

    /**
     * Run one of the page's commands where nothing can escape.
     *
     * Capacitor takes an exception that leaves a plugin method and rethrows it on its
     * own thread, where it is uncaught and the whole app goes down with it. The camera
     * service, the queue on disk and the settings file are all things a phone can refuse
     * at a moment's notice, so each command is run inside this: the page gets a rejection
     * it can put on the screen, and the app stays open to be told why.
     */
    private void safe(PluginCall call, Runnable work) {
        try {
            work.run();
        } catch (Throwable t) {
            Logger.error("fleet-dms: " + name(t), t);
            call.reject(name(t));
        }
    }

    // ---- the page's commands

    /**
     * Open the lens and start judging frames. The service reads its orders from disk
     * when it wakes, so the page must call configure first - which is the order the
     * driver app uses on every trip. `trip` says whether this open is a trip or only the
     * driver's own watch: a watch shows him the lens and files nothing.
     */
    @PluginMethod
    public void start(PluginCall call) {
        safe(call, () -> {
            // A new trip opens the box the way the dashboard has it set; the driver's own
            // button then changes it for the rest of that trip.
            wantBox = Config.load(getContext()).preview;
            wantTrip = call.getBoolean("trip", false);
            if (getPermissionState(CAMERA) != PermissionState.GRANTED) {
                requestPermissionForAlias(CAMERA, call, "cameraResult");
                return;
            }
            String why = launch(wantTrip);
            JSObject r = new JSObject();
            r.put("granted", true);
            r.put("running", DmsService.get() != null);
            // Whether the phone took the order. `running` cannot answer it: an instance
            // left open by the driver's own watch is running while it judges nothing, and
            // a trip that was told "running" then drives its whole shift unwatched.
            r.put("started", why.isEmpty());
            if (!why.isEmpty()) r.put("reason", why);
            r.put("preview", wantBox);
            r.put("trip", wantTrip);
            call.resolve(r);
        });
    }

    /** Android's answer to the camera prompt, which the driver may have refused. */
    @PermissionCallback
    private void cameraResult(PluginCall call) {
        safe(call, () -> {
            boolean granted = getPermissionState(CAMERA) == PermissionState.GRANTED;
            String why = granted ? launch(wantTrip) : "";
            JSObject r = new JSObject();
            r.put("granted", granted);
            r.put("started", why.isEmpty());
            if (!why.isEmpty()) r.put("reason", why);
            call.resolve(r);
        });
    }

    /**
     * End the trip's watch: the lens goes back to whoever wants it.
     *
     * A stop used to be one thing - the service going down with the camera. It is now two, and
     * the difference is what the office sees on the map afterwards. A handset that is signed in
     * with a vehicle still owes that vehicle's positions every second of the day, so its stop
     * puts the lens away and leaves the runner alive beside it; only a phone that has nothing
     * left to report is switched off.
     */
    @PluginMethod
    public void stop(PluginCall call) {
        safe(call, () -> {
            wantBox = false;
            wantTrip = false;
            DmsService s = DmsService.get();
            boolean keeps = s != null && s.keepsReporting();
            if (s == null) {
                // nothing holds the camera, but the flag that tells Android to bring the
                // service back by itself still has to be cleared
                DmsService.tripSet(getContext(), false);
            } else {
                Intent i = new Intent(getContext(), DmsService.class);
                // The lens goes, and the service stays if it still has positions to send.
                i.setAction(keeps ? DmsService.ACTION_LENS : DmsService.ACTION_STOP);
                try {
                    getContext().startService(i);
                } catch (Exception e) {
                    // The same refusal as a start, from an app that is off screen - and here
                    // the cost of swallowing it is a lens left open over a driver nobody is
                    // watching, held by an app that has just been told it stopped.
                    Logger.error("fleet-dms: the camera stop was not accepted, " + name(e), e);
                    if (keeps) s.releaseLens();
                    else s.stopFromPage();
                }
            }
            JSObject r = new JSObject();
            // What the page has to know as well as that the lens is down: a stop that leaves a
            // runner behind is a phone still reporting, and one that did not is a phone the
            // office will read as gone quiet.
            r.put("reporting", keeps);
            call.resolve(r);
        });
    }

    /**
     * Install the rules, the thresholds and the words to speak. Written to disk before
     * anything else so a service Android restarts later wakes up with the same orders.
     */
    @PluginMethod
    public void configure(PluginCall call) {
        safe(call, () -> {
            JSONObject j = call.getObject("cfg");
            if (j == null) {
                call.reject("configure needs the settings object");
                return;
            }
            DmsService s = DmsService.get();
            Config next;
            try {
                next = Config.parse(j, s != null ? s.cfg() : Config.load(getContext()));
            } catch (Exception e) {
                call.reject("The settings could not be read: " + e.getMessage());
                return;
            }
            next.save(getContext());
            if (s != null) s.applyNewConfig(next);
            JSObject r = new JSObject();
            r.put("on", next.on);
            r.put("running", s != null);
            r.put("camera", s != null && s.statusJson().optBoolean("camera"));
            call.resolve(r);
        });
    }

    /** The position and speed the vehicle's own watcher has, so an event can say where. */
    @PluginMethod
    public void feed(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            if (s != null) {
                s.feed(call.getDouble("lat", 0.0), call.getDouble("lng", 0.0),
                        call.getDouble("acc", Double.NaN), call.getDouble("alt", Double.NaN),
                        call.getDouble("heading", Double.NaN), call.getDouble("kmh", 0.0),
                        call.getLong("at", System.currentTimeMillis()));
            }
            JSObject r = new JSObject();
            r.put("ran", s != null);
            call.resolve(r);
        });
    }

    /**
     * Whether the page is the thing the driver is looking at. The app's own pause and
     * resume arrive here without being asked; this is for the moment the page itself
     * goes behind another screen of the same app. A warning only goes to the page when
     * both agree it is visible.
     */
    @PluginMethod
    public void ui(PluginCall call) {
        safe(call, () -> {
            pageVisible = call.getBoolean("visible", true);
            if (pageVisible && resumed) ping();
            call.resolve();
        });
    }

    /** Show or hide the driver's own picture box. */
    @PluginMethod
    public void preview(PluginCall call) {
        safe(call, () -> {
            wantBox = call.getBoolean("show", false);
            JSObject r = new JSObject();
            r.put("show", wantBox);
            call.resolve(r);
        });
    }

    /** What the camera is doing, and why it may not be doing it. */
    @PluginMethod
    public void status(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            JSObject r = new JSObject();
            if (s != null) {
                try {
                    r = JSObject.fromJSONObject(s.statusJson());
                } catch (Exception e) {
                    r.put("error", "The service would not answer: " + e.getMessage());
                }
            } else {
                Config c = Config.load(getContext());
                r.put("running", false).put("camera", false).put("on", c.on)
                        .put("reason", "The driver app has not started a trip.")
                        .put("queued", new Outbox(getContext()).size());
            }
            r.put("granted", getPermissionState(CAMERA) == PermissionState.GRANTED);
            r.put("preview", wantBox);
            call.resolve(r);
        });
    }

    /**
     * The events the camera decided on while the page could not hear them, oldest
     * first, with the queue left empty. The page puts these into its own
     * out-of-coverage outbox and sends them when it can.
     */
    @PluginMethod
    public void drain(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            JSONArray items = s != null ? s.drain() : new Outbox(getContext()).drain();
            JSObject r = new JSObject();
            r.put("items", items);
            call.resolve(r);
        });
    }

    /** Sign-out: the events and pictures on this phone stop belonging to this driver. */
    @PluginMethod
    public void clear(PluginCall call) {
        safe(call, () -> {
            wantBox = false;
            wantTrip = false;
            DmsService s = DmsService.get();
            if (s != null) s.clearAll();
            else new Outbox(getContext()).clear();
            call.resolve();
        });
    }

    // ---- the trip's own position runner

    /**
     * Hand the vehicle's positions to the part of this app that Android does not freeze.
     *
     * The order carries the server, the vehicle, the two recording intervals, the road limits
     * the office drew and the flag that says whether a trip is open, and never a sign-in:
     * {@link #trackToken} lends one for an hour at a time and {@link #trackSession} hands over
     * the one this runner keeps. A service that has to be started by this call is given the
     * order on disk instead, which is how a stretch Android had to bring back by itself starts
     * taking positions again before the page has woken up - and, on a phone that was ever given
     * a sign-in of its own, sends them too.
     *
     * A driver who can see this page may be asking for two different things with the same
     * call: the office's positions, which every signed-in handset wants all day, and the
     * camera that only a trip or his own watch may have. Only the first of these is what
     * reaches a service that is not already alive - an order to report positions must never
     * be the thing that lights a lens over a man who did not ask for one.
     */
    @PluginMethod
    public void trackStart(PluginCall call) {
        safe(call, () -> {
            JSONObject order = call.getObject("cfg");
            boolean tripping = order != null && order.optBoolean("trip", false);
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            JSObject r = new JSObject();
            if (t == null) {
                Track.holdOrder(getContext(), order);
                String why = tripping ? launch(true) : launchTrack();
                DmsService again = DmsService.get();
                r.put("started", why.isEmpty());
                r.put("running", again != null);
                if (!why.isEmpty()) r.put("reason", why);
                call.resolve(r);
                return;
            }
            t.start(order);
            r.put("started", true);
            r.put("running", t.isRunning());
            r.put("status", t.status());
            call.resolve(r);
        });
    }

    /**
     * The page's own sign-in, lent for as long as the page is awake to see it. Kept in memory
     * and never on the disk - which is exactly why it is not enough on its own, and why the
     * runner is given a sign-in of its own at {@link #trackSession}.
     */
    @PluginMethod
    public void trackToken(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            if (t != null) t.token(call.getString("token", ""));
            JSObject r = new JSObject();
            r.put("ran", t != null);
            r.put("status", t == null ? new JSONObject() : t.status());
            call.resolve(r);
        });
    }

    /**
     * The sign-in this runner keeps for itself, so that it can upload with the app shut.
     *
     * The page mints it once, in the background, from the driver's own sign-in: it is a second
     * session for the same account rather than the page's session handed over, because the
     * sign-in server burns a refresh token as soon as it is used and the page would find its own
     * no longer works. Only the two tokens, the moment the first stops working and the account
     * they belong to are kept; the password is not here and cannot be got from what is.
     *
     * It is sealed with a key inside the phone's own Keystore that cannot be exported, and a
     * handset that will not give up such a key simply keeps doing what it did before. Sign-out
     * and giving up the seat both forget it.
     */
    @PluginMethod
    public void trackSession(PluginCall call) {
        safe(call, () -> {
            String raw = call.getString("session", "");
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            boolean kept = t != null ? t.session(raw) : Track.seal(getContext(), raw);
            JSObject r = new JSObject();
            r.put("kept", kept);
            r.put("ran", t != null);
            r.put("status", t == null ? new JSONObject() : t.status());
            call.resolve(r);
        });
    }

    /** An alert the page raised: the point it happened at goes now, not at the next beat. */
    @PluginMethod
    public void trackNow(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            if (t != null) t.now();
            JSObject r = new JSObject();
            r.put("ran", t != null);
            call.resolve(r);
        });
    }

    /**
     * The page has taken these breaches in hand. It writes their rows from here, and the
     * runner must not write a second row for the stretch it has just handed over - which is
     * how one bend ends up with two overspeed records and a driver twice over the count.
     */
    @PluginMethod
    public void trackClaim(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            JSONArray types = call.getArray("types");
            if (t != null) t.claim(types);
            JSObject r = new JSObject();
            r.put("ran", t != null);
            call.resolve(r);
        });
    }

    /**
     * The trip ended, or this account signed out.
     *
     * {@code keep} is the difference between the two, and it is the whole of #211: a trip that
     * stops is a vehicle that goes back to a yard and still has to be seen there, while a
     * sign-out is a handset that belongs to nobody and must go quiet and forget the order. With
     * {@code keep} the runner is told to stop calling itself a trip, writes its closing point,
     * and carries on reporting at the parked interval it was configured with.
     */
    @PluginMethod
    public void trackStop(PluginCall call) {
        safe(call, () -> {
            boolean keep = call.getBoolean("keep", false);
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            JSObject r = new JSObject();
            if (t != null) {
                t.stop(keep);
                // The trip's camera went with the trip, and the page asked for that here in
                // the same breath; the runner stays, and only the watch is put away.
                if (!keep) s.releaseLens();
            } else if (!keep) {
                Track.dropOrder(getContext());
            }
            r.put("ran", t != null);
            r.put("status", t == null ? new JSONObject() : t.status());
            call.resolve(r);
        });
    }

    /**
     * What this phone could not send, given back to the page and cleared from the runner, so
     * the page can put it into the same out-of-coverage queue the rest of the app uses and
     * file it against the session it holds rather than one this service ever saw.
     */
    @PluginMethod
    public void trackDrain(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            JSONArray items = t != null ? t.drain() : Track.peek(getContext());
            JSObject r = new JSObject();
            r.put("items", items);
            call.resolve(r);
        });
    }

    /**
     * Records the page holds but cannot send, given to the runner, which can. The other
     * direction of {@link #trackDrain}, and the answer to a vehicle whose phone works but whose
     * office has gone quiet: a page Android froze hours ago leaves its collected points in its
     * own outbox, where only a page can flush them, and the queue that keeps running behind the
     * driver's back never sees them.
     * <p>
     * Nothing is thrown away on the way. The page keeps its copies until this call says the
     * runner holds the whole batch, and the runner refuses a batch it cannot post - no order,
     * no sign-in, no room - so the records are in one queue or the other at every moment, never
     * in neither.
     */
    @PluginMethod
    public void trackPush(PluginCall call) {
        safe(call, () -> {
            JSONArray rows = call.getArray("rows");
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            JSObject r = new JSObject();
            // A dead service is not a spare queue: with nothing alive to post, the file on disk
            // would be written by two hands at once. The page keeps the records and asks again
            // after it has brought the runner back.
            int kept = t == null ? 0 : t.handover(rows);
            r.put("kept", kept);
            r.put("ran", t != null);
            r.put("status", t == null ? new JSONObject() : t.status());
            call.resolve(r);
        });
    }

    /** What the runner is doing, and why it may not be doing it. */
    @PluginMethod
    public void trackStatus(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            JSObject r = new JSObject();
            if (t != null) {
                r.put("running", t.isRunning());
                r.put("service", true);
                r.put("status", t.status());
            } else {
                r.put("running", false);
                r.put("service", false);
                r.put("wanted", Track.wanted(getContext()));
            }
            call.resolve(r);
        });
    }

    // ---- the phone's own battery saving

    /** Whether Android has already been told to leave this app alone when it saves battery. */
    @PluginMethod
    public void ignoreState(PluginCall call) {
        safe(call, () -> {
            JSObject r = new JSObject();
            r.put("exempt", ignoring());
            call.resolve(r);
        });
    }

    /**
     * Ask the driver, in Android's own words, to keep this app out of its battery saving. The
     * wake lock covers a trip while it runs; it is this saving that defers the uploads of a
     * phone that sat in a yard for two hours, and on the handsets this fleet drives that is
     * what strands a queue until the driver opens the app again. Declining is a plain answer
     * and costs nothing but the delay.
     */
    @PluginMethod
    public void ignoreAsk(PluginCall call) {
        safe(call, () -> {
            boolean free = ignoring();
            JSObject r = new JSObject();
            r.put("exempt", free);
            if (free) {
                call.resolve(r);
                return;
            }
            // The direct ask is one dialog with an Allow in it. Not every handset answers it,
            // and one that does not is sent to the list screen instead, where the driver has to
            // find this app himself - slower, but the same decision.
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.fromParts("package", getContext().getPackageName(), null));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                r.put("asked", true);
            } catch (Throwable t) {
                try {
                    Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                    r.put("asked", true).put("list", true);
                } catch (Throwable t2) {
                    r.put("asked", false).put("reason", name(t2));
                }
            }
            call.resolve(r);
        });
    }

    /** What Android itself says about this app and its battery saving. */
    private boolean ignoring() {
        try {
            PowerManager p = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            return p != null && p.isIgnoringBatteryOptimizations(getContext().getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- the clips an alert left on this phone

    /**
     * The clip folder, whether or not a trip is running. A phone is asked for the
     * picture of an event it recorded an hour ago, with the camera long shut and the
     * service gone, and the files outlive both.
     */
    private Clip clipNow() {
        DmsService s = DmsService.get();
        return s != null ? s.clip() : new Clip(getContext());
    }

    /** The clips this phone still holds, each named by the event it belongs to. */
    @PluginMethod
    public void clips(PluginCall call) {
        safe(call, () -> {
            Clip c = clipNow();
            JSONArray a = new JSONArray();
            if (c != null) {
                for (String cid : c.cids()) a.put(cid);
            }
            JSObject r = new JSObject();
            r.put("cids", a);
            call.resolve(r);
        });
    }

    /**
     * A piece of one clip as text small enough to travel the bridge. The page is the only
     * thing in this app holding a session, so it is the page that puts the file where an
     * administrator can watch it - and nothing uploads from here.
     *
     * It asks for a piece at a time because the whole file in one string is what used to
     * end a trip: base64 costs two bytes of char for every three bytes of film, Capacitor
     * copies the answer again on its way to the page, and a two-megabyte recording asked
     * a phone that was already holding a camera and two models for room it did not have.
     */
    @PluginMethod
    public void clipData(PluginCall call) {
        safe(call, () -> {
            String cid = call.getString("cid", "");
            long at = call.getLong("at", 0L);
            Clip c = clipNow();
            long total = c == null ? 0 : c.size(cid);
            JSObject r = new JSObject();
            r.put("total", total);
            r.put("at", at);
            if (c == null || total == 0) {
                r.put("missing", true);
                call.resolve(r);
                return;
            }
            byte[] b = c.read(cid, at, Clip.MAX_CHUNK);
            if (b == null || b.length == 0) {
                r.put("missing", true);
                call.resolve(r);
                return;
            }
            r.put("bytes", b.length);
            r.put("eof", at + b.length >= total);
            r.put("b64", Base64.encodeToString(b, Base64.NO_WRAP));
            call.resolve(r);
        });
    }

    /** The server has the picture: this phone stops carrying it. */
    @PluginMethod
    public void clipDrop(PluginCall call) {
        safe(call, () -> {
            clipNow().drop(call.getString("cid", ""));
            call.resolve();
        });
    }

    // ---- what the service hands the page

    @Override
    public boolean uiVisible() {
        return resumed && pageVisible;
    }

    @Override
    public void notice(String type, String detail, double value) {
        JSObject j = new JSObject();
        j.put("type", type);
        j.put("detail", detail);
        j.put("value", value);
        notifyListeners("dmsBreach", j);
    }

    @Override
    public boolean previewWanted() {
        return wantBox && uiVisible();
    }

    /**
     * One picture for the box, as text small enough to travel the bridge once a second.
     * The bitmap handed over belongs to this method: it is recycled on every path,
     * including the one where the driver has just hidden the box.
     */
    @Override
    public void frame(Bitmap upright) {
        if (upright == null) return;
        try {
            if (!previewWanted()) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            JSObject j = new JSObject();
            j.put("img", "data:image/jpeg;base64,"
                    + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            j.put("w", upright.getWidth());
            j.put("h", upright.getHeight());
            notifyListeners("dmsFrame", j);
        } catch (Throwable t) {
            // losing the driver's own picture is not worth a trip: the judgement of
            // his face goes on whether or not he can see it
            Logger.warn("fleet-dms: the preview frame could not be sent: " + name(t));
        } finally {
            if (!upright.isRecycled()) upright.recycle();
        }
    }

    @Override
    public void ping() {
        DmsService s = DmsService.get();
        JSObject j = new JSObject();
        j.put("queued", s == null ? 0 : s.outboxSize());
        notifyListeners("dmsPing", j);
    }

    private static String name(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ---- the service

    /**
     * Ask for the position runner on a phone that has no service alive yet. Answers with the
     * reason it could not, or an empty string when it did.
     *
     * This is the one launch that carries no ask for a lens, and the only one a handset that
     * is not in the middle of a trip ever makes. Android will not let a background app start
     * a camera service at all, so a page that wants positions all day would otherwise be
     * refused by the very rule that is supposed to let it report them.
     */
    private String launchTrack() {
        Context ctx = getContext();
        Intent i = new Intent(ctx, DmsService.class);
        i.setAction(DmsService.ACTION_TRACK);
        try {
            ContextCompat.startForegroundService(ctx, i);
            return "";
        } catch (Throwable t) {
            Logger.error("fleet-dms: the position runner could not be started", t);
            // The order is already on disk, so nothing is lost by a service that is already
            // alive taking it instead of a fresh one: this instance has the same runner, and
            // it is told to look at that order rather than to open anything.
            DmsService s = DmsService.get();
            if (s != null) {
                s.reporting();
                return "";
            }
            return "this phone would not start the position runner (" + name(t) + ")";
        }
    }

    /**
     * Hand the order to the camera service. Answers with the reason it could not, or an
     * empty string when it did.
     */
    private String launch(boolean tripping) {
        Context ctx = getContext();
        Intent i = new Intent(ctx, DmsService.class);
        i.setAction(DmsService.ACTION_START);
        i.putExtra(DmsService.EXTRA_TRIP, tripping);
        try {
            // The page starts a trip from its own button, so this happens while the app
            // is on screen - the only moment Android lets a camera service begin. From
            // anywhere else it is a refusal, and the page is told by the status call.
            ContextCompat.startForegroundService(ctx, i);
            return "";
        } catch (Throwable t) {
            Logger.error("fleet-dms: the camera service could not be started", t);
            // Android 12 and later refuse this from an app that is not the screen in front:
            // a trip started by the speed sentry, or resumed after a process Android killed.
            // The order is not lost when a service is already alive - the driver's own watch
            // holds one, and so does a lens this trip opened a minute ago - so it goes to
            // that instance directly. What used to happen to this exception was nothing at
            // all: it was written to logcat, the page was told the camera was running, and
            // the driver drove a shift that was filmed and judged by nobody.
            DmsService s = DmsService.get();
            if (s != null) {
                s.holdFromPage(tripping);
                return "";
            }
            return "this phone would not start the camera service (" + name(t) + ")";
        }
    }

    // ---- app foreground and background

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        resumed = true;
        DmsService.setHost(this);
        // events gathered while the page was away are the page's business now
        ping();
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        resumed = false;
        // previewWanted() goes false with this, so the camera stops spending a
        // picture on a screen that is no longer his to look at. The trip keeps
        // watching: that is the whole point of the service.
    }
}
