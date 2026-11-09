package com.fleet.dms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.getcapacitor.Logger;

/**
 * Brings the position runner back when the handset does.
 * <p>
 * A reboot is the one thing this app cannot plan for from inside itself. The phone is restarted
 * at a depot, or overnight by its own schedule, or after an update, and everything Android kept
 * - the queue of unsent positions, the order the office's Configuration page set, the sign-in
 * this phone sealed for itself - is still on the disk, but nothing is left awake to use any of
 * it. Until now the reporting started again the moment the driver opened the app, which is the
 * very thing this is meant to stop him having to do: a vehicle driven all morning on a handset
 * that rebooted at six arrives at the office as a vehicle that never moved.
 * <p>
 * Two answers this keeps to. It only starts anything when the order is still standing
 * ({@link Track#wanted}), so a driver who signed out, or who never signed in on this handset,
 * does not get a service woken over his weekend. And it asks for positions and nothing else
 * ({@link DmsService#ACTION_TRACK}), so a reboot is never itself the reason a handset starts
 * work - only a shift the office had already asked this phone to report.
 * <p>
 * The runner that comes back from this collects from the first second and sends as soon as it
 * can. With a sealed sign-in on the phone it is uploading within its own interval; without one
 * it waits, and the queue that a driver used to find waiting when he opened the app drains by
 * itself the moment he does.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String act = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(act)) return;
        if (!Track.wanted(ctx)) return;
        Intent i = new Intent(ctx, DmsService.class);
        i.setAction(DmsService.ACTION_TRACK);
        try {
            // Android will not let an app that is not on screen start a foreground service, and
            // a system broadcast is one of the few exemptions it leaves. This is the only place
            // the runner is asked for outside a page.
            ContextCompat.startForegroundService(ctx, i);
        } catch (Throwable t) {
            // The order stays on disk. A phone that refuses this is a phone the page brings up
            // when the driver opens it, which is what happened before this receiver existed.
            Logger.error("fleet-dms: the position runner would not start after a reboot", t);
        }
    }
}
