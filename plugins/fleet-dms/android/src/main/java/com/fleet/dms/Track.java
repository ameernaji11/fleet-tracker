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
 * So this runs in the service that already survives that: the same one that reads a
 * driver's face behind a locked screen, holds the wake lock, is restarted by Android when
 * it has been killed, and keeps its own queue on disk. It listens to the handset's own
 * location providers, applies the fleet's two recording intervals exactly as the page
 * applies them, keeps the corner points and the last point of a trip, and puts the rows
 * where the dashboard reads them.
 *
 * It uploads with the driver's own access token, handed over by the page when the trip
 * begins and refreshed whenever the page can see itself. That is not a second way into the
 * database: it is the same sign-in in a different hand, and every guard on the positions
 * table still applies to it, so this cannot file a position for a vehicle its driver does
 * not own any more than the page can. A token is never written to disk, and a runner that
 * Android had to start on its own therefore collects without sending: the rows go to the
 * same file, the page drains what a refused or expired sign-in left there, and nothing is
 * dropped for a reason the phone could have waited out.
 *
 * The page stays the writer of events while the driver is looking at it, and this takes over
 * the ones it cannot see: a zone the vehicle crossed behind a locked screen, and the three
 * vehicle warnings a driver would otherwise not hear until he unlocks the phone. Said out
 * loud only, and only while the page is not there to say them itself. Every one of them is
 * handed back the moment the page wakes - see {@link #openRows} and {@link #insideZones} - so
 * two writers of one corner never leave a trip with two overspeed records for one bend.
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
    /** A fix worse than this says nothing about a driver's speed, as in the page. */
    private static final double MAX_ACC = 25;
    /** The same corner the page adds a point of its own for: two sampled seconds of a bend
     *  would otherwise be drawn as one straight cut across it. */
    private static final double TURN_DEG = 30;
    /** The speed the page measures "moving" against, and the number the automatic trip end
     *  is argued from: below it the vehicle idles and reports on the parked interval. */
    private static final double MOVING_KMH = 2;

    private static final String PREFS = "fleet.track";
    private static final String K_SENT = "sent", K_MOV = "moving", K_PARK = "parked",
            K_URL = "url", K_ANON = "anon", K_SET = "settings", K_WANT = "want", K_PEND = "pending",
            K_TRIP = "trip", K_ZONE = "zones";

    /** The rules this may speak about: the vehicle's own three, and the zone's three. */
    private static final List<String> RULES = new ArrayList<>();
    static {
        RULES.add("overspeed");
        RULES.add("harsh_acceleration");
        RULES.add("harsh_braking");
        RULES.add("zone_overspeed");
        RULES.add("zone_enter");
        RULES.add("zone_exit");
    }

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
     * this service is the one that carries the driver's live sign-in in memory and posts a
     * whole trip with it - so the host is checked here as well, at the moment it arrives, at
     * the moment it is read back off the disk after a restart, and again at the connection.
     * A page that had been tricked into naming somebody else's host would otherwise hand
     * over both the route and the key that writes it, and a bad host typed into the config
     * would keep being used by a service that outlives the page that made the mistake.
     */
    private static final java.util.regex.Pattern HOST =
            java.util.regex.Pattern.compile("^https://[a-z0-9]{15,30}\\.supabase\\.co$");

    private static boolean hostOk(String u) { return u != null && HOST.matcher(u).matches(); }

    private volatile String url = "", anon = "", token = "";
    private volatile String uid = "", vid = "", did = "", vname = "", dname = "";
    private volatile int movingS = 10, parkedS = 3600;
    private volatile double speedLimit, buf, accelLimit, brakeLimit;
    private final Map<String, Config.Rule> rules = new HashMap<>();
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
    /** The last moment the beat was changed, so a vehicle crawling in a queue cannot re-tune
     *  the handset's receiver every second. */
    private long lastBeat;
    /** Whether {@link #tick} is posted right now. Never more than one clock per trip. */
    private volatile boolean scheduled;
    // True only while a page in this process has handed over a token. Android can restart
    // this runner mid-shift, and the sign-in it would have to send with is not on disk - so
    // a restarted runner collects, and the page sends or re-hands the token over.
    private volatile boolean allowed;
    private volatile boolean want;
    private long lastSent, lastTry, lastFixAt, sent;
    private double heading = Double.NaN, prevV, prevT, lastA;
    private boolean first = true, havePrev;
    // The newest point this has measured but not yet put in the queue's send order, kept so
    // that an alert the page raises can have its own position on the map immediately
    // instead of waiting out the fleet's recording interval.
    private volatile JSONObject latest;
    private volatile long latestAt;
    // When the point above last went into the queue, so an alert asked to send it cannot
    // write the same second twice.
    private long lastQueued;
    // The last time each warning was sounded, and whether it was sounding a moment ago.
    // Handed back in the status so a page that wakes does not buzz the driver again for a
    // breach he has already been warned about with his screen off.
    private final Map<String, Long> sounded = new HashMap<>();
    private final Map<String, Boolean> bad = new HashMap<>();
    // The vehicle-rule breaches opened on this runner's own fixes, kept until they end or
    // until the page that has just woken takes them over.
    private final Map<String, Breach> open = new HashMap<>();
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
        readSettings(sp.getString(K_SET, null));
        readZones(kept(sp.getString(K_ZONE, null)));
        take(sp.getString(K_PEND, null));
        sp.edit().remove(K_PEND).apply();
        load();
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
        speedLimit = j.optDouble("speedLimit", speedLimit);
        buf = j.optDouble("buffer", buf);
        accelLimit = j.optDouble("accelLimit", accelLimit);
        brakeLimit = j.optDouble("brakeLimit", brakeLimit);
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
        JSONArray rs = j.optJSONArray("rules");
        if (rs != null) {
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.optJSONObject(i);
                if (r == null) continue;
                String t = r.optString("type", "");
                if (RULES.contains(t)) rules.put(t, Config.Rule.of(t, r));
            }
        }
        // as with the page's own copy of the shapes: no array is not an empty fleet
        readZones(j.optJSONArray("zones"));
        sp.edit().putInt(K_MOV, movingS).putInt(K_PARK, parkedS)
                .putString(K_URL, url).putString(K_ANON, anon)
                .putString(K_SET, rulesJson()).putString(K_ZONE, zonesJson())
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
     * can see that happen. Handing it over here is also the only thing that lets this send,
     * so a runner Android started by itself starts uploading again the moment the page can
     * talk.
     */
    void token(String t) {
        if (t == null || t.isEmpty()) return;
        token = t;
        allowed = true;
        if (!error.isEmpty()) error = "";
        trySend();
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
                last.put("recorded_at", Outbox.iso(System.currentTimeMillis()));
                synchronized (rows) {
                    push(last);
                }
            } catch (Exception ignore) {
                // a trip that cannot write its own last point still ends
            }
        }
        // A breach the driver was still inside when he ended the trip gets its end time here,
        // exactly as the page closes the breaches it opened itself. An event with no end reads
        // as a breach still happening while the driver is at home.
        closeOpen(System.currentTimeMillis());
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
     * An alert the page raised: the position it happened at goes now, not at the end of the
     * fleet's interval, because the live map is the one thing an office reads while a
     * vehicle is still moving. The point is only ever queued once, so this cannot leave two
     * rows for the same second.
     */
    void now() {
        JSONObject last = latest;
        long at = latestAt;
        if (last == null || at == 0 || at == lastQueued) return;
        lastQueued = at;
        synchronized (rows) {
            push(last);
        }
        allowed = !token.isEmpty();
        trySend(true);
    }

    void closeDown() {
        running = false;
        close();
        java.util.concurrent.ExecutorService e = net;
        if (e != null) e.shutdown();
    }

    /**
     * Sign-out: nothing this phone is holding belongs to this driver any more. The page has
     * already emptied the queue into its own outbox by the time this is called, which is the
     * order the app's sign-out uses for the camera's events too.
     */
    void clearDown() {
        running = false;
        close();
        want = false;
        trip = false;
        allowed = false;
        token = "";
        open.clear();
        bad.clear();
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

        // The camera judges frames with the screen off and the page asleep, and it can only
        // say where a breach happened, or how fast the vehicle was going, from the reading it
        // was last handed. Until now that was the page's own feed, which stops exactly when
        // the page does, so every warning raised behind the driver's back carried a place a
        // minute or an hour old and a speed of zero - which is a rule that never arms.
        // This is the same handset's same receiver, offered on every fix: the call is a
        // method in this process, not a trip over the bridge, and the page's own readings
        // still win while it is awake because they arrive between these ones.
        DmsService sv = svc;
        if (sv != null) {
            sv.feed(lat, lng, acc, loc.hasAltitude() ? loc.getAltitude() : Double.NaN, hd, kmh, at);
        }

        // the speed the driver is warned about, and the acceleration that ends a trip, come
        // from the same seconds the row is written from, so they are read here rather than
        // left to a page that may be asleep
        double v = kmh / 3.6, t = at / 1000.0;
        if (havePrev && t - prevT >= 0.8 && t - prevT <= 4) {
            double a = (v - prevV) / (t - prevT);
            watch("harsh_braking", a <= brakeLimit && brakeLimit < 0, a * 3.6, loc, at);
            watch("harsh_acceleration", a >= accelLimit && accelLimit > 0, a * 3.6, loc, at);
        }
        if (!havePrev || t - prevT >= 0.8) {
            prevV = v;
            prevT = t;
            havePrev = true;
        }
        double lim = speedLimit + buf;
        watch("overspeed", lim > 0 && kmh > lim, kmh, loc, at);
        zoneCheck(loc, kmh, at);

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
        lastQueued = at;
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
            p.put("recorded_at", Outbox.iso(at));
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

    /** The zone this runner last found the vehicle inside, whose own limit is lowest. */
    private Zone tight;

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
     * Driving into a zone, out of one, or past its own limit, read from the fix the fleet's
     * interval is being cut by.
     *
     * The rows are only ever written with the page asleep, exactly as the vehicle breaches
     * are: while the driver is looking at his app that page runs the same test on the same
     * shapes, and two writers of one crossing is how a trip ends up with an entered and a left
     * record for a vehicle that never moved. The inside-set travels back in the status either
     * way, so a page that wakes agrees with this one before it tests its next fix.
     */
    private void zoneCheck(Location loc, double kmh, long at) {
        if (zones.isEmpty()) {
            tight = null;
            return;
        }
        boolean quiet = svc != null && svc.pageAsleep();
        double lat = loc.getLatitude(), lng = loc.getLongitude();
        for (Zone z : zones) {
            boolean on = inside(z, lat, lng);
            boolean was = z.inside;
            boolean fresh = !z.known;
            z.inside = on;
            z.known = true;
            if (fresh || on == was || !quiet) continue;
            if (on && z.enter) zEvent("zone_enter", z, loc, at, kmh);
            else if (!on && z.exit) zEvent("zone_exit", z, loc, at, kmh);
        }
        Zone best = null;
        for (Zone z : zones) {
            if (!z.inside || !z.over || z.limit <= 0) continue;
            if (best == null || z.limit < best.limit) best = z;
        }
        tight = best;
        Config.Rule r = rules.get("zone_overspeed");
        double over = r == null ? 0 : Math.max(0, r.threshold);
        watch("zone_overspeed", best != null && kmh > best.limit + over, kmh, loc, at,
                best == null ? 0 : best.limit + over, best);
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

    /**
     * A crossing the page never saw. The row is the page's own shape - a moment, closed one
     * second after itself, with the zone's limit beside it - and it goes into the same outbox
     * the camera's events use, which is the one queue in this app that survives a sign-in, a
     * dead spot and a process Android killed. The page files it under its own live session when
     * it drains, so the row guards still decide what it becomes.
     */
    private void zEvent(String type, Zone z, Location loc, long at, double kmh) {
        DmsService s = svc;
        if (s == null) return;
        Config.Rule r = rules.get(type);
        if (r != null && !r.on) return;
        String cid = java.util.UUID.randomUUID().toString();
        String detail = (type.equals("zone_enter") ? "Entered " : "Left ")
                + (z.name.isEmpty() ? "zone" : z.name)
                + (z.limit > 0 ? " · limit " + Math.round(z.limit) + " km/h" : "");
        JSONObject p = s.row(type, cid, at, (double) Math.round(kmh), detail);
        JSONObject item = new JSONObject();
        try {
            // a zone change is a moment, not a breach that grows, so it closes after itself -
            // the same second the page gives it, for the same reason: a blank duration column
            // reads as lost data to the office
            p.put("ended_at", Outbox.iso(at + 1000));
            if (z.limit > 0) p.put("road_limit", (double) Math.round(z.limit));
            item.put("op", "ev").put("cid", cid).put("clip", null).put("p", p);
        } catch (Exception ignore) {
            // JSONObject only throws on a null key, and none of these are null
        }
        s.outbox().add(item);
        s.pageEvent();
        // the office's Alert / Buzzer / Voice switches for the crossing itself, behind the same
        // 60 second guard every other warning on this phone uses
        if (r != null && r.alert) {
            sound(type, r, Math.round(kmh) + " km/h"
                    + (z.limit > 0 ? " against a " + Math.round(z.limit) + " km/h zone limit" : ""),
                    kmh, at);
        }
    }

    // ---- the alerts the page cannot hear with its screen off

    /**
     * One breach of one of the vehicle's own rules, as the runner saw it.
     *
     * Only the stretches this page cannot see are ever written here. While the driver is
     * looking at his app the page runs its own detection - it has the zones, the peak maths
     * and the words the Events table prints - and two writers of one corner is how a trip
     * ends up with two overspeed rows for one bend. So an episode opened behind a locked
     * screen is closed here if the limit is respected again before he wakes the app, and
     * handed back the moment he does: the page takes the row over, finishes it, and this
     * forgets it.
     */
    private static final class Breach {
        String cid = "";
        long start, last;
        double val, peak, kmh, lat, lng, alt, hdg;
        /** The level this breach was judged against, and the zone it belongs to: the fleet's
         *  own alert level for everything except a zone's limit, which is the one threshold in
         *  this app that moves with the ground the vehicle is on. */
        double lim;
        Zone z;
        boolean row, adopted;
    }

    private void watch(String type, boolean isBad, double val, Location loc, long at) {
        watch(type, isBad, val, loc, at, speedLimit + buf, null);
    }

    private void watch(String type, boolean isBad, double val, Location loc, long at, double lim, Zone z) {
        double acc = loc.hasAccuracy() ? loc.getAccuracy() : Double.NaN;
        if (Double.isNaN(acc) || acc > MAX_ACC) return;
        if (!isSpeed(type)) lastA = val;
        double kmh = loc.hasSpeed() ? Math.max(0, loc.getSpeed() * 3.6) : 0;
        Config.Rule r = rules.get(type);
        Boolean was = bad.get(type);
        boolean rising = isBad && !Boolean.TRUE.equals(was);
        bad.put(type, isBad);
        if (r == null || !r.on) return;
        DmsService s = svc;
        if (s == null) return;
        Breach b = open.get(type);
        if (!isBad) {
            if (b == null) return;
            open.remove(type);
            // A page that has taken this breach writes its own end; a page that never woke
            // has nobody else to write it, and an event with no end reads as a breach that
            // is still happening while the driver is at home.
            if (!b.adopted && b.row) closeBreach(type, r, b, Math.max(b.last, b.start));
            return;
        }
        if (b == null) {
            if (!rising) return;
            b = new Breach();
            b.start = b.last = at;
            b.val = b.peak = val;
            b.kmh = kmh;
            b.lim = lim;
            b.z = z;
            b.lat = loc.getLatitude();
            b.lng = loc.getLongitude();
            b.alt = loc.hasAltitude() ? loc.getAltitude() : Double.NaN;
            b.hdg = loc.hasBearing() ? loc.getBearing() : Double.NaN;
            open.put(type, b);
            // The driver is only ever warned out loud for a breach this page cannot banner
            // about himself. The 60 second guard is the page's own, seeded back into it on
            // the way wake, so one breach never buzzes a driver twice.
            if (s.pageAsleep()) sound(type, r, b, at);
        }
        if (Math.abs(val) > Math.abs(b.peak)) {
            b.peak = val;
            if (isSpeed(type)) b.kmh = kmh;
        }
        b.last = at;
        // The row follows the page's two rules exactly: it is written once the breach has
        // outlasted the tolerance the Configuration page sets, and a hard launch or a panic
        // stop is its own evidence, so it is written from the sample that peaked.
        if (!b.row && b.adopted) b.row = true;
        if (!b.row && s.pageAsleep()
                && (isSpeed(type) ? at - b.start >= (long) (r.holdS * 1000) : true)) {
            writeBreach(type, r, b);
        }
    }

    /** The two rules that are a speed held over seconds, rather than a pedal that peaked. */
    private static boolean isSpeed(String type) {
        return type.equals("overspeed") || type.equals("zone_overspeed");
    }

    private void sound(String type, Config.Rule r, Breach b, long at) {
        String detail = isSpeed(type)
                ? Math.round(b.kmh) + " km/h against a limit of " + Math.round(b.lim) + " km/h"
                + (b.z != null ? " in " + zName(b.z) : "")
                : Math.abs(round1(b.val)) + " km/h/s "
                + (type.equals("harsh_braking") ? "of braking" : "of acceleration");
        sound(type, r, detail, b.val, at);
    }

    /** What the office called the zone, or the one word a nameless shape is answered by. */
    private static String zName(Zone z) {
        return z != null && !z.name.isEmpty() ? z.name : "zone";
    }

    /** The one warning clock: a type this has spoken about in the last minute stays silent. */
    private void sound(String type, Config.Rule r, String detail, double val, long at) {
        DmsService s = svc;
        if (s == null) return;
        if (at - sounded.getOrDefault(type, 0L) < 60000) return;
        sounded.put(type, at);
        s.announced(type, detail, val, r);
    }

    /**
     * The row for a breach the page never saw. It is written into the camera service's own
     * queue of records waiting for a page, which is the one thing in this app that survives
     * sign-in, offline stretches and a process Android killed - and the page files it under
     * its own live session when it drains, so the row guards still decide what it becomes.
     */
    private void writeBreach(String type, Config.Rule r, Breach b) {
        DmsService s = svc;
        if (s == null) return;
        b.row = true;
        b.cid = java.util.UUID.randomUUID().toString();
        JSONObject p = s.row(type, b.cid, b.start, num(type, b, false), text(type, r, b, false));
        JSONObject item = new JSONObject();
        try {
            // the zone's own limit sits in its own column, exactly as the page puts it there,
            // so the Events table can tell a fleet limit from a school-road one without reading
            // the words
            if (b.z != null && b.z.limit > 0) p.put("road_limit", (double) Math.round(b.z.limit));
            item.put("op", "ev").put("cid", b.cid).put("clip", null).put("p", p);
        } catch (Exception ignore) {
            // JSONObject only throws on a null key, and none of these are null
        }
        s.outbox().add(item);
        s.pageEvent();
    }

    /** The end of that row, at the last reading that was still out of bounds. */
    private void closeBreach(String type, Config.Rule r, Breach b, long at) {
        DmsService s = svc;
        if (s == null || b.cid.isEmpty()) return;
        if (r == null) r = Config.off(type);
        JSONObject p = new JSONObject();
        JSONObject item = new JSONObject();
        try {
            p.put("ended_at", Outbox.iso(at));
            p.put("value", num(type, b, true));
            p.put("details", text(type, r, b, true));
            item.put("op", "upd").put("id", b.cid).put("p", p);
        } catch (Exception ignore) {
            // as above
        }
        s.outbox().add(item);
        s.pageEvent();
    }

    /** The number the Events table sorts on: km/h for a limit, km/h/s for a pedal. */
    private static Double num(String type, Breach b, boolean closed) {
        double x = isSpeed(type)
                ? (closed ? Math.max(b.kmh, b.peak) : b.kmh)
                : (closed ? b.peak : b.val);
        return Math.round(x * 10) / 10.0;
    }

    /**
     * What the row says about itself, in the same words the page writes. An administrator
     * who reads a camera event, a zone event and this one side by side must not be able to
     * tell which of the three the phone was awake for.
     */
    private String text(String type, Config.Rule r, Breach b, boolean closed) {
        long dur = Math.max(0, (closed ? Math.max(b.last, b.start) : b.start) - b.start) / 1000;
        if (isSpeed(type)) {
            // the zone says which of its own limits applied, because that is the only thing
            // that tells the office why this one row was raised at 60 and the next at 90
            String sb = Math.round(b.kmh) + " km/h against a " + Math.round(b.lim)
                    + " km/h alert level" + (b.z != null ? " in " + zName(b.z) : "");
            // "held over N s" is what the tolerance is for, and the page only ever says it
            // when the fleet has actually set one
            if (r.holdS > 0) sb += " held over " + (long) r.holdS + " s";
            if (closed && dur > 0) sb += " · lasted " + hms(dur);
            if (b.peak > b.kmh) sb += " · peak " + Math.round(b.peak) + " km/h";
            return sb;
        }
        boolean brk = type.equals("harsh_braking");
        double lim = Math.abs(round1((brk ? brakeLimit : accelLimit) * 3.6));
        String sb = Math.abs(round1(b.val)) + " km/h/s " + (brk ? "deceleration" : "acceleration")
                + " against a " + one1(lim) + " km/h/s limit";
        if (closed && dur > 0) sb += " · lasted " + hms(dur);
        if (Math.abs(b.peak) > Math.abs(b.val)) sb += " · peak " + one1(Math.abs(round1(b.peak))) + " km/h/s";
        return sb;
    }

    /** One decimal, the way the page prints it: 3.0 not 3. */
    private static String one1(double x) {
        return String.format(java.util.Locale.US, "%.1f", x);
    }

    /** hh:mm:ss, hours allowed past 24 - the one duration format the whole system uses. */
    private static String hms(long s) {
        long x = Math.max(0, s);
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", x / 3600, (x % 3600) / 60, x % 60);
    }

    /**
     * The breaches this runner opened and the page has now taken over, oldest first, so a
     * page that wakes can carry on recording them as its own. A row is only ever handed back
     * once: the page answers with {@link #claim} and this stops writing for it.
     */
    JSONArray openRows() {
        JSONArray a = new JSONArray();
        for (Map.Entry<String, Breach> e : open.entrySet()) {
            Breach b = e.getValue();
            if (!b.row || b.adopted || b.cid.isEmpty()) continue;
            try {
                JSONObject j = new JSONObject();
                j.put("type", e.getKey()).put("cid", b.cid).put("start", b.start)
                        .put("kmh", b.kmh).put("val", b.val).put("peak", b.peak)
                        .put("lat", b.lat).put("lng", b.lng);
                if (!Double.isNaN(b.alt)) j.put("alt", b.alt);
                if (!Double.isNaN(b.hdg)) j.put("hdg", b.hdg);
                a.put(j);
            } catch (Exception ignore) {
                // a breach this cannot describe is a breach the page will simply re-open
            }
        }
        return a;
    }

    /** The page has taken these breaches in hand: they are its rows now, not this runner's. */
    void claim(JSONArray types) {
        if (types == null) return;
        for (int i = 0; i < types.length(); i++) {
            Breach b = open.get(types.optString(i));
            if (b != null) b.adopted = true;
        }
    }

    /** A breach still open when the trip ends gets its end time all the same. */
    private void closeOpen(long now) {
        for (Map.Entry<String, Breach> e : new HashMap<>(open).entrySet()) {
            Breach b = e.getValue();
            if (b.row && !b.adopted) closeBreach(e.getKey(), rules.get(e.getKey()), b,
                    Math.max(b.last, now));
        }
        open.clear();
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

    /** `urgent` is a point the office should see now, so the two-second wait is skipped. */
    private void trySend(boolean urgent) {
        if (!allowed || token.isEmpty() || url.isEmpty() || anon.isEmpty()) return;
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
            final String tk = token;
            e.execute(() -> send(tk));
        } catch (Throwable ignore) {
            // a pool that will not take the chore means the trip is ending; the rows are
            // on disk and the page drains them
        }
    }

    private void send(String tk) {
        // A trip that ran offline for a day is sixty thousand rows, and this must not hold
        // the queue while an unbounded loop tries them all: one run empties what it can and
        // the next turn of the clock takes the rest.
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
            // turn: either the server never heard the request or the sign-in ran out. DROPPED
            // means one row of this batch went away and the rest of the queue is untouched, so
            // the trip carries on reporting from the second it stopped being thrown away for.
            if (r == KEPT) return;
            if (r == LANDED && !error.isEmpty()) error = "";
        }
    }

    /** What one try at a batch of positions came back as. */
    private static final int LANDED = 0, KEPT = 1, DROPPED = 2;

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
     * this app cannot refresh by itself, which every row survives because signing in again
     * delivers all of them.
     *
     * @return LANDED when the server took every row, KEPT when they are back on the queue and
     * nothing more should be tried this turn, DROPPED when a row was refused and thrown away.
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
            synchronized (rows) {
                requeue(batch);
                save();
            }
            error = "the phone's sign-in has run out: open the app to send what is waiting";
            allowed = false;
            return KEPT;
        }
        if (batch.size() > 1) {
            int h = (batch.size() + 1) / 2;
            int a = tryBatch(batch.subList(0, h), tk);
            if (a == KEPT) return KEPT;
            int b = tryBatch(batch.subList(h, batch.size()), tk);
            return b == KEPT ? KEPT : (a == DROPPED || b == DROPPED ? DROPPED : LANDED);
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

    private String rulesJson() {
        JSONArray a = new JSONArray();
        for (String t : RULES) {
            Config.Rule r = rules.get(t);
            if (r != null) a.put(Config.ruleJson(r));
        }
        return a.toString();
    }

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

    private void readSettings(String raw) {
        if (raw == null || raw.isEmpty()) return;
        JSONArray a;
        try {
            a = new JSONArray(raw);
        } catch (Exception e) {
            return;
        }
        for (int i = 0; i < a.length(); i++) addRule(a.optJSONObject(i));
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
        speedLimit = j.optDouble("speedLimit", speedLimit);
        buf = j.optDouble("buffer", buf);
        accelLimit = j.optDouble("accelLimit", accelLimit);
        brakeLimit = j.optDouble("brakeLimit", brakeLimit);
        JSONObject ids = j.optJSONObject("ids");
        if (ids != null) {
            uid = ids.optString("user_id", uid);
            vid = ids.optString("vehicle_id", vid);
            did = ids.optString("driver_id", did);
            vname = ids.optString("vehicle_name", vname);
            dname = ids.optString("driver_name", dname);
        }
        JSONArray rs = j.optJSONArray("rules");
        if (rs != null) {
            for (int i = 0; i < rs.length(); i++) addRule(rs.optJSONObject(i));
        }
        if (j.has("trip")) trip = j.optBoolean("trip", trip);
        readZones(j.optJSONArray("zones"));
    }

    private void addRule(JSONObject r) {
        if (r == null) return;
        String t = r.optString("type", "");
        if (RULES.contains(t)) rules.put(t, Config.Rule.of(t, r));
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
            JSONArray s = new JSONArray();
            for (Map.Entry<String, Long> e : sounded.entrySet()) {
                s.put(new JSONObject().put("type", e.getKey()).put("at", e.getValue()));
            }
            j.put("running", running).put("wanted", want).put("queued", q).put("sent", sent)
                    .put("movingS", movingS).put("parkedS", parkedS)
                    .put("trip", trip).put("moving", isMoving)
                    .put("listening", providers.size())
                    .put("sending", allowed && !token.isEmpty())
                    .put("sinceFix", lastFixAt == 0 ? -1
                            : (SystemClock.elapsedRealtime() - lastFixAt) / 1000)
                    .put("zones", insideZones())
                    .put("sounded", s).put("open", openRows()).put("error", error).put("why", why);
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
}
