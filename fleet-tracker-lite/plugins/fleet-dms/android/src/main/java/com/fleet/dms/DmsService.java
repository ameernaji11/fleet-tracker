package com.fleet.dms;

import android.Manifest;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.Logger;

/**
 * The part of the driver app that keeps working when the app itself does not.
 *
 * Android freezes the JavaScript of an app that is not on screen and stops its timers, so a
 * page cannot go on reporting a vehicle's positions once the driver has locked his phone to
 * answer a call. Everything that has to go on in those moments is here instead: a service of
 * type location, which is the one form of background work Android allows a handset that holds
 * a location permission to do at all.
 *
 * This is the tracking-only build. There is no camera, no driver monitoring, no alerts and no
 * events, so this class holds one thing and one reason to exist: {@link Track}, which samples
 * the handset's own receivers at the fleet's two recording intervals, keeps the trip's points
 * on the disk while the vehicle is out of signal, and posts them with the driver's own sign-in
 * as soon as a page can hand one over.
 */
public class DmsService extends Service {

    public static final String ACTION_STOP = "com.fleet.dms.STOP";
    /**
     * Positions only. Nothing in this build has a lens in it, so this is the only start the
     * page can ask for and the only one the service answers.
     */
    public static final String ACTION_TRACK = "com.fleet.dms.TRACK";

    private static final int NOTICE_TRIP = 1;

    private static volatile DmsService instance;

    static DmsService get() {
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    // The trip's own position runner: the whole reason this service exists.
    private Track track;
    private Alerts alerts;
    private PowerManager.WakeLock wake;

    // Set in onDestroy, before anything is put down: the answer every callback gives on its way
    // in, so a fix or a clock that arrives for a dying service finds nothing left to do.
    private volatile boolean dead;

    // ---- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        alerts = new Alerts(this);
        track = new Track(this);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        // The screen is off a lot of the working day, and a sleeping CPU stops sampling with
        // it; a partial wake lock costs battery and is the only way to keep the trip's seconds
        // while the driver's screen is dark. The runner decides when to hold it - see
        // {@link #reporting()} - so a van standing in a yard for an hour does not spend the
        // driver's whole charge saying nothing.
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fleet:track");
            wake.setReferenceCounted(false);
        }
        foreground(notice());
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (dead) {
            // Android can hand a fresh start to an instance that has already been told to go
            // away, and a service on its way out cannot declare itself foreground. The page is
            // told by its own status call, and asking again a moment later reaches the new
            // instance that replaces this one.
            return START_NOT_STICKY;
        }
        String act = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(act)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // Both a page's ask and Android's own wordless restart are answered by looking at what
        // the runner holds on disk. A service Android brought back after the driver signed out
        // holds nothing, and one that stayed alive for no reason is a notice in the driver's
        // shade and a wake lock on his battery, both for a vehicle nobody is reporting.
        if (!Track.wanted(this) && (track == null || !track.isRunning())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        reporting();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        dead = true;
        instance = null;
        // The runner goes down with the service it lives in, and the wish that started it stays
        // on disk: a trip Android interrupted is a trip that should carry on reporting when the
        // service comes back, not one whose positions were thrown away with the process.
        // Everything not yet sent is already in the queue file.
        if (track != null) track.closeDown();
        if (wake != null && wake.isHeld()) wake.release();
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignore) {
            // a service Android already took off the foreground has nothing to take down
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- what the page asks for

    /**
     * The line this service writes into its own notice: what the handset is really doing for
     * this driver. A phone that is signed in and standing still still owes the office an
     * occasional point, and one that has been told to go quiet says so here.
     */
    private String notice() {
        Track t = track;
        if (t != null && (t.isRunning() || t.isTripping() || t.isWanted())) {
            return "Reporting this vehicle's position to the office";
        }
        return "Waiting for the trip to start";
    }

    /**
     * The service doing the one thing it is here for: keeping the office told where this
     * vehicle is.
     *
     * The wake lock follows the vehicle rather than the service - held while a trip is open or
     * the wheels are turning, given back the moment it is standing with no trip asked for. A
     * phone that keeps Android's CPU awake to say once an hour that a van is in a yard is a
     * phone its driver unplugs at the end of his shift, and a dead handset reports nothing at
     * all.
     */
    void reporting() {
        if (isMain()) reportNow();
        else main.post(this::reportNow);
    }

    private void reportNow() {
        if (dead) return;
        Track t = track;
        boolean driving = t != null && (t.isTripping() || t.isMoving());
        if (wake != null) {
            if (driving && !wake.isHeld()) wake.acquire();
            else if (!driving && wake.isHeld()) wake.release();
        }
        foreground(notice());
    }

    void stopFromPage() {
        if (isMain()) stopSelf();
        else main.post(this::stopSelf);
    }

    /**
     * Whether this handset still owes the office its positions: the runner alive in front of us,
     * or an order on disk for one that is not. This is what decides whether the page's trip end
     * is a service that goes away or one that stays and changes its beat.
     */
    boolean keepsReporting() {
        Track t = track;
        return (t != null && t.isRunning()) || Track.wanted(this);
    }

    /** Sign-out: nothing on this phone belongs to that driver any more. */
    void clearAll() {
        if (track != null) track.clearDown();
        reporting();
    }

    // ---- threads

    /**
     * Whether this call is already on the thread Android runs this service's callbacks on.
     *
     * Every command the driver page sends arrives on Capacitor's bridge thread: "start the
     * trip", "here is the sign-in", "end it". A wake lock, a foreground-service notice and the
     * location listeners all belong to the main thread, and asking for them from the bridge
     * thread is a crash the page cannot see coming. So each entry point above puts its work
     * where it belongs rather than doing it where it was asked.
     */
    private boolean isMain() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    // ---- the notice that keeps the service alive

    private void foreground(String detail) {
        try {
            Notification n = alerts.tripNotification(detail);
            if (Build.VERSION.SDK_INT >= 29) {
                // Android 14 wants to be told which kind of background work this service is
                // doing, and refuses one whose type it has not been allowed: a location type
                // with the handset's location switched off is a SecurityException. So the type
                // says what this instance can actually do, and a phone with no location at all
                // is given the plain call, which is refused out loud rather than silently.
                boolean loc = granted(Manifest.permission.ACCESS_FINE_LOCATION)
                        || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
                if (loc) {
                    ServiceCompat.startForeground(this, NOTICE_TRIP, n,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
                } else {
                    startForeground(NOTICE_TRIP, n);
                }
            } else {
                startForeground(NOTICE_TRIP, n);
            }
        } catch (Throwable t) {
            // Android refuses a location service whose permission was taken back while it was
            // running, and the second refusal is not thrown back at us - it is the handset
            // killing the app a few seconds later for a foreground service that never came up.
            // So say why, and go down at once; the page asks again the next time it can see
            // itself, and the driver hears the reason from its own status strip.
            Logger.error("fleet-dms: this phone would not run the position reporter: " + name(t), t);
            if (!dead) stopSelf();
        }
    }

    private boolean granted(String permission) {
        try {
            return ContextCompat.checkSelfPermission(this, permission)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- helpers shared with Track

    Track track() {
        return track;
    }

    /** Set in onDestroy: the answer every callback gives on its way in. */
    boolean isDead() {
        return dead;
    }

    /** The charge on the phone at this moment, from the system's own copy. */
    double[] battery() {
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

    static String name(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
