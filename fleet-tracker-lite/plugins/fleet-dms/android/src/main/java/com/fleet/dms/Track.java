package com.fleet.dms;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.getcapacitor.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The trip's own record of where the vehicle is, kept and sent by the part of this app
 * that Android does not freeze.
 *
 * The driver page has always been the thing that wrote positions, and it is the honest
 * place for them: it holds the sign-in, it knows the vehicle, and the row guards on the
 * positions table read that sign-in and decide what gets in. What it cannot do is any of
 * it while the screen is off. Android stops an app's JavaScript the moment the app is not
 * the screen in front - a locked phone, an answered call, a message read over this app -
 * and a page that is stopped samples nothing, throttles nothing, queues nothing and sends
 * nothing. The driver presses Start, drives away, locks the phone, and the office's map
 * draws the trip with eight minutes of road missing out of it, because the only thing that
 * knew where he was had been put to sleep along with his screen.
 *
 * So this runs in the service that already survives that: the one that holds the wake lock,
 * is restarted by Android when it has been killed, and keeps its own queue on disk. It
 * listens to the handset's own location providers, applies the fleet's two recording
 * intervals exactly as the page applies them, keeps the corner points and the last point of a
 * trip, and puts the rows where the dashboard reads them.
 *
 * It uploads with the driver's own sign-in. That is not a second way into the database: it is
 * the same driver in a different hand, and every guard on the positions table still applies to
 * it, so this cannot file a position for a vehicle its driver does not own any more than the
 * page can. Two sign-ins reach it: the one the page lends from memory every fifteen seconds,
 * and one the page mints for this runner alone at the driver's own sign-in, which is kept
 * sealed on the phone by {@link Session} and started again here whenever it runs out. The
 * second is what changed the shape of a shift: the lent token dies an hour after sign-in, a
 * page Android has frozen cannot lend a new one, and a runner that had nothing else to send
 * with spent the rest of the day collecting - which is the driver's "+243 records waiting"
 * and the office's blank map. A runner started without the app now reads the sealed sign-in
 * back and goes on uploading. What it cannot do is sign in by itself: a phone that has never
 * been handed one, or that lost the blob, collects and the page drains what is left, so
 * nothing is ever dropped for a reason the phone could have waited out.
 *
 * This is the tracking-only build, so this is the only thing the service writes: positions.
 * The full driver app also takes over the events its page cannot see - a zone the vehicle
 * crossed behind a locked screen, the overspeed and pedal warnings a driver would not hear
 * until he unlocked the phone. There are no events and no warnings in this app, so none of
 * that is here, and the office's Events table stays exactly as empty as a phone with no
 * camera and no alerting should leave it. The geofences themselves are still carried and
 * still tracked, because the page draws them and shows which one the vehicle is inside, and
 * that answer has to be the same whether or not the screen is on.
 */
class Track {

    /** How often the handset is asked while the vehicle is being driven. The page's own
     *  watcher speaks about this often too. */
    private static final long SAMPLE_MS = 1000;
    /** How often the handset is asked when nothing is being driven and no trip is open. The
     *  queue is still written at the fleet's parked interval - this number only decides how
     *  long the phone's receiver stays warm, and a floor of a second an hour would not notice
     *  a drive starting for that same hour, which is the failure this whole runner exists to
     *  end. The distance below is what answers the moment the vehicle actually moves. */
    private static final long IDLE_MS = 15000;
    private static final float IDLE_M = 15f;
    /** As many rows as one request. The page's own queue sends this many at a time. */
    private static final int BATCH = 200;
    /** A phone that never comes back for a signal is a broken phone; newest rows kept. */
    private static final int MAX_ROWS = 6000;
    /** The queue is tried again this often, so a dead spot that ends between two points
     *  an hour apart does not cost an hour of track. */
    private static final long TRY_MS = 15000;
    /** The same corner the page adds a point of its own for: two sampled seconds of a bend
     *  would otherwise be drawn as one straight cut across it. */
    private static final double TURN_DEG = 30;
    /** The speed the page measures "moving" against, and the number the automatic trip end
     *  is argued from: below it the vehicle idles and reports on the parked interval. */
    private static final double MOVING_KMH = 2;

    private static final String PREFS = "fleet.track";
    private static final String K_SENT = "sent", K_MOV = "moving", K_PARK = "parked",
            K_URL = "url", K_ANON = "anon", K_WANT = "want", K_PEND = "pending",
            K_TRIP = "trip", K_ZONE = "zones";

    private final DmsService svc;
    private final Context ctx;
    private final SharedPreferences sp;
    private final File file;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile java.util.concurrent.ExecutorService net;

    // ---- what the page hands over

    /**
     * The only server this runner will ever talk to. The page names the Supabase project it
     * reads from its own config, and the same test is applied to it at www/index.html, but
     * this service is the one that carries the driver's live sign-in and posts a whole trip
     * with it - so the host is checked here as well, at the moment it arrives, at the moment it
     * is read back off the disk after a restart, and again at the connection. A host it is not
     * allowed to speak to is also a host it will not ask to start a sign-in again: the sealed
     * refresh token only ever goes to the same address, checked the same way.
     * A page that had been tricked into naming somebody else's host would otherwise hand
     * over both the route and the key that writes it, and a bad host typed into the config
     * would keep being used by a service that outlives the page that made the mistake.
     */
    private static final java.util.regex.Pattern HOST =
            java.util.regex.Pattern.compile("^https://[a-z0-9]{15,30}\\.supabase\\.co$");

    private static boolean hostOk(String u) { return u != null && HOST.matcher(u).matches(); }

    private volatile String url = "", anon = "", token = "";
    /**
     * The sign-in this runner owns outright, read back from the sealed copy on this phone: the
     * access token it last started, the refresh token that can start the next one, the moment
     * the first stops working, and the account both belong to. All empty until a page hands a
     * minted sign-in over, and cleared again by sign-out, by giving up the seat, and by the
     * sign-in server refusing the refresh token. See {@link Session}.
     */
    private volatile String own = "", refresh = "", ownUid = "";
    private volatile long ownUntil;
    /** The last moment the sign-in server was asked to start a new hour, so a phone sitting in
     *  a dead spot with a queue on it cannot build a loop of auth requests behind itself. */
    private volatile long lastRenew;
    private volatile String uid = "", vid = "", did = "", vname = "", dname = "";
    private volatile int movingS = 10, parkedS = 3600;
    /** The shapes the office drew, with the vehicle's state inside each one. */
    private final List<Zone> zones = new ArrayList<>();

    // ---- what this is doing

    private final JSONArray rows = new JSONArray();
    private LocationManager lm;
    private final List<String> providers = new ArrayList<>();
    private volatile boolean running;
    /** Whether a trip is open on this phone. See {@link #trip}. */
    private volatile boolean trip;
    /** Whether the last fix said the wheels were turning. Decides the beat and the wake lock. */
    private volatile boolean isMoving;
    /** Consecutive fixes on each side of the moving line, so one drifting satellite cannot
     *  decide that the vehicle has started being driven. */
    private int hotStreak, coldStreak;
    /** When this runner's own judgement last said the vehicle had stopped being driven, in
     *  {@link SystemClock#elapsedRealtime} units, or 0 while no fix has been judged. The page
     *  keeps a still clock of its own, but Android freezes that one when the driver locks his
     *  handset, so a trip that must not be cut in two asks this clock instead. */
    private volatile long stoodAt;
    /** The last moment the beat was changed, so a vehicle crawling in a queue cannot re-tune
     *  the handset's receiver every second. */
    private long lastBeat;
    /** Whether {@link #tick} is posted right now. Never more than one clock per trip. */
    private volatile boolean scheduled;
    // Whether this runner has a sign-in to send with at all - one a page lent it from memory,
    // or one it keeps sealed on this phone. Android can restart this runner mid-shift, and a
    // restarted one used to be unable to send because the lending was never on the disk; now it
    // reads its own sign-in back and carries on. See {@link Session}.
    private volatile boolean allowed;
    private volatile boolean want;
    private long lastSent, lastTry, lastFixAt, sent;
    private double heading = Double.NaN, prevV, prevT, lastA;
    private boolean first = true, havePrev;
    // The newest point this has measured but not yet put in the queue's send order, kept so
    // the end of a trip can write the point the office draws its finish from, whatever the
    // fleet's recording interval was doing at the time.
    private volatile JSONObject latest;
    private volatile long latestAt;
    private volatile String error = "", why = "";

    Track(DmsService s) {
        svc = s;
        ctx = s;
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        file = new File(ctx.getFilesDir(), "dms" + File.separator + "pos.queue");
        movingS = sp.getInt(K_MOV, movingS);
        parkedS = sp.getInt(K_PARK, parkedS);
        // A runner Android brought back by itself reads the server's address off the disk, so
        // the host is checked again here: a route must never leave this phone for an address
        // that is not the fleet's own Supabase project, whatever has happened to the file
        // since the page last spoke. The points are kept on disk either way, and the page
        // drains them when it wakes.
        String saved = sp.getString(K_URL, "");
        url = hostOk(saved) ? saved : "";
        if (!saved.isEmpty() && url.isEmpty())
            why = "the server address kept on this phone is not a Supabase project, so nothing has been sent to it";
        anon = sp.getString(K_ANON, "");
        sent = sp.getLong(K_SENT, 0);
        want = sp.getBoolean(K_WANT, false);
        trip = sp.getBoolean(K_TRIP, false);
        readZones(kept(sp.getString(K_ZONE, null)));
        take(sp.getString(K_PEND, null));
        sp.edit().remove(K_PEND).apply();
        load();
        // Before this line a runner Android had to start on its own could only collect, because
        // the only sign-in it ever knew was one a page held in memory and had no way to pass on
        // while it was frozen. The sealed copy is what a restarted service reads instead.
        restore();
        checkOwner();
        // A trip that Android had to bring back by itself is still a trip: it keeps taking
        // positions from this second, and the page hands over a token when it wakes.
        if (want) resume();
    }

    /** A runner used by the page when no service is alive: it only empties the queue. */
    static JSONArray peek(Context c) {
        try {
            File f = new File(c.getFilesDir(), "dms" + File.separator + "pos.queue");
            if (!f.isFile()) return new JSONArray();
            JSONArray a = parse(new String(readAll(f), "UTF-8"));
            f.delete();
            return a;
        } catch (Throwable t) {
            return new JSONArray();
        }
    }

    // ---- the page's commands

    /**
     * Start, or keep, the runner for this phone. Everything the page knows about the
     * vehicle's own settings arrives here, because a service Android restarts mid-shift has
     * to be sampling at the right intervals before the page has had a chance to speak.
     * Never call this with a token in it - see {@link #token}.
     *
     * The order is standing whenever a driver is signed in with a vehicle, not only while a
     * trip is open: the fleet's two intervals are what the office agreed to be told by, and a
     * handset that only ever reported during a trip went quiet for the whole of a drive that
     * began with nobody pressing Start. `trip` is what tells the two apart, and it decides
     * both the beat and the `ignition` column the office's trip list is cut from.
     */
    void start(JSONObject j) {
        if (j == null) j = new JSONObject();
        if (j.has("url")) {
            String u = j.optString("url", "");
            if (hostOk(u)) url = u;
            else why = "the address the page gave for the server is not a Supabase project, so nothing was sent to it";
        }
        if (j.has("anon")) anon = j.optString("anon", anon);
        movingS = clamp(j.optInt("movingS", movingS), 1, 3600);
        parkedS = clamp(j.optInt("parkedS", parkedS), 5, 43200);
        // an order that says nothing about the trip leaves the flag as this runner holds it,
        // so a settings re-read cannot quietly end a trip that is being driven
        if (j.has("trip")) trip = j.optBoolean("trip", trip);
        JSONObject ids = j.optJSONObject("ids");
        if (ids != null) {
            uid = ids.optString("user_id", uid);
            vid = ids.optString("vehicle_id", vid);
            did = ids.optString("driver_id", did);
            vname = ids.optString("vehicle_name", vname);
            dname = ids.optString("driver_name", dname);
        }
        checkOwner();
        // as with the page's own copy of the shapes: no array is not an empty fleet
        readZones(j.optJSONArray("zones"));
        sp.edit().putInt(K_MOV, movingS).putInt(K_PARK, parkedS)
                .putString(K_URL, url).putString(K_ANON, anon)
                .putString(K_ZONE, zonesJson())
                .putBoolean(K_WANT, true).putBoolean(K_TRIP, trip).apply();
        want = true;
        if (uid.isEmpty() || vid.isEmpty()) {
            why = "this phone has not been told which vehicle it is driving";
            return;
        }
        // A refused address keeps its complaint on the strip, and an accepted one clears it:
        // with no usable server this trip cannot reach the office, and that is the one thing
        // the driver and the page both need to hear while it is true.
        if (hostOk(url)) why = "";
        resume();
        beat();
    }

    /**
     * The sign-in moves: the page's own client refreshes it an hour in, and only the page
     * can see that happen. Handing it over here is the cheapest way for this runner to send,
     * and it stays in memory like it always did. What has changed is that it is no longer the
     * only way: a page that is frozen, or an app that was swiped away, leaves the sign-in
     * minted at {@link #session} to carry the rest of the shift.
     */
    void token(String t) {
        if (t == null || t.isEmpty()) return;
        token = t;
        allowed = true;
        if (!error.isEmpty()) error = "";
        trySend();
    }

    /**
     * The sign-in the page minted for this runner alone, at the moment the driver signed in
     * with his own hands, in the shape the sign-in server answers in. Sealed onto this phone so
     * that a service Android restarts, or one that simply outlived the hour of the token the
     * page lent it, can go on uploading with the app closed.
     *
     * A second sign-in for one driver, rather than the page's own kept twice, because the
     * server burns a refresh token the moment it is used: see {@link #renew}. The password is
     * not in here and cannot be got out of what is stored.
     *
     * @return whether this phone can now keep a sign-in. Nothing else changes if it cannot -
     *         the page still lends a token every fifteen seconds it is awake to.
     */
    boolean session(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        if (!apply(raw, true)) {
            Logger.error("fleet-dms: the sign-in handed to the position runner would not read");
            return false;
        }
        checkOwner();
        trySend(true);
        return !refresh.isEmpty();
    }

    /**
     * Put one sign-in into memory, and - when asked - onto the disk.
     *
     * @param ans  the sign-in server's own answer, which the page passes on as it received it
     *             and a refresh passes on itself.
     * @param seal whether to overwrite the sealed copy. A refresh must answer yes: the server
     *             has just burned the token this phone was holding and handed a new one, and a
     *             runner that dies before writing it down has no sign-in left to go back to.
     */
    private boolean apply(String ans, boolean seal) {
        String kept = trim(ans);
        if (kept == null) return false;
        try {
            JSONObject j = new JSONObject(kept);
            own = j.optString("a", "");
            refresh = j.optString("r", "");
            ownUntil = j.optLong("x", 0);
            ownUid = j.optString("u", "");
        } catch (Exception e) {
            return false;
        }
        token = own;
        allowed = true;
        if (!error.isEmpty()) error = "";
        if (seal) Session.save(ctx, kept);
        return true;
    }

    /**
     * One sign-in, cut down to the four things this runner can use and put in the shape it
     * keeps them. Anything else the sign-in server sends - the driver's email, his metadata,
     * the whole of his profile - has no business on a phone that only needs to prove who it is.
     *
     * @return the compact sign-in, or null when the answer is not one a runner can send with.
     */
    private static String trim(String ans) {
        try {
            JSONObject j = new JSONObject(ans);
            String a = j.optString("access_token", ""), r = j.optString("refresh_token", "");
            if (a.isEmpty() || r.isEmpty()) return null;
            // The server says both the absolute moment this token stops working and how long it
            // was worth from now. The first is what survives a reboot, so it wins when present.
            long at = j.optLong("expires_at", 0), in = j.optLong("expires_in", 0);
            JSONObject usr = j.optJSONObject("user");
            JSONObject o = new JSONObject();
            o.put("a", a).put("r", r)
                    .put("x", at > 0 ? at * 1000L : in > 0 ? System.currentTimeMillis() + in * 1000L : 0)
                    .put("u", usr == null ? "" : usr.optString("id", ""));
            return o.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Seal a sign-in for a runner that is not alive yet. The page mints one when the driver
     * signs in, and on a handset with no service at that moment there is nothing to hand it to -
     * so it goes on the disk here, in the same blob the next service reads back, rather than
     * being dropped until the page happens to talk to a live one.
     */
    static boolean seal(Context c, String raw) {
        String kept = trim(raw);
        if (kept == null) return false;
        Session.save(c, kept);
        return true;
    }

    /** The sign-in kept on this phone, put back into memory. See {@link Session}. */
    private void restore() {
        String raw = Session.load(ctx);
        if (raw == null || raw.isEmpty()) return;
        try {
            JSONObject j = new JSONObject(raw);
            own = j.optString("a", "");
            refresh = j.optString("r", "");
            ownUntil = j.optLong("x", 0);
            ownUid = j.optString("u", "");
        } catch (Exception e) {
            own = refresh = ownUid = "";
            ownUntil = 0;
            return;
        }
        if (own.isEmpty() || refresh.isEmpty()) {
            own = refresh = ownUid = "";
            ownUntil = 0;
            return;
        }
        allowed = true;
    }

    /**
     * This phone stops owning a sign-in, and forgets the copy on the disk. Sign-out, a seat
     * given up, a blob that would not read, a refresh token the server says nothing about, or
     * one that belongs to a driver who is not the one this runner was told about.
     */
    private void forget() {
        String a = own;
        own = refresh = ownUid = "";
        ownUntil = 0;
        // The borrowed token is the page's to keep using; the runner's own is only ever in hand
        // while the refresh token that made it can be.
        if (!a.isEmpty() && a.equals(token)) token = "";
        allowed = !token.isEmpty();
        Session.wipe(ctx);
    }

    /** Give up a sign-in kept on this phone that was minted by a different driver's sign-in. */
    private void checkOwner() {
        if (ownUid.isEmpty() || uid.isEmpty() || ownUid.equals(uid)) return;
        Logger.error("fleet-dms: the sign-in kept on this phone belongs to another driver");
        forget();
    }

    /**
     * A sign-in to post with, answered on the network thread because answering it can need a
     * request of its own. This is the whole of the fix for a shift that arrives as "+243
     * records waiting": the gate used to be "has a page lent me a token lately", an hour-old
     * answer on a phone whose page has been frozen since the driver locked his screen, and a
     * runner that only ever collected costs the office every trip, tacho second and kilometre
     * counted from those rows.
     *
     * @return the token to send with, or "" when this phone genuinely has nothing to send as.
     */
    private String ready() {
        if (token.isEmpty()) token = own;
        // A minute early, so a two-hundred-row batch never starts a flight whose sign-in runs
        // out underneath it. Only this runner's own sign-in has a known moment of death: a
        // token the page lent says nothing about its own, and is simply asked again if refused.
        if (token.equals(own) && ownUntil > 0 && System.currentTimeMillis() >= ownUntil - 60000)
            renew(false);
        allowed = !token.isEmpty();
        return token;
    }

    /**
     * Start a fresh hour with the sign-in kept on this phone. Network thread only.
     * <p>
     * The refresh token used here is not the one the page's own client holds, and that is the
     * reason the page mints a second sign-in at all: Supabase burns a refresh token the moment
     * it is exchanged and hands back a new one, so a runner that refreshed the page's sign-in
     * would leave the page unable to refresh its own - and the driver would find himself signed
     * out by his own background service at the moment he unlocked his handset, which is the one
     * case this was added for. Two families, one driver, and each hand keeps its own.
     *
     * @param now ask the server whatever else has been asked recently. {@link #send} uses this
     *            for the one retry a refused batch is worth; the clock-driven ask does not, so
     *            a phone sitting in a dead spot with a queue on it cannot build a loop of auth
     *            requests behind itself.
     * @return whether there is a usable token in hand afterwards.
     */
    private boolean renew(boolean now) {
        String r = refresh;
        if (r.isEmpty() || !hostOk(url) || anon.isEmpty()) return false;
        long n = System.currentTimeMillis();
        if (now || n - lastRenew >= 60000) lastRenew = n;
        else return !token.isEmpty();
        String body;
        try {
            body = new JSONObject().put("refresh_token", r).toString();
        } catch (Exception e) {
            return false;
        }
        String ans = auth(body);
        if (ans == null) return !token.isEmpty();
        if (ans.isEmpty()) return false;
        // Written down before it is used: see the seal argument of {@link #apply}.
        return apply(ans, true);
    }

    /**
     * One request to the sign-in server of the same project the page logs in to.
     *
     * @return the answer as text, "" when the server refused this refresh token outright and it
     *         has been forgotten, or null when this phone never heard anything at all - which
     *         is the only answer that leaves the sealed sign-in worth asking about again.
     */
    private String auth(String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url + "/auth/v1/token?grant_type=refresh_token").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("apikey", anon);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code == 200) return readAll(c.getInputStream());
            if (code >= 400 && code < 500 && code != 408 && code != 429) {
                Logger.error("fleet-dms: the sign-in kept on this phone was refused (code " + code + ")");
                forget();
                return "";
            }
            return null;
        } catch (Throwable t) {
            // never reached the server: nothing here has learned that this refresh token is bad
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * The trip ended. `keep` is the difference between that and this phone going quiet: a
     * driver who parks has not asked the office to stop seeing his vehicle, so with the flag
     * up the runner drops the trip and carries on reporting at the fleet's parked interval.
     * Either way the last point of the trip is written, and nothing that has not been sent is
     * thrown away.
     */
    void stop(boolean keep) {
        want = keep;
        trip = false;
        sp.edit().putBoolean(K_WANT, keep).putBoolean(K_TRIP, false).apply();
        // The last point of a trip is where the office draws its end, and it is the one
        // point the fleet's interval would otherwise hold back for an hour: a driver who
        // stops at the yard gates wants the trip to finish at the gates.
        JSONObject last = latest;
        if (last != null && latestAt > 0) {
            try {
                last.put("speed", 0);
                last.put("ignition", false);
                last.put("recorded_at", iso(System.currentTimeMillis()));
                synchronized (rows) {
                    push(last);
                }
            } catch (Exception ignore) {
                // a trip that cannot write its own last point still ends
            }
        }
        latest = null;
        if (keep) {
            // The listeners stay where they are; only the beat they are asked on changes, and
            // the wake lock the trip was holding comes back to Android. A phone that reports
            // an hourly point all day has no business holding the CPU awake all day.
            beat();
            trySend(true);
            return;
        }
        running = false;
        close();
        // Urgent: the page has stopped being able to wait on this, and a trip that ends with
        // a batch still on the phone is the gap the office then reads as "not reported".
        trySend(true);
        DmsService s = svc;
        if (s != null) s.reporting();
    }

    /** Everything still waiting on this phone, given back to the page, and the queue emptied. */
    JSONArray drain() {
        JSONArray a = new JSONArray();
        synchronized (rows) {
            try {
                a = new JSONArray(rows.toString());
            } catch (Exception e) {
                // a queue this cannot hand over is the queue still on disk; nothing is lost
                // by the page finding it empty, because it was never sent
                Logger.error("fleet-dms: the position queue would not be read", e);
                return a;
            }
            empty();
            save();
        }
        return a;
    }

    /**
     * The positions the page has collected but cannot send, taken onto this queue. The other
     * half of {@link #drain}.
     * <p>
     * A frozen page is the last place a record can still hide in this app. Every point taken
     * while his screen was on waits in the page's own outbox for a flush, and a flush only ever
     * happens while the page is awake to run it. So the office's map goes blank for a phone that
     * is working perfectly, and the driver reads a number in brackets when he opens the app
     * again hours later. This queue is the one that keeps working behind his back, and now it
     * can carry the page's share too.
     * <p>
     * The whole lot or none of it, because the page is only allowed to throw its copies away
     * once this queue says it holds them, and a half-taken batch would leave it guessing which
     * half. The rows travel exactly as the page wrote them: each already names the driver, the
     * vehicle and the second it was taken at, and both queues send as the same account, so the
     * guards on the table see the same person whichever way a row came.
     *
     * @return how many rows this queue took, or 0 when it refused them all - no sign-in to send
     *         with, no room for the lot, or a row that is not a position. The page keeps
     *         everything it handed over until one of those says yes.
     */
    int handover(JSONArray in) {
        if (in == null || in.length() == 0) return 0;
        // Rows landed here because the page could not post them, not because anywhere could
        // not: a queue with nothing to ask with would keep them out of sight instead.
        if (!canSend()) return 0;
        List<JSONObject> keep = new ArrayList<>();
        for (int i = 0; i < in.length(); i++) {
            JSONObject o = in.optJSONObject(i);
            if (!isRow(o)) return 0;
            keep.add(o);
        }
        synchronized (rows) {
            // A full queue is a day of driving out of signal. Emptying it of the points this
            // phone measured itself, to make room for records the page would have sent anyway,
            // is the wrong trade - and the page has room for these, so nothing is lost by
            // asking again at the next beat.
            if (rows.length() + keep.size() > MAX_ROWS) return 0;
            for (JSONObject o : keep) push(o);
        }
        trySend(true);
        return keep.size();
    }

    /** Whether a handed row is a position at all - a place, a second, and no room for doubt. */
    private static boolean isRow(JSONObject o) {
        if (o == null || o.optString("recorded_at").isEmpty()) return false;
        return !Double.isNaN(o.optDouble("lat", Double.NaN))
                && !Double.isNaN(o.optDouble("lng", Double.NaN));
    }

    void closeDown() {
        running = false;
        close();
        java.util.concurrent.ExecutorService e = net;
        if (e != null) e.shutdown();
    }

    /**
     * Sign-out: nothing this phone is holding belongs to this driver any more. The page has
     * already emptied the queue into its own out-of-coverage outbox by the time this is
     * called, so what is left here is the order, the vehicle's identity and the shapes.
     */
    void clearDown() {
        running = false;
        close();
        want = false;
        trip = false;
        allowed = false;
        token = "";
        // The sealed sign-in goes with everything else this phone was holding for this driver.
        // Left behind it would let a service started after he handed the seat over - by the
        // order on the disk, or by the handset booting - carry on filing positions in his name.
        forget();
        sp.edit().putBoolean(K_WANT, false).putBoolean(K_TRIP, false).remove(K_PEND).remove(K_ZONE).apply();
        synchronized (rows) {
            empty();
        }
        file.delete();
    }

    boolean isRunning() {
        return running;
    }

    /** Whether a live trip asked for this runner and has not been told to let go of it. */
    boolean isWanted() {
        return want;
    }

    /** Whether the page has a trip open, as opposed to this phone only keeping the office told. */
    boolean isTripping() {
        return trip;
    }

    /** Whether the last fix said the wheels were turning. */
    boolean isMoving() {
        return isMoving;
    }

    /** The zones this runner has the vehicle inside, for a page that wakes and has to agree. */
    JSONArray insideZones() {
        JSONArray a = new JSONArray();
        for (Zone z : zones) {
            if (z.known && z.inside) a.put(z.id);
        }
        return a;
    }

    /** Whether this phone was told to keep reporting and has not been given back. */
    static boolean wanted(Context c) {
        try {
            return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_WANT, false);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- the listening

    /**
     * Ask the phone for positions, if this phone is currently willing to give them.
     *
     * Safe to call again and again: it does nothing once the listeners are in place, and it
     * is called again every fifteen seconds until they are. That matters more than it looks -
     * a driver who switches the handset's location service off for a fuel stop, or a permission
     * Android takes back mid-shift, used to leave this runner holding no listeners at all with
     * no way of getting one back, because the only thing that asked for them had already
     * written itself off as done.
     */
    private void listen() {
        if (!want || (running && !providers.isEmpty())) return;
        if (url.isEmpty() || anon.isEmpty()) {
            why = "this phone has not been told which server to send to";
            return;
        }
        if (!haveLocation()) {
            why = "location is not allowed to this app, so the trip cannot be reported";
            return;
        }
        if (lm == null) lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            why = "this phone will not give the trip a location";
            return;
        }
        boolean fresh = providers.isEmpty();
        providers.clear();
        List<String> all;
        try {
            all = lm.getAllProviders();
        } catch (Exception e) {
            all = new ArrayList<>();
        }
        // GPS for the road, network for a yard with no sky, passive for whatever another app
        // of this phone's is already being told about. Every one of them is asked, and the
        // fixes are judged as one stream: two providers a second apart cost the queue
        // nothing, because the fleet's interval is what decides whether a point is kept.
        for (String p : all) {
            if (p == null) continue;
            if (!p.equals(LocationManager.GPS_PROVIDER) && !p.equals(LocationManager.NETWORK_PROVIDER)
                    && !p.equals(LocationManager.PASSIVE_PROVIDER)) continue;
            try {
                if (lm.isProviderEnabled(p)) add(p);
            } catch (Exception ignore) {
                // a handset that will not name its providers is answered by the ones it
                // lets through, and by none of them if it lets through none
            }
        }
        if (providers.isEmpty()) {
            // The trip is being kept and the phone simply has nowhere to hear a position from.
            // `running` stays false so that the clock below keeps asking, and so the page can
            // see the difference between a runner that is reporting and one that is waiting.
            why = "no location provider on this phone is switched on";
            running = false;
            return;
        }
        if (fresh) first = true;
        running = true;
        why = "";
    }

    private void add(String p) {
        if (providers.contains(p) || lm == null) return;
        try {
            lm.requestLocationUpdates(p, hot() ? SAMPLE_MS : IDLE_MS, hot() ? 0f : IDLE_M,
                    onFix, Looper.getMainLooper());
            providers.add(p);
        } catch (SecurityException e) {
            error = "the trip is not allowed to know where it is: allow location \"all the time\"";
        } catch (Exception e) {
            // a provider that refuses one handset answers the next one
        }
    }

    /** Whether this phone needs its receiver warm: a trip open, or the wheels genuinely turning. */
    private boolean hot() {
        return trip || isMoving;
    }

    /** Take the listeners off without touching the clock that renews them. */
    private void drop() {
        if (lm == null) return;
        try {
            lm.removeUpdates(onFix);
        } catch (Exception ignore) {
            // as in close()
        }
        providers.clear();
    }

    /**
     * Re-ask the handset at the beat its own state now says, and tell the service whether it
     * still needs the CPU. The points the office is given are still cut by the fleet's two
     * intervals - this only decides how long the phone's receiver stays warm and its sleep
     * held off, which is what makes reporting an hourly heartbeat around the clock something
     * a driver's handset can be asked to do. Rate-limited, because a queue at a junction
     * crosses the moving line every few seconds and each re-subscribe costs more than it saves.
     */
    private void beat() {
        long n = System.currentTimeMillis();
        if (running && n - lastBeat > 20000) {
            lastBeat = n;
            drop();
            listen();
        }
        DmsService s = svc;
        if (s != null) s.reporting();
    }

    private void close() {
        main.removeCallbacks(tick);
        scheduled = false;
        if (lm != null) {
            try {
                lm.removeUpdates(onFix);
            } catch (Exception ignore) {
                // a listener the phone already took back has nothing left to stop
            }
        }
        providers.clear();
    }

    private boolean haveLocation() {
        return granted(Manifest.permission.ACCESS_FINE_LOCATION)
                || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private boolean granted(String permission) {
        try {
            return ContextCompat.checkSelfPermission(ctx, permission)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    private final LocationListener onFix = new LocationListener() {
        @Override
        public void onLocationChanged(Location loc) {
            fix(loc);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) { }

        @Override
        public void onProviderEnabled(String provider) {
            // a driver who turns the handset's location on mid-shift gets watched again
            // on that provider without waiting for the page to speak
            if (running) add(provider);
        }

        @Override
        public void onProviderDisabled(String provider) { }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!want || (svc != null && svc.isDead())) {
                scheduled = false;
                return;
            }
            // The trip's own clock, and the only reason a runner can come back by itself:
            // it asks the phone for positions again until the phone gives it any, and it
            // empties whatever the dead spot filled. Both halves have to happen on a timer
            // that does not depend on a position arriving, because a runner that is not
            // listening is exactly the runner that never gets to do the second half.
            listen();
            trySend();
            main.postDelayed(this, TRY_MS);
        }
    };

    /** One repeating clock per trip, whatever number of things ask for it. */
    private void schedule() {
        if (scheduled) return;
        scheduled = true;
        main.postDelayed(tick, TRY_MS);
    }

    /** Bring the runner up for the trip this phone is holding, and keep it running. */
    private void resume() {
        listen();
        schedule();
        trySend();
    }

    // ---- one fix, and what the fleet's intervals make of it

    private void fix(Location loc) {
        if (!running || (svc != null && svc.isDead())) return;
        double lat = loc.getLatitude(), lng = loc.getLongitude();
        long at = loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis();
        // a receiver that has not locked anything says 0,0, and a trip drawn through the
        // Gulf of Guinea is worse than one with a gap in it
        if (!(lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180) || (lat == 0 && lng == 0)) return;
        double kmh = loc.hasSpeed() ? Math.max(0, loc.getSpeed() * 3.6) : 0;
        double acc = loc.hasAccuracy() ? loc.getAccuracy() : Double.NaN;
        double hd = loc.hasBearing() ? loc.getBearing() : Double.NaN;
        lastFixAt = SystemClock.elapsedRealtime();
        // Three readings on one side of the line before this says the vehicle started, or
        // stopped, being driven. A handset left on a seat reads a satellite drift of 4 km/h
        // now and then, and a runner that believed each one of them would report at the
        // driving interval all day, hold the phone's CPU awake for a vehicle that had not
        // moved, and - the part an office would notice - cut a trip out of the drift.
        if (kmh >= MOVING_KMH) {
            hotStreak++;
            coldStreak = 0;
        } else {
            coldStreak++;
            hotStreak = 0;
        }
        boolean was = isMoving;
        if (!isMoving && hotStreak >= 3) isMoving = true;
        else if (isMoving && coldStreak >= 3) isMoving = false;
        if (isMoving != was) beat();
        // The standing clock the page is handed: started by the first fix this runner judges
        // as still, and wiped by any fix that says the wheels are turning again. It is the
        // service's own, so it keeps counting through a locked screen and a frozen WebView -
        // which is the only reason a 40 minute drive with the screen off cannot be read as
        // 40 minutes parked and ended on the fix after the driver unlocks his phone.
        if (isMoving) stoodAt = 0;
        else if (stoodAt == 0) stoodAt = SystemClock.elapsedRealtime();

        // The acceleration the positions row carries. The full app reads the same two samples
        // to decide whether the driver braked or launched hard enough to be warned about; this
        // build warns about nothing, but the office still gets the same column it gets from a
        // page that is awake, and the tacho report is cut from it.
        double v = kmh / 3.6, t = at / 1000.0;
        if (havePrev && t - prevT >= 0.8 && t - prevT <= 4) lastA = (v - prevV) / (t - prevT);
        if (!havePrev || t - prevT >= 0.8) {
            prevV = v;
            prevT = t;
            havePrev = true;
        }
        zonesCheck(loc);

        JSONObject p = row(loc, at, kmh, acc, isMoving);
        if (p == null) return;
        latest = p;
        latestAt = at;

        double every = (isMoving ? movingS : parkedS) * 1000.0;
        long n = System.currentTimeMillis();
        double turn = Double.isNaN(hd) || Double.isNaN(heading) ? 0
                : Math.min(Math.abs(hd - heading), 360 - Math.abs(hd - heading));
        boolean corner = isMoving && turn >= TURN_DEG && n - lastSent > 1200;
        if (!first && n - lastSent < every - 500 && !corner) return;
        first = false;
        lastSent = n;
        if (!Double.isNaN(hd)) heading = hd;

        synchronized (rows) {
            push(p);
        }
        trySend();
    }

    /**
     * The positions row, in the columns the table has, in the shape the page writes them.
     * <p>
     * Every row carries every key, and that is a wire rule rather than tidiness: these rows go
     * out as one JSON array in one request, and the server refuses an array whose objects do not
     * all name the same columns - "All object keys must match" - refusing the whole of it, not
     * the odd row. A fix that happened to come without an altitude, a bearing the receiver had
     * not settled on, or a minute in which the battery was not asked, therefore used to take two
     * hundred positions with it, and a trip's worth of points went out of this phone in batches
     * of two hundred. A JSON null is what puts the column empty instead, which is what the page
     * already sends; every column here is nullable except the user, the place and the time, and
     * this has all three of those before it builds anything.
     */
    private JSONObject row(Location loc, long at, double kmh, double acc, boolean moving) {
        if (uid.isEmpty() || vid.isEmpty()) {
            why = "this phone has not been told which vehicle it is driving";
            return null;
        }
        JSONObject p = new JSONObject();
        try {
            p.put("user_id", uid);
            p.put("vehicle_id", vid);
            p.put("driver_id", did.isEmpty() ? JSONObject.NULL : did);
            // The two names are rewritten by the database as the row lands, so an empty one here
            // costs nothing - but leaving its key out costs the batch it sits in.
            p.put("vehicle_name", vname.isEmpty() ? JSONObject.NULL : vname);
            p.put("driver_name", dname.isEmpty() ? JSONObject.NULL : dname);
            p.put("lat", loc.getLatitude());
            p.put("lng", loc.getLongitude());
            // a standing vehicle reports zero, as the page does: an idling handset that
            // keeps saying 4 km/h is how a trip reads as a slow crawl across a depot
            p.put("speed", moving ? (double) Math.round(kmh) : 0);
            p.put("recorded_at", iso(at));
            // The column the office's trip list is cut from. A point taken while a trip is open
            // says the vehicle is being driven even when it is standing at a light; a rest
            // heartbeat outside a trip says neither, so it reaches the map and the live list
            // without becoming a trip of its own.
            p.put("ignition", trip || moving);
            p.put("accuracy", Double.isNaN(acc) ? JSONObject.NULL : round1(acc));
            p.put("altitude", loc.hasAltitude() ? round1(loc.getAltitude()) : JSONObject.NULL);
            p.put("heading", loc.hasBearing() ? (double) Math.round(loc.getBearing()) : JSONObject.NULL);
            p.put("accel", round1(lastA));
            double[] b = svc == null ? null : svc.battery();
            p.put("battery_pct", b == null ? JSONObject.NULL : Math.round(b[0] * 10) / 10.0);
            p.put("charging", b == null ? JSONObject.NULL : b[1] > 0);
        } catch (Exception e) {
            return null;
        }
        return p;
    }

    // ---- the zones the office drew

    /**
     * One geofence: the ring of corners it was drawn with, and the three switches the
     * dashboard's Geofences panel has for it. `known` is false until the first fix has said
     * which side of the boundary this phone is on, because nothing can report a crossing that
     * happened while the runner had no shape to test against.
     */
    private static final class Zone {
        String id = "", name = "";
        double limit;
        boolean enter = true, exit = true, over = true;
        double[] la = new double[0], ln = new double[0];
        boolean inside, known;
    }

    /**
     * The shapes as this phone kept them. A file this cannot read is no answer at all rather
     * than an empty fleet: {@link #readZones} keeps what it already holds for a null, and the
     * last thing a service Android restarted should do is cross every zone in silence because
     * one byte of its own storage tore.
     */
    private static JSONArray kept(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Replace the shapes the fixes are tested against.
     *
     * No array at all is not an empty fleet: the page's own settings read can fail to answer,
     * and a runner that took that to mean "this fleet has no zones" would go on crossing
     * every one of them in silence. A zone whose corners are unchanged keeps the state it had,
     * so the five-minute re-read of the same setup cannot lose a crossing in progress; a
     * redrawn one loses its baseline, which is the honest answer about a boundary that moved.
     */
    private void readZones(JSONArray a) {
        if (a == null) return;
        Map<String, Zone> had = new HashMap<>();
        for (Zone z : zones) had.put(z.id, z);
        List<Zone> next = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Zone z = new Zone();
            z.id = j.optString("id", "");
            if (z.id.isEmpty()) continue;
            z.name = j.optString("name", "");
            double lim = j.optDouble("limit", 0);
            z.limit = usable(lim) && lim > 0 ? lim : 0;
            z.enter = j.optBoolean("enter", true);
            z.exit = j.optBoolean("exit", true);
            z.over = j.optBoolean("overspeed", true);
            JSONArray pts = j.optJSONArray("pts");
            if (pts == null || pts.length() < 3) continue;
            int n = 0;
            for (int k = 0; k < pts.length(); k++) {
                JSONArray p = pts.optJSONArray(k);
                if (p == null || p.length() < 2) continue;
                double x = p.optDouble(0, Double.NaN), y = p.optDouble(1, Double.NaN);
                if (!usable(x) || !usable(y)) continue;
                n++;
            }
            if (n < 3) continue;
            z.la = new double[n];
            z.ln = new double[n];
            int at = 0;
            for (int k = 0; k < pts.length(); k++) {
                JSONArray p = pts.optJSONArray(k);
                if (p == null || p.length() < 2) continue;
                double x = p.optDouble(0, Double.NaN), y = p.optDouble(1, Double.NaN);
                if (!usable(x) || !usable(y)) continue;
                z.la[at] = x;
                z.ln[at] = y;
                at++;
            }
            Zone old = had.get(z.id);
            if (old != null && !moved(old, pts)) {
                z.inside = old.inside;
                z.known = old.known;
            }
            next.add(z);
        }
        zones.clear();
        zones.addAll(next);
    }

    private static boolean usable(double x) {
        return !Double.isNaN(x) && !Double.isInfinite(x);
    }

    /** The corners are not the ones this runner was holding, so its state cannot carry over. */
    private static boolean moved(Zone z, JSONArray pts) {
        if (z.la.length != pts.length()) return true;
        for (int i = 0; i < pts.length(); i++) {
            JSONArray p = pts.optJSONArray(i);
            if (p == null || p.length() < 2) return true;
            if (p.optDouble(0, Double.NaN) != z.la[i] || p.optDouble(1, Double.NaN) != z.ln[i]) return true;
        }
        return false;
    }

    /**
     * Which side of every shape the office drew this fix leaves the vehicle on.
     *
     * Nothing is filed from it: this build writes no events. It is kept because the page draws
     * the same shapes over the driver's map and answers "you are inside the depot" from the
     * same test, and that answer has to come from one place whether or not the screen is on -
     * which is why the inside-set travels back in the status for a page that wakes, exactly as
     * it does in the full app.
     */
    private void zonesCheck(Location loc) {
        if (zones.isEmpty()) return;
        double lat = loc.getLatitude(), lng = loc.getLongitude();
        for (Zone z : zones) {
            z.inside = inside(z, lat, lng);
            z.known = true;
        }
    }

    /** The page's own point-in-polygon test, on the same shape: a crossing must not read
     *  differently in the two hands it can be tested by. */
    private static boolean inside(Zone z, double lat, double lng) {
        boolean c = false;
        for (int i = 0, j = z.la.length - 1; i < z.la.length; j = i++) {
            double ai = z.la[i], aj = z.la[j], bi = z.ln[i], bj = z.ln[j];
            if ((bi > lng) != (bj > lng) && lat < (aj - ai) * (lng - bi) / (bj - bi) + ai) c = !c;
        }
        return c;
    }

    // ---- the queue on disk, and the server

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0 && out.size() < 64 * 1024 * 1024) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * A server's whole answer, however long. The short capped reader the positions call uses is
     * right for a refusal message and wrong here: a sign-in cut in half reads as nonsense, and
     * nonsense thrown away is a refresh token this phone had and will never get back.
     */
    private static String readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) > 0) out.write(b, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    private void load() {
        if (!file.isFile()) return;
        try {
            String text = new String(readAll(file), "UTF-8");
            boolean whole = text.trim().startsWith("[");
            JSONArray a = parse(text);
            synchronized (rows) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) rows.put(o);
                }
                // A queue an older build left behind is one JSON array, and a new point cannot
                // be added to the end of that without writing the whole of it again. It is put
                // into this build's own shape here, once, on the way in.
                if (whole) save();
            }
        } catch (Throwable t) {
            // a queue this cannot read is not worth trying to rescue: the rows in it were
            // never sent, and the trip has to be able to carry on
            file.delete();
        }
    }

    /**
     * The rows out of either shape this file has ever had. Line by line is what lets a point
     * be added without rewriting a trip that is already held: a phone switched off in the
     * middle of writing loses the one line it was writing and keeps every fix before it,
     * where the whole-array file this replaced lost the entire trip over a torn last byte.
     */
    private static JSONArray parse(String text) throws JSONException {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("[")) return new JSONArray(t);
        JSONArray a = new JSONArray();
        for (String line : t.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            try {
                a.put(new JSONObject(s));
            } catch (Exception ignore) {
                Logger.error("fleet-dms: a queued position would not read", ignore);
            }
        }
        return a;
    }

    /** Called with the queue already locked: Android's JSONArray only throws on a null row. */
    private void requeue(List<JSONObject> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            try {
                rows.put(0, batch.get(i));
            } catch (Exception e) {
                Logger.error("fleet-dms: a position would not go back in the queue", e);
            }
        }
    }

    /** Called with the queue already locked. Android's own JSONArray has no range delete. */
    private void empty() {
        while (rows.length() > 0) rows.remove(0);
    }

    /** Called with the queue already locked: the whole trip's unsent rows, one per line. */
    private void save() {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o != null) b.append(o.toString()).append('\n');
            }
            try (OutputStream out = new FileOutputStream(file)) {
                out.write(b.toString().getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            error = "the phone would not keep the trip's positions on disk";
        }
    }

    /**
     * Called with the queue already locked: one fix onto the end of the file. This is what a
     * trip does every interval, so it is the write that had to stop costing the whole queue -
     * an hour parked out of signal is a few hundred rows, and a day of a dead spot is the
     * cap of them, each of which used to be serialized and put back on the storage chip
     * again every time the next one arrived.
     */
    private void append(JSONObject o) {
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(file, true)) {
                out.write((o.toString() + "\n").getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            error = "the phone would not keep the trip's positions on disk";
        }
    }

    /**
     * Called with the queue already locked: keep the point, and keep it on the disk in
     * whichever way is cheap for the file as it stands. Only the cap rewrites everything, and
     * a trip only reaches the cap after a day of driving out of signal.
     */
    private void push(JSONObject o) {
        rows.put(o);
        if (rows.length() > MAX_ROWS) {
            while (rows.length() > MAX_ROWS) rows.remove(0);
            save();
        } else {
            append(o);
        }
    }

    private void trySend() {
        trySend(false);
    }

    /**
     * Whether this queue has a way to reach the office at all: its address, its key, and a
     * sign-in - the one this phone keeps or one a page has lent. {@link #trySend} asks before it
     * wakes the network thread, and {@link #handover} asks before it takes records off another
     * queue, because rows that land where nothing can post them are only lost more slowly.
     */
    private boolean canSend() {
        return !url.isEmpty() && !anon.isEmpty() && (!token.isEmpty() || !own.isEmpty());
    }

    /** `urgent` is a point the office should see now, so the two-second wait is skipped. */
    private void trySend(boolean urgent) {
        // Whether this phone has any sign-in at all is answered here. Whether the one in hand
        // still works is answered on the network thread, where it can be started again, because
        // that is the only place a request can be made. The gate used to be "a page has lent a
        // token lately", which is an hour of an answer at best: the lending ran out while the
        // driver was still driving, the frozen page could not lend a new one, and the rows piled
        // up on the handset until he opened the app again.
        if (!canSend()) return;
        long n = System.currentTimeMillis();
        if (!urgent && n - lastTry < 2000) return;
        lastTry = n;
        synchronized (rows) {
            if (rows.length() == 0) return;
        }
        java.util.concurrent.ExecutorService e = net;
        if (e == null) {
            synchronized (this) {
                if (net == null) net = java.util.concurrent.Executors.newSingleThreadExecutor();
            }
            e = net;
        }
        try {
            e.execute(this::send);
        } catch (Throwable ignore) {
            // a pool that will not take the chore means the trip is ending; the rows are
            // on disk and the page drains them
        }
    }

    private void send() {
        String tk = ready();
        if (tk.isEmpty()) return;
        // A trip that ran offline for a day is sixty thousand rows, and this must not hold
        // the queue while an unbounded loop tries them all: one run empties what it can and
        // the next turn of the clock takes the rest.
        int ran = 0;
        for (int turn = 0; turn < 200; turn++) {
            List<JSONObject> batch = new ArrayList<>();
            synchronized (rows) {
                for (int i = 0; i < BATCH && rows.length() > 0; i++) {
                    JSONObject o = rows.optJSONObject(0);
                    rows.remove(0);
                    if (o != null) batch.add(o);
                }
                if (batch.isEmpty()) {
                    save();
                    return;
                }
            }
            int r = tryBatch(batch, tk);
            // KEPT means the rows are back on the disk and there is no sense asking again this
            // turn: the server never heard the request. RANOUT means the same, and that the
            // reason is the sign-in this phone sends with - which is worth one restart and one
            // second try, because the rows at the front of the queue are exactly the ones that
            // were just put back. Anything past that is a server that will not have this
            // driver's rows today, and the page is the only thing that can ask it again.
            if (r == RANOUT) {
                if (++ran > 1 || !renew(true)) {
                    allowed = false;
                    error = refresh.isEmpty()
                            ? "the phone's sign-in has run out: open the app to send what is waiting"
                            : "this phone could not start its own sign-in again: open the app once and everything waiting goes";
                    return;
                }
                tk = token;
                continue;
            }
            // DROPPED means one row of this batch went away and the rest of the queue is
            // untouched, so the trip carries on reporting from the second it stopped being
            // thrown away for.
            if (r == KEPT) return;
            if (r == LANDED && !error.isEmpty()) error = "";
        }
    }

    /** What one try at a batch of positions came back as. */
    private static final int LANDED = 0, KEPT = 1, DROPPED = 2;
    /** The rows are back on the queue and the refusal was about who was asking: {@link #send}
     *  gets one chance to start this phone's own sign-in again and take the same piece a second
     *  time, and that is the difference between a locked screen costing one trip and costing
     *  the whole shift. */
    private static final int RANOUT = 3;

    /**
     * How many single rows in a row this server has refused before the runner gives up on it.
     * Ten identical answers is not a bad point in a trip, it is a database that will not take
     * anything from this phone - a policy, a column the schema has not added, a server address
     * belonging to another project - and the queue is worth more kept than asked about again.
     */
    private static final int MAX_DROP = 10;

    private int dropRun = 0;
    private String dropKey = "";

    /**
     * One batch, and when the server answers no, its two halves, and their halves, down to the
     * single row that was the problem.
     * <p>
     * The descent is the only way to find the row: the answer comes back for the array as a
     * whole and never names what it refused, and before this the whole batch was thrown away on
     * the strength of it - one position the database would not take cost the driver the two
     * hundred that came with it, which is a trip's worth of points, and every trip, tacho second
     * and kilometre the office counts from them, gone in pieces of two hundred.
     * <p>
     * Two answers stop it and give the rows back rather than drop them: a request this phone
     * never heard, which goes back on the disk in the order the road was driven, and a sign-in
     * that has run out, which goes back the same way and is then asked about again by
     * {@link #send} with a fresh hour of this phone's own sign-in.
     *
     * @return LANDED when the server took every row, KEPT when they are back on the queue and
     * nothing more should be tried this turn, DROPPED when a row was refused and thrown away,
     * RANOUT when they are back on the queue because the sign-in sent for an hour that is over.
     */
    private int tryBatch(List<JSONObject> batch, String tk) {
        JSONArray body = new JSONArray();
        for (JSONObject o : batch) body.put(o);
        String refused = post(body.toString(), tk);
        if (refused == null) {
            // never reached the server: put the batch back in front of the queue and
            // leave it for the next turn, in the order the road was driven
            synchronized (rows) {
                requeue(batch);
                save();
            }
            return KEPT;
        }
        if (refused.isEmpty()) {
            synchronized (rows) {
                sent += batch.size();
                sp.edit().putLong(K_SENT, sent).apply();
                save();
            }
            dropRun = 0;
            return LANDED;
        }
        if (refused.startsWith("TOKEN")) {
            // Every row of this batch goes back on the disk, because a sign-in is the one
            // refusal that says nothing whatever about the rows themselves: they are the
            // driver's route, and an hour that ran out cannot make them wrong.
            synchronized (rows) {
                requeue(batch);
                save();
            }
            return RANOUT;
        }
        if (batch.size() > 1) {
            int h = (batch.size() + 1) / 2;
            List<JSONObject> left = batch.subList(0, h), right = batch.subList(h, batch.size());
            int a = tryBatch(left, tk);
            // The half this turn never asked about is not thrown away with the answer to the
            // other one. It has reached no server at all, and answering early used to drop it
            // with the batch it came out of. Putting it back behind its twin reverses the order
            // of two neighbouring halves, which costs nothing: every row carries the second it
            // was taken at, and losing one of them costs a stretch of road nobody can redraw.
            if (a == KEPT || a == RANOUT) {
                synchronized (rows) {
                    requeue(right);
                    save();
                }
                return a;
            }
            int b = tryBatch(right, tk);
            if (b == KEPT || b == RANOUT) return b;
            return a == DROPPED || b == DROPPED ? DROPPED : LANDED;
        }
        // A single row the server answered no to is refused for a reason that will not change
        // by asking again, so it is the one thing here worth throwing away - out loud, and with
        // the server's own words, because the office is who has to put it right.
        dropRun = refused.equals(dropKey) ? dropRun + 1 : 1;
        dropKey = refused;
        if (dropRun > MAX_DROP) {
            synchronized (rows) {
                requeue(batch);
                save();
            }
            error = "the server refuses every position from this phone: " + refused +
                    " - tell your office, nothing more is thrown away";
            Logger.error("fleet-dms: every position refused: " + refused);
            return KEPT;
        }
        synchronized (rows) {
            save();
        }
        error = "the server refused a position and it was dropped: " + refused;
        Logger.error("fleet-dms: position refused: " + refused);
        return DROPPED;
    }

    /** "" when the server took it, a message when it refused, null when it never heard. */
    private String post(String body, String tk) {
        // The last gate before the network. Answering "never heard" rather than "refused" is
        // deliberate: the batch goes back on the disk instead of being thrown away, so a trip
        // is not lost over an address this runner will not speak to.
        if (!hostOk(url)) {
            why = "no trip has been sent from this phone: the server address is not a Supabase project";
            Logger.error("fleet-dms: refusing to post to a non-Supabase host");
            return null;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url + "/rest/v1/positions").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("apikey", anon);
            c.setRequestProperty("Authorization", "Bearer " + tk);
            c.setRequestProperty("Content-Type", "application/json");
            // the rows are the whole answer this trip needs; the server does not have to
            // send them back to us and cost the phone a second transfer of every one
            c.setRequestProperty("Prefer", "return=minimal");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code == 201 || code == 200 || code == 204) return "";
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 401 || code == 403) return "TOKEN";
            String m = "";
            try {
                m = new JSONObject(text).optString("message", "");
            } catch (Exception ignore) {
                // a refusal that is not JSON is still a refusal, and the code says it
            }
            return m.isEmpty() ? "code " + code : m;
        } catch (Throwable t) {
            // a request that never reached the server has told this phone nothing at all,
            // so nothing here is allowed to conclude that its rows were refused
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = i.read(b)) > 0 && out.size() < 8192) out.write(b, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // ---- what the page is told

    /**
     * The shapes this runner tests, written back the way the page sent them, so a service
     * Android had to bring back crosses the fleet's zones from its first fix instead of
     * waiting for a page that may not wake for hours. Which side of each boundary the vehicle
     * was last found on is deliberately not in here: after a restart the first fix only sets
     * the baseline, and a crossing nobody measured is not one to report.
     */
    private String zonesJson() {
        JSONArray a = new JSONArray();
        for (Zone z : zones) {
            JSONObject j = new JSONObject();
            try {
                JSONArray pts = new JSONArray();
                for (int i = 0; i < z.la.length; i++) {
                    pts.put(new JSONArray().put(z.la[i]).put(z.ln[i]));
                }
                j.put("id", z.id).put("name", z.name).put("limit", z.limit)
                        .put("enter", z.enter).put("exit", z.exit).put("overspeed", z.over)
                        .put("pts", pts);
            } catch (Exception ignore) {
                // JSONObject and JSONArray only throw on a null key or a NaN, and neither of
                // these can be either: the id is required and the corners are screened twice
            }
            a.put(j);
        }
        return a.toString();
    }

    /** The start order written by a page that had no service to give it to. */
    private void take(String raw) {
        if (raw == null || raw.isEmpty()) return;
        try {
            startFrom(new JSONObject(raw));
        } catch (Exception ignore) {
            // a pending order this cannot read is an order the page will send again
        }
    }

    private void startFrom(JSONObject j) {
        // the same gate as an order that arrives from the page: this one was read off the
        // phone's own storage, and neither route may leave this trip for an address that is
        // not the fleet's project
        if (j.has("url")) {
            String u = j.optString("url", "");
            if (hostOk(u)) url = u;
            else why = "the server address kept on this phone is not a Supabase project, so nothing has been sent to it";
        }
        if (j.has("anon")) anon = j.optString("anon", anon);
        movingS = clamp(j.optInt("movingS", movingS), 1, 3600);
        parkedS = clamp(j.optInt("parkedS", parkedS), 5, 43200);
        JSONObject ids = j.optJSONObject("ids");
        if (ids != null) {
            uid = ids.optString("user_id", uid);
            vid = ids.optString("vehicle_id", vid);
            did = ids.optString("driver_id", did);
            vname = ids.optString("vehicle_name", vname);
            dname = ids.optString("driver_name", dname);
        }
        if (j.has("trip")) trip = j.optBoolean("trip", trip);
        readZones(j.optJSONArray("zones"));
    }

    /** The page writes this order when the service is not up yet; see {@link #take}. */
    static void holdOrder(Context c, JSONObject j) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(K_PEND, j == null ? "" : j.toString())
                    .putBoolean(K_WANT, true).apply();
        } catch (Exception ignore) {
            // an order the phone would not keep is re-sent by the page a moment later
        }
    }

    static void dropOrder(Context c) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .remove(K_PEND).putBoolean(K_WANT, false).apply();
        } catch (Exception ignore) {
            // as above
        }
    }

    JSONObject status() {
        JSONObject j = new JSONObject();
        try {
            int q;
            synchronized (rows) {
                q = rows.length();
            }
            j.put("running", running).put("wanted", want).put("queued", q).put("sent", sent)
                    .put("movingS", movingS).put("parkedS", parkedS)
                    .put("trip", trip).put("moving", isMoving)
                    // How long this service has judged the vehicle stationary, in
                    // milliseconds, or -1 while no fix has been judged at all. The page's own
                    // trip end is measured against this rather than against its frozen
                    // WebView clock, so a drive taken with the screen off cannot be read as
                    // a park. See {@link #stoodAt}.
                    .put("stood", stoodAt == 0 ? -1 : SystemClock.elapsedRealtime() - stoodAt)
                    .put("listening", providers.size())
                    .put("sending", allowed && (!token.isEmpty() || !own.isEmpty()))
                    // Whether this phone can send with the app shut: the sign-in minted at the
                    // driver's own sign-in is still sealed here and still worth asking. The page
                    // shows nothing for it - it is what tells a support call apart between "this
                    // handset uploads on its own" and "this one waits for you to open it".
                    .put("keeps", !refresh.isEmpty())
                    .put("renews", ownUntil > 0 ? (ownUntil - System.currentTimeMillis()) / 1000 : -1)
                    .put("sinceFix", lastFixAt == 0 ? -1
                            : (SystemClock.elapsedRealtime() - lastFixAt) / 1000)
                    .put("zones", insideZones())
                    .put("error", error).put("why", why);
        } catch (Exception ignore) {
            // JSONObject only throws on a null key, and none of these are null
        }
        return j;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    /** ISO 8601 in UTC, the format the positions column expects and the page writes. */
    private static String iso(long ms) {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return f.format(new java.util.Date(ms));
    }
}
