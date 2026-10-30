package com.fleet.dms;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the driver page can tell the camera to change, in one immutable-ish
 * object. The page sends it as JSON, the same JSON is kept in shared preferences,
 * and a service that Android restarts on its own reads that copy first so it is
 * watching for the right things before the page has had a chance to speak again.
 */
public class Config {

    static final String PREFS = "fleet.dms";
    static final String[] TYPES = {"dms_fatigue", "dms_distraction", "dms_phone_use", "dms_cam_blocked"};
    private static final List<String> TYPES_LIST = new ArrayList<>();
    static { for (String t : TYPES) TYPES_LIST.add(t); }

    /** One rule row of the Configuration page, already clamped to what can be acted on. */
    static class Rule {
        String type = "";
        boolean on = true, alert = true, beep = true, voice = true;
        double threshold, holdS, speedMin;
        int beepDur = 5;
        String beepType = "discontinuous", voiceLang = "en";

        static Rule of(String type, JSONObject j) {
            Rule r = new Rule();
            r.type = type;
            r.on = j.optBoolean("enabled", true);
            r.threshold = j.optDouble("threshold", 0);
            r.holdS = Math.max(0, j.optDouble("hold_s", 0));
            // A database that has not run v19 yet has no speed_min column, so the page
            // cannot send one. Standing still is then the case to guess at, and the
            // guess the seeded rows make is 10 km/h: an alert about a driver's face is
            // noise while the vehicle is parked, and a phone left on a seat with
            // nobody in it would otherwise raise camera-blocked all day in a depot.
            r.speedMin = (j.has("speed_min") && !j.isNull("speed_min"))
                    ? Math.max(0, j.optDouble("speed_min", 10)) : 10;
            r.alert = j.optBoolean("alert", true);
            r.beep = j.optBoolean("beep", true);
            r.beepDur = Math.max(1, Math.min(30, j.optInt("beep_duration", 5)));
            String bt = j.optString("beep_type", "discontinuous");
            r.beepType = Alerts.TONES.contains(bt) ? bt : "discontinuous";
            r.voice = j.optBoolean("voice", true);
            r.voiceLang = lang(j.optString("voice_lang", "en"));
            return r;
        }

        /** True when this rule may judge the frame that is being looked at right now. */
        boolean armed(double kmh) {
            return on && kmh >= speedMin;
        }
    }

    boolean on, preview = true, mute, voiceOn = true;
    int fps = 5, keepD = 7;
    double preS = 3, postS = 1;
    String lens = "front", voiceLang = "en";
    String userId = "", vehicleId = "", driverId = "", vehicleName = "", driverName = "";
    /**
     * The words to speak, by rule and by language, exactly as the driver app holds them.
     * Kept here rather than written out in Java so a translation added on the page needs
     * no new build - and so a service Android restarts speaks them before the page has
     * had a chance to say them again.
     */
    JSONObject msgs = new JSONObject();
    final Map<String, Rule> rules = new HashMap<>();

    static String lang(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int dash = v.indexOf('-');
        if (dash > 0) v = v.substring(0, dash);
        return v.equals("ar") || v.equals("hi") || v.equals("ur") ? v : "en";
    }

    /**
     * Build the settings to run on. The page sends what it knows, anything it leaves
     * out is carried over from the object already in use, and the result is always a
     * new Config: the camera thread reads the old one while this runs, so nothing may
     * be changed in place.
     */
    static Config parse(JSONObject j, Config base) {
        Config c = new Config();
        if (base != null) {
            c.on = base.on; c.preview = base.preview; c.mute = base.mute; c.voiceOn = base.voiceOn;
            c.fps = base.fps; c.keepD = base.keepD; c.preS = base.preS; c.postS = base.postS;
            c.lens = base.lens; c.voiceLang = base.voiceLang;
            c.msgs = base.msgs;
            c.userId = base.userId; c.vehicleId = base.vehicleId; c.driverId = base.driverId;
            c.vehicleName = base.vehicleName; c.driverName = base.driverName;
            c.rules.putAll(base.rules);
        }
        if (j.has("dms_on")) c.on = j.optBoolean("dms_on", false);
        if (j.has("dms_preview")) c.preview = j.optBoolean("dms_preview", true);
        if (j.has("dms_fps")) c.fps = clamp(j.optInt("dms_fps", 5), 1, 15);
        if (j.has("dms_pre_s")) c.preS = clamp(j.optDouble("dms_pre_s", 3), 0, 10);
        if (j.has("dms_post_s")) c.postS = clamp(j.optDouble("dms_post_s", 1), 0, 10);
        if (j.has("dms_keep_d")) c.keepD = clamp(j.optInt("dms_keep_d", 7), 1, 30);
        if (j.has("mute")) c.mute = j.optBoolean("mute", false);
        if (j.has("voice_on")) c.voiceOn = j.optBoolean("voice_on", true);
        if (j.has("voice_lang")) c.voiceLang = lang(j.optString("voice_lang", "en"));
        if (j.has("lens")) c.lens = j.optString("lens", "front").equalsIgnoreCase("back") ? "back" : "front";

        JSONObject ids = j.optJSONObject("ids");
        if (ids != null) {
            c.userId = ids.optString("user_id", c.userId);
            c.vehicleId = ids.optString("vehicle_id", c.vehicleId);
            c.driverId = ids.optString("driver_id", c.driverId);
            c.vehicleName = ids.optString("vehicle_name", c.vehicleName);
            c.driverName = ids.optString("driver_name", c.driverName);
        }

        JSONObject m = j.optJSONObject("msgs");
        if (m != null) c.msgs = m;

        JSONArray rs = j.optJSONArray("rules");
        if (rs != null) {
            Map<String, Rule> fresh = new HashMap<>();
            for (int i = 0; i < rs.length(); i++) {
                JSONObject r = rs.optJSONObject(i);
                if (r == null) continue;
                String type = r.optString("type", "");
                // only the four face rules are this plugin's business; the vehicle rules
                // arrive in the same list and are ignored here on purpose
                if (!TYPES_LIST.contains(type)) continue;
                fresh.put(type, Rule.of(type, r));
            }
            // a rule the page did not send keeps whatever it had, so a phone that is
            // offline and reading its own saved copy still has all four armed
            for (String t : TYPES) if (!fresh.containsKey(t) && c.rules.containsKey(t)) fresh.put(t, c.rules.get(t));
            c.rules.clear();
            c.rules.putAll(fresh);
        }
        // Every rule is present before the camera ever looks at it. A page that has not
        // spoken yet, or a database with no dms rows in it, then means a rule that is
        // switched off rather than one that has to be invented at the moment it is
        // asked for - which would put a write into a map the camera thread is reading.
        for (String t : TYPES) if (!c.rules.containsKey(t)) c.rules.put(t, off(t));
        return c;
    }

    /** A rule nobody has configured: it exists, and it never fires. */
    private static Rule off(String type) {
        Rule r = new Rule();
        r.type = type;
        r.on = false;
        return r;
    }

    /** A rule always exists by the time a frame is judged, even an unseeded one. */
    Rule rule(String type) {
        Rule r = rules.get(type);
        return r == null ? off(type) : r;
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Written back out so the copy on disk says what the camera is actually doing. */
    JSONObject toJson() {
        JSONObject j = new JSONObject();
        try {
            j.put("dms_on", on).put("dms_preview", preview).put("dms_fps", fps)
                    .put("dms_pre_s", preS).put("dms_post_s", postS).put("dms_keep_d", keepD)
                    .put("mute", mute).put("voice_on", voiceOn).put("voice_lang", voiceLang).put("lens", lens)
                    .put("msgs", msgs);
            j.put("ids", new JSONObject().put("user_id", userId).put("vehicle_id", vehicleId)
                    .put("driver_id", driverId).put("vehicle_name", vehicleName).put("driver_name", driverName));
            JSONArray rs = new JSONArray();
            for (String t : TYPES) rs.put(new JSONObject().put("type", t)
                    .put("enabled", rule(t).on).put("threshold", rule(t).threshold).put("hold_s", rule(t).holdS)
                    .put("speed_min", rule(t).speedMin).put("alert", rule(t).alert).put("beep", rule(t).beep)
                    .put("beep_duration", rule(t).beepDur).put("beep_type", rule(t).beepType)
                    .put("voice", rule(t).voice).put("voice_lang", rule(t).voiceLang));
            j.put("rules", rs);
        } catch (Exception ignore) {
            // JSONObject only throws on a null key, and none of these are null
        }
        return j;
    }

    void save(Context c) {
        try {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("cfg", toJson().toString()).putLong("cfg_at", System.currentTimeMillis()).apply();
        } catch (Exception ignore) {
            // a settings write that fails costs the saved copy only: the live values
            // just installed are what the camera runs on
        }
    }

    /** The copy on disk, or a disabled Config when nothing has ever been sent. */
    static Config load(Context c) {
        String raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("cfg", null);
        if (raw == null) return new Config();
        try {
            return parse(new JSONObject(raw), null);
        } catch (Exception e) {
            return new Config();
        }
    }

    /** Seconds of picture the buffer must hold before an alert, plus a small margin. */
    int preFrames() {
        return Math.max(2, Math.min(120, (int) Math.ceil(preS * fps) + fps / 2 + 1));
    }
}
