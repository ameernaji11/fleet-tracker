package com.fleet.dms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * The one notice this app ever shows: the steady line Android requires of a service that
 * keeps working with the screen off, and nothing else.
 *
 * The full driver app has this class make a sound and speak a warning when its camera sees
 * something. This build has no camera and records no events, so there is nothing to sound
 * and nobody to warn: what is left is the notification that keeps the position reporter
 * alive, because without it Android takes the service down a few seconds after the screen
 * goes. It is deliberately silent, quiet and hidden on the lock screen for the same reason
 * the full app's trip notice is - a driver who has just locked his phone has not asked to be
 * told, twice a shift, that his handset is working.
 */
class Alerts {

    private static final String CH_TRIP = "fleet.dms.trip";

    private final Context ctx;
    private final NotificationManager nm;

    Alerts(Context c) {
        ctx = c;
        nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        channels();
    }

    /**
     * One channel, created once. Android keeps the sound of a channel that already exists,
     * and this one has never had one, so it is only ever built when the handset has no
     * record of it.
     */
    private void channels() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        if (nm.getNotificationChannel(CH_TRIP) != null) return;
        NotificationChannel tr = new NotificationChannel(CH_TRIP, "Position reporting",
                NotificationManager.IMPORTANCE_MIN);
        tr.setDescription("Keeps this vehicle's positions reporting while the screen is off");
        tr.setSound(null, null);
        tr.enableVibration(false);
        tr.setShowBadge(false);
        nm.createNotificationChannel(tr);
    }

    /** The steady notice that keeps the reporter alive, and tells the driver it is running. */
    Notification tripNotification(String detail) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_TRIP)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Fleet Tracker Lite is reporting")
                .setContentText(detail)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET);
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            b.setContentIntent(PendingIntent.getActivity(ctx, 7, open, flags));
        }
        return b.build();
    }
}
