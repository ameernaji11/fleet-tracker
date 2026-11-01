package com.fleet.dms;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The nine rules of the Configuration page, applied to what the camera just saw.
 *
 * Each one works the way the vehicle's own rules already work on this phone, so an
 * administrator who has set a tolerance time for overspeed is setting the same thing
 * here: the event is written once the breach has outlasted that tolerance, and the row
 * is closed with an end time and a duration when it stops. The reading kept with the row
 * is the worst of the breach, which is what a report should sort on.
 *
 * The warning sounds at that same moment rather than at the first bad frame, and that is
 * the whole of why a driver's alerts and the dashboard's events agree with each other.
 * They used to be two decisions: a buzz came on frame one, the row only once the
 * tolerance had run out, and a breach that flickered back inside the limit before then
 * was written off - so the driver was warned about seven things the fleet never saw
 * recorded. Now one moment answers for all three of a breach's consequences: the sound,
 * the row and its video. An alert the driver hears is always an event somebody can
 * watch, and the only way to be warned the instant a limit is broken is to set that
 * rule's tolerance to zero, which is the same lever the vehicle rules offer.
 *
 * Nothing here decides what the numbers mean. FaceReader measures the face and names
 * what is in the driver's hand; this class only asks three things of each of them -
 * whether it is out of bounds, how long it stayed that way, and which of the nine it
 * belongs to. That last one matters because four of the nine are the same square
 * centimetre of a driver: a hand at the mouth is a cigarette, a sandwich or a phone,
 * an open mouth is a yawn or somebody talking, and a lens that shows nothing is a
 * covered camera or a driver who has leant forward. Each frame therefore picks one
 * reading and refuses the others, in the order below, which is why a driver eating a
 * sandwich is recorded as eating and not as smoking.
 */
class Rules {

    static final String[] ORDER = {Alerts.FATIGUE, Alerts.DISTRACTED, Alerts.PHONE, Alerts.YAWN,
            Alerts.TALK, Alerts.EAT, Alerts.DRINK, Alerts.SMOKE, Alerts.BLOCKED};
    // One minute between the sound for the same rule, exactly as the page does it. See
    // notice() for why the sound is said with the row rather than with the frame.
    private static final long REPEAT_MS = 60000;
    // How long a face that was just in view still holds its cover reading, used only
    // so a phone raised in front of the face is not also reported as a blocked lens.
    private static final long COVER_FRESH_MS = 20000;
    // The share of the lower face that has to be covered before the object in front of
    // it is treated as the reason the face disappeared.
    private static final double COVER_OWN = 35;
    // How long a rule may see nothing wrong before its breach is written off. The
    // camera is looked at five times a second and a reading is a measurement of one
    // picture, so a head turned away for the whole tolerance can give one clean frame
    // in the middle of it - and a breach that starts again from nothing on every such
    // frame never reaches its tolerance however long the driver keeps it up. This is
    // the window that survives that flicker; it is not long enough to let a driver
    // who really has recovered keep an event open.
    private static final long GAP_MS = 1200;
    // The last second, which is what every "how much of the time" reading in this file
    // is measured over. One second is also the shortest thing a five-times-a-second
    // camera can answer about with any confidence: five pictures, and a fifth of them
    // is the smallest share of the time the page can be told.
    private static final long WIN_MS = 1000;
    // Talking is a rate rather than a share, and two seconds is the shortest stretch
    // over which "he is moving his mouth again and again" can be told from "he opened
    // it once": a rate needs more than one movement in it to be a rate at all.
    private static final long TALK_WIN = 2000;
    // The number of samples kept for each rule: eight seconds of a five-times-a-second
    // camera, two and a half of a fifteen-times-a-second one, and more than enough for
    // the longest window asked of it above.
    private static final int CAP = 40;

    /**
     * The mouth's opening, as a percentage of the width of the mouth, at which the page
     * stops calling it somebody talking and starts calling it a yawn.
     *
     * Both rules read the same measurement and they must not both answer for one frame,
     * or a long conversation writes two events about the same second. This split is
     * fixed rather than settable because it is not a threshold of tolerance - nobody
     * argues about how wide a yawn is - and because the two rules' own thresholds are
     * both meant to sit this side of it and would otherwise have to chase each other.
     */
    private static final double MOUTH_WIDE = 14;
    /**
     * The least opening that is a mouth moving at all, and half that for the level it
     * has to fall back to before the next opening counts as another one. The two
     * numbers are deliberately different: a measurement that wobbles across a single
     * level would count a hundred movements a minute out of one long open mouth.
     */
    private static final double MOUTH_PART = 3;
    /** The share of the strip over the lips that has to be this driver's own skin. */
    private static final double LIP_SKIN = 50;

    /** Where the phone is, as this frame saw it. {@link #AT_NONE} when it did not see one. */
    private static final int AT_NONE = 0, AT_FRONT = 1, AT_EAR_L = 2, AT_EAR_R = 3;
    /**
     * In the picture a front lens gives - rotated upright, as this app always hands it
     * over, and never mirrored, because mirroring is something a preview display does
     * and not something an analysis frame is - the driver's left side is on the right
     * of the picture. It is worth naming once, in the one place, because getting it the
     * other way round would tell an administrator that a man driving with a phone on
     * his right ear had it on his left.
     */
    private static final boolean IMAGE_RIGHT_IS_DRIVERS_LEFT = true;

    /** The open breach of one rule, and the id its row and its clip will share. */
    private static class Br {
        String cid;
        long start;
        long acc;      // the time actually spent out of bounds, gaps left out
        long last;     // the last frame that counted towards acc
        long gapAt;    // when the reading first came back inside the limit, 0 while out
        double peak;
        int why;
        boolean row;
        // which side of his head the phone was on while this breach ran, counted frame
        // by frame so the answer is the driver's own rather than the last picture's
        int front, earL, earR;
    }

    /**
     * The last few seconds of one measurement, kept so that a rule can be asked "how
     * much of the time" and "how often" rather than "what did the last picture say".
     *
     * The samples older than the window are passed over when they are read rather than
     * swept out when they age, because the only writer is one frame at a time on one
     * thread and a sweep would be a second thing to get wrong. A phone that stalls for
     * a second therefore answers nothing at all for that second rather than a false
     * amount - the same answer the eye rule has always given.
     */
    private static class Win {
        private final long[] t = new long[CAP];
        private final double[] v = new double[CAP];
        private final boolean[] f = new boolean[CAP];
        private int p, n;

        void add(long now, double value, boolean flag) {
            t[p] = now;
            v[p] = value;
            f[p] = flag;
            p = (p + 1) % CAP;
            if (n < CAP) n++;
        }

        void clear() {
            p = 0;
            n = 0;
        }

        /** The oldest sample still held, or -1 when nothing has been put in yet. */
        private int oldest() {
            return n == 0 ? -1 : (p - n + CAP) % CAP;
        }

        double duty(long now, long ms) {
            int first = oldest();
            if (first < 0) return 0;
            int seen = 0, hit = 0;
            for (int i = 0; i < n; i++) {
                int j = (first + i) % CAP;
                if (now - t[j] > ms) continue;
                seen++;
                if (f[j]) hit++;
            }
            return seen == 0 ? 0 : (double) hit / seen;
        }

        double peak(long now, long ms) {
            int first = oldest();
            if (first < 0) return 0;
            double best = 0;
            for (int i = 0; i < n; i++) {
                int j = (first + i) % CAP;
                if (now - t[j] > ms) continue;
                if (v[j] > best) best = v[j];
            }
            return best;
        }

        /** How many times the flag came up inside the window, once per movement. */
        int edges(long now, long ms) {
            int first = oldest();
            if (first < 0) return 0;
            int k = 0;
            boolean was = false, have = false;
            for (int i = 0; i < n; i++) {
                int j = (first + i) % CAP;
                if (now - t[j] > ms) continue;
                if (f[j] && (!have || !was)) k++;
                was = f[j];
                have = true;
            }
            return k;
        }
    }

    private final DmsService svc;
    private final Map<String, Br> open = new HashMap<>();
    private final long[] lastNotice = new long[ORDER.length];

    // The eyes: the last second of closure, which is how the page defines this threshold.
    private final Win shutWin = new Win();
    // The mouth: its opening above this driver's rest, and whether it is open at all
    // right now with the level to fall back to before the next movement counts.
    private final Win mouthWin = new Win();
    private boolean mouthOpen;
    // The four that need to know where a thing is rather than how open a mouth is.
    private final Win phoneWin = new Win(), foodWin = new Win(), drinkWin = new Win(), handWin = new Win();
    private double lastCover;
    private long lastCoverAt, lastPhoneAt;

    Rules(DmsService s) {
        svc = s;
    }

    /** Trip start and end: an unfinished breach belongs to the trip that ended. */
    void reset() {
        open.clear();
        lastCover = 0;
        lastCoverAt = 0;
        lastPhoneAt = 0;
        mouthOpen = false;
        shutWin.clear();
        mouthWin.clear();
        phoneWin.clear();
        foodWin.clear();
        drinkWin.clear();
        handWin.clear();
        for (int i = 0; i < lastNotice.length; i++) lastNotice[i] = 0;
    }

    /**
     * Whether any rule the page has switched on needs to know what the driver is
     * holding. The phone is included although it has a colour answer: a named phone at
     * an ear says far more than a patch of skin-coloured pixels below a nose, and the
     * second model costs about eight milliseconds of a five-times-a-second camera.
     */
    static boolean needsObjects(Config cfg) {
        return cfg.rule(Alerts.PHONE).on || cfg.rule(Alerts.EAT).on
                || cfg.rule(Alerts.DRINK).on || cfg.rule(Alerts.SMOKE).on;
    }

    /** One measured frame, at the speed the vehicle was going when it was taken. */
    void frame(FaceReader.Reading r, double kmh, Config cfg) {
        long now = r.at;
        if (r.face) {
            lastCover = r.cover;
            lastCoverAt = now;
        }
        shutWin.add(now, 0, r.face && r.shut);
        double perclos = 100 * shutWin.duty(now, WIN_MS);

        // The mouth, once, for the three rules that read it. Everything about it is a
        // percentage of the width of this driver's own mouth, so it means the same at
        // any distance from the lens.
        double mouth = 100 * r.parted;
        if (r.face) {
            if (!mouthOpen && mouth >= MOUTH_PART) mouthOpen = true;
            else if (mouthOpen && mouth < MOUTH_PART / 2) mouthOpen = false;
        } else mouthOpen = false;
        mouthWin.add(now, r.face ? mouth : 0, mouthOpen);
        double movements = 60000.0 * mouthWin.edges(now, TALK_WIN) / TALK_WIN;

        // The three things a hand can hold up to a face, decided here once so that the
        // rules below cannot each claim the same frame for themselves.
        int where = r.face ? phoneWhere(r) : AT_NONE;
        if (where != AT_NONE) lastPhoneAt = now;
        boolean food = r.face && r.seeing && atMouth(r.food, r);
        boolean drink = r.face && r.seeing && atMouth(r.drink, r);
        // A hand at the mouth that is not holding anything the model can name, over a
        // mouth that is not open wide enough to be talking or yawning. The last of the
        // four and the one that is left over on purpose.
        boolean hand = r.face && r.seeing && !food && !drink && where == AT_NONE
                && mouth < MOUTH_WIDE && 100 * r.lipSkin >= LIP_SKIN;
        phoneWin.add(now, 0, where != AT_NONE);
        foodWin.add(now, 0, food);
        drinkWin.add(now, 0, drink);
        handWin.add(now, 0, hand);

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
                if (r.seeing) {
                    // a phone at an ear does not cover the face at all, so the thing
                    // that can be counted here is how much of the last second it was up
                    val = 100 * phoneWin.duty(now, WIN_MS);
                    bad = rule.armed(kmh) && r.face && val >= rule.threshold;
                } else {
                    // no model: the colour of the lower face is all there is, and it is
                    // what this rule did before the camera was asked to name anything
                    val = r.cover * 100;
                    bad = rule.armed(kmh) && r.face && val >= rule.threshold;
                }
            } else if (type.equals(Alerts.YAWN)) {
                // the split keeps a conversation out of this rule however the page is
                // set, because both rules read the same measurement of the same mouth
                val = mouth;
                bad = rule.armed(kmh) && r.face && mouth >= MOUTH_WIDE && mouth >= rule.threshold;
            } else if (type.equals(Alerts.TALK)) {
                // every movement of this stretch has to be below the yawn level, which
                // is what stops one long open mouth reading as a fast talker
                val = movements;
                bad = rule.armed(kmh) && r.face && mouthOpen && mouthWin.peak(now, TALK_WIN) < MOUTH_WIDE
                        && val >= rule.threshold;
            } else if (type.equals(Alerts.EAT)) {
                val = 100 * foodWin.duty(now, WIN_MS);
                bad = rule.armed(kmh) && val >= rule.threshold;
            } else if (type.equals(Alerts.DRINK)) {
                val = 100 * drinkWin.duty(now, WIN_MS);
                bad = rule.armed(kmh) && val >= rule.threshold;
            } else if (type.equals(Alerts.SMOKE)) {
                val = 100 * handWin.duty(now, WIN_MS);
                bad = rule.armed(kmh) && val >= rule.threshold;
            } else {
                boolean hidden = fresh(now, lastCoverAt, COVER_FRESH_MS) && lastCover * 100 >= COVER_OWN;
                // a phone raised to an ear sits over the lens for the same reason a
                // hand does, and a driver on the telephone is not a driver who has
                // put his hand over the camera
                bad = rule.armed(kmh) && r.blocked && !hidden && !fresh(now, lastPhoneAt, COVER_FRESH_MS);
                val = 0;
            }
            if (bad) breach(type, rule, now, val, r.why, where);
            else recover(type, rule, now);
        }
    }

    /** True when this frame is recent enough for its reading still to be believed. */
    private static boolean fresh(long now, long at, long ms) {
        return at != 0 && now - at < ms;
    }

    // ---- where the thing in the driver's hand is

    /**
     * Whether the phone the model named is against this driver rather than lying on the
     * seat beside him, and which side of his head it is on if so.
     *
     * Every distance is a multiple of the width of his own face, which is the only unit
     * two handsets two feet apart and six inches apart agree on. The bands are the ones
     * a face gives: an ear is further out than the outer corner of the eye and about at
     * the height of it, and a phone held up to read is anywhere across the face between
     * the eyes and a little below the chin.
     */
    private static int phoneWhere(FaceReader.Reading r) {
        if (!r.seeing || r.phone == null || r.faceW < 6) return AT_NONE;
        FaceReader.Box b = r.phone;
        if (b.w > r.faceW * 1.8 || b.h > r.faceW * 2.6) return AT_NONE;
        double dx = (b.cx - r.faceCx) / r.faceW, ax = Math.abs(dx);
        if (ax >= 0.62 && ax <= 1.15 && b.cy >= r.eyeCy - 0.40 * r.faceW
                && b.cy <= r.eyeCy + 0.55 * r.faceW) {
            boolean onPictureRight = dx > 0;
            if (onPictureRight == IMAGE_RIGHT_IS_DRIVERS_LEFT) return AT_EAR_L;
            return AT_EAR_R;
        }
        if (ax <= 0.62 && b.cy >= r.eyeCy - 0.25 * r.faceW && b.cy <= r.mouthCy + 0.80 * r.faceW)
            return AT_FRONT;
        return AT_NONE;
    }

    /** Whether the thing the model named is at this driver's mouth rather than by the wheel. */
    private static boolean atMouth(FaceReader.Box b, FaceReader.Reading r) {
        if (b == null || r.faceW < 6) return false;
        if (b.w > r.faceW * 1.8 || b.h > r.faceW * 2.6) return false;
        double dx = Math.abs(b.cx - r.mouthCx) / r.faceW;
        return dx <= 0.75 && b.cy >= r.noseCy - 0.20 * r.faceW
                && b.cy <= r.mouthCy + 0.45 * r.faceW;
    }

    /** True while a breach of any rule is being watched, which is what the page paints. */
    boolean active() {
        return !open.isEmpty();
    }

    // ---- the breach machine

    private void breach(String type, Config.Rule rule, long now, double val, int why, int where) {
        Br b = open.get(type);
        if (b == null) {
            b = new Br();
            b.start = now;
            b.last = now;
            b.peak = val;
            b.why = why;
            open.put(type, b);
        }
        // counted rather than remembered from the last picture: a driver on the
        // telephone moves the handset about his ear, and the row has to say the side
        // it was on for most of the breach, not the side it happened to be at the
        // instant the tolerance ran out
        if (where == AT_FRONT) b.front++;
        else if (where == AT_EAR_L) b.earL++;
        else if (where == AT_EAR_R) b.earR++;
        if (val > b.peak) {
            b.peak = val;
            b.why = why;
        }
        if (b.start == now) {
            // a rule with no tolerance is a rule that answers on the first bad frame,
            // so this is the one case where the warning is also immediate
            if (rule.holdS <= 0) write(type, rule, b, now);
            return;
        }
        // The time spent back inside the limit is never added to the breach: a frame
        // that returns from a quiet stretch only starts counting again from itself.
        if (b.gapAt != 0) b.gapAt = 0;
        else b.acc += Math.max(0, now - b.last);
        b.last = now;
        if (!b.row && b.acc / 1000.0 >= rule.holdS) write(type, rule, b, now);
    }

    /**
     * The sound and the banner for a breach that has just been written.
     *
     * A breach of the same rule one minute later stays quiet, exactly as the page does
     * it: a driver who is still turning his head must not be buzzed once a second, and
     * the row for the second breach is written all the same.
     */
    private void notice(String type, Config.Rule rule, Br b, long now) {
        int i = idx(type);
        if (now - lastNotice[i] < REPEAT_MS) return;
        lastNotice[i] = now;
        svc.announced(type, shortText(type, rule, b.peak, b.why, b), b.peak, rule);
    }

    private void recover(String type, Config.Rule rule, long now) {
        Br b = open.get(type);
        if (b == null) return;
        if (b.gapAt == 0) {
            b.gapAt = now;
            return;
        }
        if (now - b.gapAt < GAP_MS) return;
        // longer than a flicker of the same reading: the episode is over, and its row
        // closes at the last frame that was actually out of bounds
        open.remove(type);
        if (b.row) close(type, rule, b, Math.max(b.last, b.start));
    }

    /**
     * The one moment a breach becomes something the fleet can see. The row opens with
     * the readings of the moment the limit was first broken, the clip named after it
     * starts here - the seconds of picture before that moment are already in the ring,
     * which is the only reason they exist - and the driver is warned. All three are
     * decided by this single call, so nothing the driver hears can fail to reach the
     * dashboard and nothing on the dashboard can be missing the picture it belongs to.
     */
    private void write(String type, Config.Rule rule, Br b, long now) {
        Config cfg = svc.cfg();
        b.row = true;
        b.cid = UUID.randomUUID().toString();
        JSONObject p = svc.row(type, b.cid, b.start, value(type, b.peak),
                fullText(type, rule, b, now, false));
        // the record-video switch of this rule on the Configuration page. A rule that is
        // not watched on film still warns the driver and still keeps its row; only the
        // seconds of picture are withheld, which is what an operator wants in a cab he
        // has agreed with his drivers to record, and what he does not want in another.
        boolean film = rule.clip;
        if (film) svc.clip().begin(b.cid, cfg.preFrames(), cfg.postS, caption(type, rule, b));
        JSONObject item = new JSONObject();
        try {
            item.put("op", "ev").put("cid", b.cid)
                    .put("clip", film ? b.cid + ".mp4" : null).put("p", p);
        } catch (Exception ignore) {
            // as above
        }
        svc.outbox().add(item);
        svc.pageEvent();
        notice(type, rule, b, now);
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
        if (type.equals(Alerts.TALK)) return (double) Math.round(peak);
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

    /** Which ear the phone was on, counted over the whole breach rather than the last frame. */
    private static String phoneSide(Br b) {
        if (b.earL > b.earR && b.earL > b.front) return "at his left ear, on a call";
        if (b.earR > b.earL && b.earR > b.front) return "at his right ear, on a call";
        if (b.front >= Math.max(b.earL, b.earR) && b.front > 0) return "in front of his face, being read";
        // a breach that ran on the colour of his lower face alone: the phone is over
        // his features, which is all the picture can say without the second model
        return "in front of his face";
    }

    /** What was measured, said once for the warning, the row and the clip alike. */
    private static String measured(String type, double peak, int why, Br b) {
        if (type.equals(Alerts.FATIGUE)) return "Eyes shut " + pct(peak) + " of the last second";
        if (type.equals(Alerts.DISTRACTED))
            return "Head turned " + deg(peak) + " degrees off the road";
        if (type.equals(Alerts.PHONE)) return "Phone " + phoneSide(b) + " " + pct(peak) + " of the last second";
        if (type.equals(Alerts.YAWN))
            return "Mouth open " + pct(peak) + " of the width of his mouth";
        if (type.equals(Alerts.TALK)) return "Mouth moving " + deg(peak) + " times a minute";
        if (type.equals(Alerts.EAT)) return "Food at the mouth " + pct(peak) + " of the last second";
        if (type.equals(Alerts.DRINK)) return "A cup or bottle at the mouth " + pct(peak) + " of the last second";
        if (type.equals(Alerts.SMOKE)) return "A hand at the mouth " + pct(peak) + " of the last second";
        return "Driver camera: " + blockedWhy(why);
    }

    /** The limit it was measured against, in the words a sentence wants. */
    private static String against(String type, Config.Rule r) {
        if (type.equals(Alerts.DISTRACTED)) return "a " + deg(r.threshold) + " degree limit";
        if (type.equals(Alerts.TALK)) return "a " + deg(r.threshold) + " movements a minute limit";
        return "a " + pct(r.threshold) + " limit";
    }

    /** The same limit in the few words a line across a picture can carry. */
    private static String limitText(String type, Config.Rule r) {
        if (type.equals(Alerts.DISTRACTED)) return deg(r.threshold) + " degrees";
        if (type.equals(Alerts.TALK)) return deg(r.threshold) + " a minute";
        return pct(r.threshold);
    }

    /** The warning the driver hears, and the first half of the line kept in the row. */
    private static String shortText(String type, Config.Rule r, double peak, int why, Br b) {
        String m = measured(type, peak, why, b);
        if (type.equals(Alerts.BLOCKED)) return m;
        return m + " against " + against(type, r);
    }

    /**
     * The one line burned across the clip of this alert.
     *
     * One line because the picture is what an administrator came to look at: four of
     * them at the old size covered nearly half of a frame only 320 pixels wide. Short
     * on purpose too, and said again rather than copied from the row - a sentence that
     * fits a database column does not fit a film of a man's face. Nothing is lost: the
     * driver, the vehicle and the moment are the name the file is stored under and the
     * reading the dashboard prints beside it, and the seconds from the alert are the
     * clip's own, because it is the only thing here that knows which picture is which.
     */
    private String[] caption(String type, Config.Rule r, Br b) {
        String line = measured(type, b.peak, b.why, b);
        if (!type.equals(Alerts.BLOCKED)) line += " · limit " + limitText(type, r);
        return new String[]{Alerts.title(type) + " · " + line};
    }

    /** The whole line of the row: the reading, the limit, the tolerance and the length. */
    private static String fullText(String type, Config.Rule r, Br b, long now, boolean closed) {
        String head = shortText(type, r, b.peak, b.why, b);
        long held = Math.max(0, now - b.start);
        String tail = r.holdS > 0 ? " · tolerance " + deg(r.holdS) + " s" : "";
        if (closed) return head + " · lasted " + hms(held) + tail;
        return head + " · held " + secs(held) + tail;
    }

    private static int idx(String type) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(type)) return i;
        return 0;
    }
}
