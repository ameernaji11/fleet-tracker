package com.fleet.dms;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The door between the driver page and the position runner.
 *
 * Everything the page can ask for is one method here, and every answer is plain JSON: it
 * hands over the trip's order and its own sign-in, and takes back the points the runner
 * collected while the page was frozen. What becomes of those points - which vehicle they
 * are filed against, whether this phone may file them at all - stays the page's decision,
 * because the page is the only thing in this app holding a database session.
 *
 * This is the tracking-only build: there is no lens to open, no rule to install and nothing
 * to be warned about, so the whole surface is the runner's. The names and the JSON shapes
 * are the ones the full driver app already uses, which is what lets the two builds share
 * one bridge and one server.
 */
@CapacitorPlugin(name = "FleetDms")
public class FleetDmsPlugin extends Plugin {

    /**
     * Run one of the page's commands where nothing can escape.
     *
     * Capacitor takes an exception that leaves a plugin method and rethrows it on its
     * own thread, where it is uncaught and the whole app goes down with it. The service,
     * the queue on disk and the settings file are all things a phone can refuse at a
     * moment's notice, so each command is run inside this: the page gets a rejection it
     * can put on the screen, and the app stays open to be told why.
     */
    private void safe(PluginCall call, Runnable work) {
        try {
            work.run();
        } catch (Throwable t) {
            Logger.error("fleet-dms: " + name(t), t);
            call.reject(name(t));
        }
    }

    // ---- the trip's own position runner

    /**
     * Hand the vehicle's positions to the part of this app that Android does not freeze.
     *
     * The order carries the server, the vehicle, the two recording intervals, the geofences
     * the office drew and the flag that says whether a trip is open, and never a sign-in:
     * {@link #trackToken} lends one for an hour at a time and {@link #trackSession} hands over
     * the one this runner keeps. A service that has to be started by this call is given the
     * order on disk instead, which is how a stretch Android had to bring back by itself starts
     * taking positions again before the page has woken up - and, on a phone that was ever given
     * a sign-in of its own, sends them too.
     */
    @PluginMethod
    public void trackStart(PluginCall call) {
        safe(call, () -> {
            JSONObject order = call.getObject("cfg");
            DmsService s = DmsService.get();
            Track t = s == null ? null : s.track();
            JSObject r = new JSObject();
            if (t == null) {
                Track.holdOrder(getContext(), order);
                String why = launchTrack();
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

    /**
     * The trip ended, or this account signed out.
     *
     * {@code keep} is the difference between the two: a trip that stops is a vehicle that goes
     * back to a yard and still has to be seen there, while a sign-out is a handset that belongs
     * to nobody and must go quiet and forget the order. With {@code keep} the runner is told to
     * stop calling itself a trip, writes its closing point, and carries on reporting at the
     * parked interval it was configured with.
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
                s.reporting();
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
     * own outbox, where only a page can empty it, and the queue that keeps running behind the
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

    // ---- the page's two other orders

    /**
     * Put the trip away.
     *
     * In the full app this closed a lens and left the runner beside it. Here the runner is the
     * only thing that can be open, so this answers the same question the page asked there:
     * does this handset still owe the office its positions? A signed-in phone that has ended
     * its trip says yes and changes its notice, and one with nothing left to report is
     * switched off.
     */
    @PluginMethod
    public void stop(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            boolean keeps = s != null && s.keepsReporting();
            if (s == null) {
                // nothing is running, but the flag that tells Android to bring the service
                // back by itself still has to be cleared
                Track.dropOrder(getContext());
            } else if (keeps) {
                s.reporting();
            } else {
                Intent i = new Intent(getContext(), DmsService.class);
                i.setAction(DmsService.ACTION_STOP);
                try {
                    getContext().startService(i);
                } catch (Exception e) {
                    Logger.error("fleet-dms: the stop was not accepted, " + name(e), e);
                    s.stopFromPage();
                }
            }
            JSObject r = new JSObject();
            // What the page has to know: a stop that leaves a runner behind is a phone still
            // reporting, and one that did not is a phone the office will read as gone quiet.
            r.put("reporting", keeps);
            call.resolve(r);
        });
    }

    /** Sign-out: the points kept on this phone stop belonging to this driver. */
    @PluginMethod
    public void clear(PluginCall call) {
        safe(call, () -> {
            DmsService s = DmsService.get();
            if (s != null) s.clearAll();
            else Track.dropOrder(getContext());
            call.resolve();
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

    private static String name(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ---- the service

    /**
     * Ask for the position runner on a phone that has no service alive yet. Answers with the
     * reason it could not, or an empty string when it did.
     *
     * Android will not let an app that is not the screen in front start a location service,
     * so this is only ever called from a page the driver can see - and a phone that has to be
     * brought back later does it from the order on disk, without asking anyone.
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
            // it is told to look at that order rather than to start from nothing.
            DmsService s = DmsService.get();
            if (s != null) {
                s.reporting();
                return "";
            }
            return "this phone would not start the position runner (" + name(t) + ")";
        }
    }
}
