package com.fleet.dms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The two things a camera alert must do when the page cannot do them: make a sound
 * the driver hears with the screen locked, and say why.
 *
 * This is the same route the vehicle alerts already take. Android mutes the sound of
 * an app that is in the foreground and freezes the JavaScript of one that is not, so
 * the only warning that reaches a driver who is looking at another screen is a
 * notification on a loud channel - and the tones those channels play are the seven
 * files the build copies from www/tones into res/raw. The names are looked up at run
 * time because they belong to the application, not to this module.
 *
 * When the page is on screen it is told about the breach instead, and it runs the
 * banner, the buzzer and the voice itself. One breach, one sound, either way.
 */
class Alerts {

    /** The buzzer choices of the Configuration page, identical to the page's own list. */
    static final Set<String> TONES = new HashSet<>(Arrays.asList(
            "discontinuous", "continuous", "fast", "twotone", "siren", "chime", "pulse"));

    private static final String CH_PREFIX = "fleet.dms.";
    private static final String CH_QUIET = CH_PREFIX + "quiet";
    private static final String CH_TRIP = CH_PREFIX + "trip";

    /** The four rules, named the way the event rows are named. */
    static final String FATIGUE = "dms_fatigue", DISTRACTED = "dms_distraction",
            PHONE = "dms_phone_use", BLOCKED = "dms_cam_blocked";

    private static final Map<String, String> FALLBACK = new HashMap<>();
    static {
        FALLBACK.put(FATIGUE, "You look tired. Please take a break.");
        FALLBACK.put(DISTRACTED, "Please keep your eyes on the road.");
        FALLBACK.put(PHONE, "Please do not use a phone while driving.");
        FALLBACK.put(BLOCKED, "The driver camera is covered. Keep the view clear.");
    }

    private final Context ctx;
    private final NotificationManager nm;
    private TextToSpeech tts;
    private boolean ttsReady, ttsBroken;
    private String ttsLang = "";
    private String[] pending;
    private JSONObject msgs = new JSONObject();

    Alerts(Context c) {
        ctx = c;
        nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        channels();
    }

    /**
     * The words to speak, taken from the page rather than copied here. The driver
     * app holds them in English, Arabic, Hindi and Urdu and the rule carries which of
     * those it should use, so a translation added on the page needs no new build.
     */
    void setMessages(JSONObject m) {
        if (m != null) msgs = m;
    }

    /**
     * The words for one rule in one language. The page sends its own message table,
     * which is grouped by language first and by rule second - the shape the driver app
     * has always kept them in - and a language the table does not carry falls back to
     * English rather than to silence.
     */
    private String textFor(String type, String lang) {
        String want = lang == null || lang.isEmpty() ? "en" : lang;
        JSONObject byType = msgs.optJSONObject(want);
        String t = byType == null ? null : byType.optString(type, null);
        if (t == null || t.trim().isEmpty()) {
            JSONObject en = msgs.optJSONObject("en");
            t = en == null ? null : en.optString(type, null);
        }
        if (t == null || t.trim().isEmpty()) t = FALLBACK.get(type);
        return t == null ? "" : t;
    }

    /** A tone file of the application, or null when the build did not ship it. */
    private Uri tone(String name) {
        int id = ctx.getResources().getIdentifier(name, "raw", ctx.getPackageName());
        if (id == 0) return null;
        return Uri.parse("android.resource://" + ctx.getPackageName() + "/raw/" + id);
    }

    /**
     * Android writes a channel's sound when the channel is created and never lets the
     * app change it again, so each tone gets its own channel and the ids here are new
     * to this build. A tone whose file is missing makes a silent channel rather than
     * no channel at all, so an alert still reaches the shade.
     */
    private void channels() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        for (String t : TONES) {
            String id = CH_PREFIX + t;
            if (wasCreated(id)) continue;
            NotificationChannel ch = new NotificationChannel(id, "Driver camera: " + t,
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Warning raised by the driver camera with the screen off");
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ch.enableVibration(true);
            Uri u = tone(t);
            if (u != null) ch.setSound(u, aa);
            nm.createNotificationChannel(ch);
        }
        if (!wasCreated(CH_QUIET)) {
            NotificationChannel q = new NotificationChannel(CH_QUIET, "Driver camera: in app",
                    NotificationManager.IMPORTANCE_LOW);
            q.setDescription("Shown while the app is open, without a second sound");
            q.setSound(null, null);
            q.enableVibration(false);
            nm.createNotificationChannel(q);
        }
        if (!wasCreated(CH_TRIP)) {
            NotificationChannel tr = new NotificationChannel(CH_TRIP, "Driver camera: working",
                    NotificationManager.IMPORTANCE_MIN);
            tr.setDescription("Keeps the driver camera open while the trip runs");
            tr.setSound(null, null);
            tr.enableVibration(false);
            tr.setShowBadge(false);
            nm.createNotificationChannel(tr);
        }
    }

    // A channel that already exists must not be re-created: Android keeps its old
    // sound, and building it again would look like the tone had been ignored.
    private boolean wasCreated(String id) {
        try {
            return nm != null && nm.getNotificationChannel(id) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The steady notice that keeps the camera service alive, and tells the driver it is running. */
    Notification tripNotification(String detail) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CH_TRIP)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Driver camera is watching")
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

    /**
     * The breach notice for a screen the driver is not looking at. The channel is the
     * loud one only when the buzzer is wanted and the driver has not muted the app;
     * otherwise it is the silent channel, which still puts the words on the screen.
     */
    void breach(String type, String detail, Config.Rule r, boolean mute) {
        if (nm == null) return;
        String tone = r.beepType;
        boolean loud = !mute && r.beep && r.beepDur > 0 && TONES.contains(tone);
        String ch = loud ? CH_PREFIX + tone : CH_QUIET;
        Uri u = loud ? tone(tone) : null;
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title(type))
                .setContentText(detail)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(detail))
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (u != null) {
            // the channel this notice is posted to already carries the tone and the
            // usage that cuts through Do Not Disturb; a compat builder cannot set audio
            // attributes of its own, and on 26 and up the channel is what speaks anyway
            b.setSound(u);
            // the tone files run a few seconds; a long press of the same breach is
            // stopped by the repeat guard rather than by stacking notices
            b.setVibrate(new long[]{0, 350, 200, 350});
        } else {
            b.setSilent(true);
        }
        Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (open != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            b.setContentIntent(PendingIntent.getActivity(ctx, 8, open, flags));
        }
        try {
            // the trip's own notice holds id 1 and a breach must never replace it
            nm.notify((int) (System.currentTimeMillis() % 900000) + 1000, b.build());
        } catch (Exception ignore) {
            // a phone that refuses the notice still has the event in the queue
        }
    }

    private String title(String type) {
        if (type.equals(FATIGUE)) return "Driver fatigued";
        if (type.equals(DISTRACTED)) return "Driver distracted";
        if (type.equals(PHONE)) return "Phone in hand";
        return "Camera blocked";
    }

    /**
     * Speak the warning. The engine is started lazily because it is a separate
     * process of the phone, and it answers a few hundred milliseconds after the
     * request: a breach that comes before it has answered is carried by the notice
     * alone, which is the same thing the page does when no voice is available.
     */
    void speak(String type, String lang, boolean mute) {
        if (mute || ttsBroken) return;
        if (tts == null) {
            // the engine is another process of the phone and answers a few hundred
            // milliseconds after the request, so the first warning of a trip is kept
            // and spoken the moment it says it is ready
            pending = new String[]{type, lang};
            tts = new TextToSpeech(ctx, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true;
                    String[] p = pending;
                    pending = null;
                    if (p != null) speak(p[0], p[1], false);
                } else {
                    ttsBroken = true;
                }
            });
            return;
        }
        if (!ttsReady) {
            pending = new String[]{type, lang};
            return;
        }
        final String text = textFor(type, lang);
        if (text.isEmpty()) return;
        pending = null;
        try {
            String want = lang == null || lang.isEmpty() ? "en" : lang;
            if (!want.equals(ttsLang)) {
                // Android reports its voices by language alone, so a region tag such as
                // ur-PK is refused by engines that do have Urdu: ask for the bare code.
                Locale loc = Locale.forLanguageTag(want);
                int r = tts.setLanguage(loc);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.ENGLISH);
                    ttsLang = "en";
                } else ttsLang = want;
            }
            // the engine takes its parameters as a Bundle since API 21: the utterance id
            // is how stopVoice() can tell its own sentence from one already queued
            Bundle p = new Bundle();
            p.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, type);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, type);
        } catch (Exception ignore) {
            // no engine, or an engine that will not talk: the notice already said it
        }
    }

    void stopVoice() {
        if (tts == null) return;
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignore) {
            // shutting the engine down twice is not a problem worth reporting
        }
        tts = null;
        ttsReady = false;
        ttsBroken = false;
        pending = null;
        ttsLang = "";
    }

    /** Whether notices from this app are allowed at all - the page warns the driver. */
    boolean blocked() {
        try {
            return nm != null && !nm.areNotificationsEnabled();
        } catch (Exception e) {
            return false;
        }
    }
}
