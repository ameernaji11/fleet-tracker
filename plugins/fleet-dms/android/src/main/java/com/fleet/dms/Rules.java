package com.fleet.dms;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The four rules of the Configuration page, applied to what the camera just saw.
 *
 * Each one works the way the vehicle's own rules already work on this phone, so an
 * administrator who has set a tolerance time for overspeed is setting the same thing
 * here: the moment the limit is broken is the moment the driver is warned, the event
 * is only written once the breach has outlasted that tolerance, and the row is closed
 * with an end time and a duration when it stops. The reading kept with the row is the
 * worst of the breach, which is what a report should sort on.
 *
 * Nothing here decides what the numbers mean. FaceReader measures the face; this class
 * only asks how long each of them stayed out of bounds, and whether the vehicle was
 * going fast enough for the answer to matter at all.
 */
class Rules {

    static final String[] ORDER = {Alerts.FATIGUE, Alerts.DISTRACTED, Alerts.PHONE, Alerts.BLOCKED};
    // One minute between the sound for the same rule, exactly as the page does it: a
    // driver who is still turning his head must not be buzzed once a second.
    private static final long REPEAT_MS = 60000;
    // How long a face that was just in view still holds its cover reading, used only
    // so a phone raised in front of the face is not also reported as a blocked lens.
    private static final long COVER_FRESH_MS = 20000;
    // The share of the lower face that has to be covered before the object in front of
    // it is treated as the reason the face disappeared.
    private static final double COVER_OWN = 35;

    /** The open breach of one rule, and the id its row and its clip will share. */
    private static class Br {
        String cid;
        long start;
        double peak;
        int why;
        boolean row;
    }

    private final DmsService svc;
    private final Map<String, Br> open = new HashMap<>();
    private final long[] lastNotice = new long[ORDER.length];

    // the last second of eye closure, which is how the page defines this threshold
    private final long[] sT = new long[40];
    private final boolean[] sS = new boolean[40];
    private int sp, sn;
    private double lastCover;
    private long lastCoverAt;

    Rules(DmsService s) {
        svc = s;
    }

    /** Trip start and end: an unfinished breach belongs to the trip that ended. */
    void reset() {
        open.clear();
        sp = 0;
        sn = 0;
        lastCover = 0;
        lastCoverAt = 0;
        for (int i = 0; i < lastNotice.length; i++) lastNotice[i] = 0;
    }

    /** One measured frame, at the speed the vehicle was going when it was taken. */
    void frame(FaceReader.Reading r, double kmh, Config cfg) {
        long now = r.at;
        if (r.face) {
            lastCover = r.cover;
            lastCoverAt = now;
        }
        pushShut(r.face && r.shut, now);
        double perclos = perclos(now);

        for (String type : ORDER) {
            Config.Rule rule = cfg.rule(type);
            boolean bad;
            double val;
            if (type.equals(Alerts.FATIGUE)) {
                bad = rule.armed(kmh) && r.face && perclos >= rule.threshold;
                val = perclos;
            } else if (type.equals(Alerts.DISTRACTED)) {
                bad = rule.armed(kmh) && r.face && Math.abs(r.yaw) >= rule.threshold;
                val = Math.abs(r.yaw);
            } else if (type.equals(Alerts.PHONE)) {
                bad = rule.armed(kmh) && r.face && r.cover * 100 >= rule.threshold;
                val = r.cover * 100;
            } else {
                boolean hiddenByObject = now - lastCoverAt < COVER_FRESH_MS && lastCover * 100 >= COVER_OWN;
                bad = rule.armed(kmh) && r.blocked && !hiddenByObject;
                val = 0;
            }
            if (bad) breach(type, rule, now, val, r.why);
            else recover(type, rule, now);
        }
    }

    /** True while a breach of any rule is being watched, which is what the page paints. */
    boolean active() {
        return !open.isEmpty();
    }

    // ---- the breach machine

    private void breach(String type, Config.Rule rule, long now, double val, int why) {
        Br b = open.get(type);
        if (b == null) {
            b = new Br();
            b.start = now;
            b.peak = val;
            b.why = why;
            open.put(type, b);
            int i = idx(type);
            // the warning is for the moment the limit was broken, not the moment the
            // row was written, and a breach of the same rule one minute later is quiet
            if (now - lastNotice[i] >= REPEAT_MS) {
                lastNotice[i] = now;
                svc.announced(type, shortText(type, rule, b.peak, b.why), b.peak, rule);
            }
            if (rule.holdS <= 0) write(type, rule, b, now);
            return;
        }
        if (val > b.peak) {
            b.peak = val;
            b.why = why;
        }
        if (!b.row && (now - b.start) / 1000.0 >= rule.holdS) write(type, rule, b, now);
    }

    private void recover(String type, Config.Rule rule, long now) {
        Br b = open.remove(type);
        if (b != null && b.row) close(type, rule, b, now);
    }

    /**
     * The row opens with the readings of the moment the limit was first broken, and
     * the clip named after it starts here: the seconds of picture before that moment
     * are already in the ring, which is the only reason they exist.
     */
    private void write(String type, Config.Rule rule, Br b, long now) {
        Config cfg = svc.cfg();
        b.row = true;
        b.cid = UUID.randomUUID().toString();
        JSONObject p = svc.row(type, b.cid, b.start, value(type, b.peak),
                fullText(type, rule, b, now, false));
        svc.clip().begin(b.cid, cfg.preFrames(), cfg.postS);
        JSONObject item = new JSONObject();
        try {
            item.put("op", "ev").put("cid", b.cid).put("clip", b.cid + ".mp4").put("p", p);
        } catch (Exception ignore) {
            // as above
        }
        svc.outbox().add(item);
        svc.pageEvent();
    }

    private void close(String type, Config.Rule rule, Br b, long now) {
        JSONObject p = new JSONObject();
        JSONObject item = new JSONObject();
        try {
            p.put("ended_at", Outbox.iso(now));
            p.put("value", value(type, b.peak));
            p.put("details", fullText(type, rule, b, now, true));
            item.put("op", "upd").put("id", b.cid).put("p", p);
        } catch (Exception ignore) {
            // as above
        }
        svc.outbox().add(item);
        svc.pageEvent();
    }

    /** A breach still open when the trip ends gets its end time all the same. */
    void closeOpen(Config cfg, long now) {
        for (Map.Entry<String, Br> e : new HashMap<>(open).entrySet()) {
            Br b = e.getValue();
            if (b.row) close(e.getKey(), cfg.rule(e.getKey()), b, now);
        }
        open.clear();
    }

    // ---- what goes into the row

    /** The number the Events table prints. A blocked lens has no reading to sort on. */
    private static Double value(String type, double peak) {
        if (type.equals(Alerts.BLOCKED)) return null;
        return Math.round(peak * 10) / 10.0;
    }

    private static String pct(double x) {
        return String.format(Locale.US, "%.0f%%", x);
    }

    private static String deg(double x) {
        return String.format(Locale.US, "%.0f", x);
    }

    private static String secs(long ms) {
        return String.format(Locale.US, "%.1f s", Math.max(0, ms) / 1000.0);
    }

    private static String hms(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** Why the lens is judged blocked, in the words an administrator will read. */
    private static String blockedWhy(int why) {
        StringBuilder sb = new StringBuilder();
        if ((why & FaceReader.WHY_NOFACE) != 0) sb.append("nobody in view");
        if ((why & FaceReader.WHY_DARK) != 0) add(sb, "the view is dark");
        if ((why & FaceReader.WHY_FLAT) != 0) add(sb, "no detail in the view");
        if ((why & FaceReader.WHY_FROZEN) != 0) add(sb, "the picture stopped changing");
        if (sb.length() == 0) sb.append("the camera is covered");
        return sb.toString();
    }

    private static void add(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(", ");
        sb.append(s);
    }

    /** The warning the driver hears, and the first half of the line kept in the row. */
    private static String shortText(String type, Config.Rule r, double peak, int why) {
        if (type.equals(Alerts.FATIGUE))
            return "Eyes shut " + pct(peak) + " of the last second against a " + pct(r.threshold) + " limit";
        if (type.equals(Alerts.DISTRACTED))
            return "Head turned " + deg(peak) + " degrees off the road against a " + deg(r.threshold) + " degree limit";
        if (type.equals(Alerts.PHONE))
            return "Something in front of the face: " + pct(peak) + " of the lower face against a "
                    + pct(r.threshold) + " limit";
        return "Driver camera: " + blockedWhy(why);
    }

    /** The whole line of the row: the reading, the limit, the tolerance and the length. */
    private static String fullText(String type, Config.Rule r, Br b, long now, boolean closed) {
        String head = shortText(type, r, b.peak, b.why);
        long held = Math.max(0, now - b.start);
        if (type.equals(Alerts.BLOCKED)) head = "Driver camera: " + blockedWhy(b.why);
        String tail = r.holdS > 0 ? " · tolerance " + deg(r.holdS) + " s" : "";
        if (closed) return head + " · lasted " + hms(held) + tail;
        return head + " · held " + secs(held) + tail;
    }

    // ---- the last second of eye closure

    private void pushShut(boolean shut, long now) {
        sT[sp] = now;
        sS[sp] = shut;
        sp = (sp + 1) % sT.length;
        if (sn < sT.length) sn++;
    }

    /**
     * The percentage of the last second the eyes were shut - the number the page puts
     * its fatigue limit on. Samples older than a second are skipped when they are
     * looked at rather than swept out, so a phone that stalls for a moment answers no
     * closure at all rather than a false one.
     */
    double perclos(long now) {
        int n = 0, s = 0;
        for (int i = 0; i < sn; i++) {
            if (now - sT[i] > 1000) continue;
            n++;
            if (sS[i]) s++;
        }
        return n == 0 ? 0 : 100.0 * s / n;
    }

    private static int idx(String type) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(type)) return i;
        return 0;
    }
}
