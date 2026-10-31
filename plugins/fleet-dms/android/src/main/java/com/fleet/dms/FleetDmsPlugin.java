package com.fleet.dms;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
     * driver app uses on every trip.
     */
    @PluginMethod
    public void start(PluginCall call) {
        safe(call, () -> {
            // A new trip opens the box the way the dashboard has it set; the driver's own
            // button then changes it for the rest of that trip.
            wantBox = Config.load(getContext()).preview;
            if (getPermissionState(CAMERA) != PermissionState.GRANTED) {
                requestPermissionForAlias(CAMERA, call, "cameraResult");
                return;
            }
            launch();
            JSObject r = new JSObject();
            r.put("granted", true);
            r.put("running", DmsService.get() != null);
            r.put("preview", wantBox);
            call.resolve(r);
        });
    }

    /** Android's answer to the camera prompt, which the driver may have refused. */
    @PermissionCallback
    private void cameraResult(PluginCall call) {
        safe(call, () -> {
            boolean granted = getPermissionState(CAMERA) == PermissionState.GRANTED;
            if (granted) launch();
            JSObject r = new JSObject();
            r.put("granted", granted);
            call.resolve(r);
        });
    }

    /** End the trip's watch: the lens goes back to whoever wants it. */
    @PluginMethod
    public void stop(PluginCall call) {
        safe(call, () -> {
            wantBox = false;
            DmsService s = DmsService.get();
            if (s == null) {
                // nothing holds the camera, but the flag that tells Android to bring the
                // service back by itself still has to be cleared
                DmsService.tripSet(getContext(), false);
            } else {
                Intent i = new Intent(getContext(), DmsService.class);
                i.setAction(DmsService.ACTION_STOP);
                try {
                    getContext().startService(i);
                } catch (Exception ignore) {
                    // a service that refuses the stop is being taken down anyway
                }
            }
            call.resolve();
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
            DmsService s = DmsService.get();
            if (s != null) s.clearAll();
            else new Outbox(getContext()).clear();
            call.resolve();
        });
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
     * One clip as text small enough to travel the bridge. The page is the only thing in
     * this app holding a session, so it is the page that puts the file where an
     * administrator can watch it - and nothing uploads from here.
     */
    @PluginMethod
    public void clipData(PluginCall call) {
        safe(call, () -> {
            String cid = call.getString("cid", "");
            byte[] b = clipNow().read(cid);
            JSObject r = new JSObject();
            if (b == null) {
                r.put("missing", true);
                call.resolve(r);
                return;
            }
            r.put("bytes", b.length);
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

    private void launch() {
        Context ctx = getContext();
        Intent i = new Intent(ctx, DmsService.class);
        i.setAction(DmsService.ACTION_START);
        try {
            // The page starts a trip from its own button, so this happens while the app
            // is on screen - the only moment Android lets a camera service begin. From
            // anywhere else it is a refusal, and the page is told by the status call.
            ContextCompat.startForegroundService(ctx, i);
        } catch (Throwable t) {
            Logger.error("fleet-dms: the camera service could not be started", t);
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
