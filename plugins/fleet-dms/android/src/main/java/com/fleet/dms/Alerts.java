package com.fleet.dms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Vibrator;
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
 * The tone is played through MediaPlayer on the alarm audio stream — the same seven
 * WAV files the build copies from www/tones into res/raw — so it cuts through Do
 * Not Disturb without a notification in the shade. The voice uses native TTS.
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

    /** The nine rules, named the way the event rows are named. */
    static final String FATIGUE = "dms_fatigue", DISTRACTED = "dms_distraction",
            PHONE = "dms_phone_use", BLOCKED = "dms_cam_blocked", YAWN = "dms_yawning",
            TALK = "dms_talking", EAT = "dms_eating", DRINK = "dms_drinking",
            SMOKE = "dms_smoking";

    private static final Map<String, String> FALLBACK = new HashMap<>();
    static {
        FALLBACK.put(FATIGUE, "You look tired. Please take a break.");
        FALLBACK.put(DISTRACTED, "Please keep your eyes on the road.");
        FALLBACK.put(PHONE, "Please do not use a phone while driving.");
        FALLBACK.put(BLOCKED, "The driver camera is covered. Keep the view clear.");
        FALLBACK.put(YAWN, "You are yawning. Please take a break.");
        FALLBACK.put(TALK, "Please keep conversations short while driving.");
        FALLBACK.put(EAT, "Please do not eat while driving.");
        FALLBACK.put(DRINK, "Please do not drink while driving.");
        FALLBACK.put(SMOKE, "Smoking in the car is not allowed. Please stop.");
    }

    private final Context ctx;
    private final NotificationManager nm;
    private MediaPlayer tonePlayer;
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
     * app holds them in English, Arabic, Kurdish (Kurmanji and Sorani), Hindi and Urdu
     * and the rule carries which of those it should use, so a translation added on the
     * page needs no new build.
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
     * Only the trip channel is still needed: it carries the ongoing foreground-service
     * notice that keeps the camera and the position reporter alive. The loud tone
     * channels and the quiet channel are no longer posted to — alerts are now played
     * via MediaPlayer and spoken via TTS directly.
     */
    private void channels() {
        if (Build.VERSION.SDK_INT < 26 || nm == null) return;
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
     * The breach sound for a screen the driver is not looking at. Plays the tone
     * directly through the alarm audio stream so it cuts through Do Not Disturb,
     * then vibrates if the tone file is available.
     */
    void breach(String type, String detail, Config.Rule r, boolean mute) {
        String tone = r.beepType;
        boolean loud = !mute && r.beep && r.beepDur > 0 && TONES.contains(tone);
        if (!loud) return;
        Uri u = tone(tone);
        if (u == null) return;
        stopTonePlayer();
        try {
            tonePlayer = new MediaPlayer();
            tonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            tonePlayer.setDataSource(ctx, u);
            tonePlayer.setOnCompletionListener(mp -> stopTonePlayer());
            tonePlayer.prepare();
            tonePlayer.start();
        } catch (Exception ignore) {
            stopTonePlayer();
        }
        Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vib != null) try { vib.vibrate(new long[]{0, 350, 200, 350}, -1); } catch (Exception ignored) {}
    }

    private void stopTonePlayer() {
        if (tonePlayer != null) {
            try { tonePlayer.stop(); } catch (Exception ignored) {}
            try { tonePlayer.release(); } catch (Exception ignored) {}
            tonePlayer = null;
        }
    }

    void release() {
        stopTonePlayer();
    }

    /** The human-readable name of a camera rule, used in event details. */
    static String title(String type) {
        if (type.equals(FATIGUE)) return "Driver fatigued";
        if (type.equals(DISTRACTED)) return "Driver distracted";
        if (type.equals(PHONE)) return "Phone in hand";
        if (type.equals(YAWN)) return "Driver yawning";
        if (type.equals(TALK)) return "Talking while driving";
        if (type.equals(EAT)) return "Eating while driving";
        if (type.equals(DRINK)) return "Drinking while driving";
        if (type.equals(SMOKE)) return "Smoking";
        return "Camera blocked";
    }

    /**
     * Speak the warning. The engine is started lazily because it is a separate
     * process of the phone, and it answers a few hundred milliseconds after the
     * request: a breach that comes before it has answered is carried by the tone
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
        final String want = lang == null || lang.isEmpty() ? "en" : lang;
        final String text = textFor(type, want);
        if (text.isEmpty()) return;
        pending = null;
        try {
            String spoken = text;
            if (!want.equals(ttsLang)) {
                // Android reports its voices by language alone, so a region tag such as
                // ur-PK is refused by engines that do have Urdu: ask for the bare code.
                Locale loc = Locale.forLanguageTag(want);
                int r = tts.setLanguage(loc);
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // no voice for the language on this handset. It must not be handed the
                    // foreign words it cannot say, and must not be trusted to read Kurdish
                    // letters with an English mouth: the same warning in English is spoken,
                    // so the driver always hears what happened even before a voice is added.
                    tts.setLanguage(Locale.ENGLISH);
                    ttsLang = "en";
                    String en = textFor(type, "en");
                    if (!en.isEmpty()) spoken = en;
                } else ttsLang = want;
            }
            // the engine takes its parameters as a Bundle since API 21: the utterance id
            // is how stopVoice() can tell its own sentence from one already queued
            Bundle p = new Bundle();
            p.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, type);
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, p, type);
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
