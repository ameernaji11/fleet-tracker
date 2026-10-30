package com.fleet.dms;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;

import java.util.Arrays;
import java.util.List;

/**
 * What the picture of the driver says, as four plain readings.
 *
 * The face model runs on the handset and is never asked to name an object: it
 * returns 478 points on the face, and everything below is measured from those points
 * and from the colours inside the small area they describe. That is why the numbers
 * are ratios of the driver's own face rather than absolute sizes - a phone mounted
 * close, a tall driver, a night cab and a sunlit one all give the same reading for
 * the same eyelid.
 *
 * The model itself is a 3.7 MB file the build downloads into this module's assets.
 * If it is not there, nothing here throws: the reader says why once, the camera keeps
 * recording, and the page shows that the eyes of the system are not fitted.
 */
class FaceReader {

    /** The asset name the build puts in place; see .github/workflows/build-apk.yml. */
    static final String MODEL = "face_landmarker.task";

    // FaceMesh point numbers. Two eyelid pairs per eye are measured and the smaller
    // gap is used, so a pair whose centre point sits a little off the lid still
    // closes to the same answer.
    private static final int EYE_R_OUT = 33, EYE_R_IN = 133, EYE_R_UP_A = 159, EYE_R_LO_A = 145,
            EYE_R_UP_B = 160, EYE_R_LO_B = 158;
    private static final int EYE_L_IN = 362, EYE_L_OUT = 263, EYE_L_UP_A = 386, EYE_L_LO_A = 374,
            EYE_L_UP_B = 385, EYE_L_LO_B = 384;
    private static final int NOSE_TIP = 1, NOSE_BASE = 198, CHIN = 152,
            MOUTH_R = 61, MOUTH_L = 291, FOREHEAD = 10, CHEEK_R = 234, CHEEK_L = 454;
    private static final int LAST_INDEX = CHEEK_L;

    /** Why the camera is judged blocked, so the event can say which of them it was. */
    static final int WHY_DARK = 1, WHY_FLAT = 2, WHY_FROZEN = 4, WHY_NOFACE = 8;

    /** The answer for one frame. What to do about it is Rules' decision, not this one. */
    static class Reading {
        long at;
        boolean face;     // a face was found at all
        boolean shut;     // these eyes, at this moment, are closed
        boolean blocked;  // the lens is covered, or the picture has stopped changing
        int why;          // the WHY_ bits that say so
        double open;      // eyelid gap over eye width
        double ref;       // the same ratio for this driver's eyes when they are open
        double yaw;       // degrees the head is turned away from the road, signed
        double cover;     // 0..1, how much of the lower face is not this driver's skin
    }

    private final Context ctx;
    private FaceLandmarker lm;
    private String error;
    private boolean tried;

    private int[] px, prev;
    private int pw, ph;
    private boolean havePrev;
    private double lastAt = -1;

    // The two things this reader learns about the driver in front of the lens.
    private double eyeRef = -1;
    private double coverBase = -1;

    FaceReader(Context c) {
        ctx = c;
    }

    String error() {
        return error;
    }

    boolean ready() {
        return lm != null;
    }

    /**
     * Load the model on first use. It is asked for from the camera callback thread
     * rather than at start-up, because a few hundred milliseconds of model loading
     * must not delay a trip that has already begun.
     */
    private void ensure() {
        if (tried) return;
        tried = true;
        try {
            BaseOptions bo = BaseOptions.builder().setModelAssetPath(MODEL).build();
            FaceLandmarker.FaceLandmarkerOptions o = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(bo)
                    // IMAGE mode answers the frame it is handed, on the calling thread.
                    // This reader is fed one frame at a time by a single thread already,
                    // so the streaming and video modes have nothing to gain here, and
                    // their timestamps and listeners would only be extra ways to fail.
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(0.4f)
                    .setMinFacePresenceConfidence(0.4f)
                    .setMinTrackingConfidence(0.4f)
                    .build();
            lm = FaceLandmarker.createFromOptions(ctx, o);
        } catch (Throwable t) {
            error = describe(t);
            lm = null;
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    /** Release the native graph. Called when the trip ends, and never on a camera frame. */
    void close() {
        if (lm != null) {
            try {
                lm.close();
            } catch (Exception ignore) {
                // a graph that refuses to close is already gone
            }
        }
        lm = null;
        tried = false;
        error = null;
        eyeRef = -1;
        coverBase = -1;
        havePrev = false;
        lastAt = -1;
    }

    /**
     * Judge one upright frame. The bitmap belongs to the camera thread that calls
     * this; it is read here and never kept.
     */
    Reading read(Bitmap bmp, long atMs) {
        Reading r = new Reading();
        r.at = atMs;
        double dt = lastAt < 0 ? 0.2 : Math.max(0.02, Math.min(2.0, (atMs - lastAt) / 1000.0));
        lastAt = atMs;

        int w = bmp.getWidth(), h = bmp.getHeight();
        grabPixels(bmp, w, h);

        // Whole-picture numbers first: a lens under a hand is dark, has no texture and
        // stops changing. All three are measurable without knowing anything about the
        // driver, which is exactly the point of this rule.
        lensBlocked(r);

        ensure();
        if (lm != null) {
            List<List<NormalizedLandmark>> got;
            try {
                MPImage img = new BitmapImageBuilder(bmp).build();
                FaceLandmarkerResult res = lm.detect(img);
                got = res == null ? null : res.faceLandmarks();
            } catch (Throwable t) {
                // one frame the graph refused is one frame with no face in it, and the
                // camera-blocked rule is timed, so a single loss changes nothing. The
                // reason is not recorded as this reader's error: the model did load,
                // and a lost frame must not look like a missing model on the page.
                got = null;
            }
            if (got != null && !got.isEmpty()) {
                List<NormalizedLandmark> f = got.get(0);
                if (f != null && f.size() > LAST_INDEX) {
                    r.face = true;
                    measure(f, w, h, dt, r);
                }
            }
        }
        // An empty view is the common way a lens ends up covered. It is only counted
        // when the model is actually fitted: a build without it would otherwise report
        // a blocked camera for the whole trip, which is a missing file, not a warning
        // the fleet needs.
        if (!r.face && lm != null) {
            r.why |= WHY_NOFACE;
            r.blocked = true;
        }
        return r;
    }

    // ---- pixels

    private void grabPixels(Bitmap b, int w, int h) {
        if (px == null || pw != w || ph != h) {
            px = new int[w * h];
            prev = new int[w * h];
            pw = w;
            ph = h;
            havePrev = false;
        }
        b.getPixels(px, 0, w, 0, 0, w, h);
    }

    private static int luma(int c) {
        return (((c >> 16) & 255) * 77 + ((c >> 8) & 255) * 150 + (c & 255) * 29) >> 8;
    }

    /**
     * Covered, dark, or stopped. "Stopped changing" is set so low on purpose: a cab is
     * never perfectly still, and this only becomes an alert at all once the rule's own
     * tolerance seconds have passed with nothing moving in the frame.
     */
    private void lensBlocked(Reading r) {
        long sum = 0, sq = 0, diff = 0, n = 0;
        int step = Math.max(1, pw / 80);
        for (int y = 0; y < ph; y += step) {
            int row = y * pw;
            for (int x = 0; x < pw; x += step) {
                int l = luma(px[row + x]);
                sum += l;
                sq += (long) l * l;
                if (havePrev) diff += Math.abs(l - prev[row + x]);
                n++;
            }
        }
        if (n == 0) return;
        double mean = (double) sum / n;
        double std = Math.sqrt(Math.max(0, (double) sq / n - mean * mean));
        double moved = havePrev ? diff / n : 99;
        System.arraycopy(px, 0, prev, 0, px.length);
        havePrev = true;
        if (mean < 14) r.why |= WHY_DARK;
        if (std < 4.5) r.why |= WHY_FLAT;
        if (moved < 0.8) r.why |= WHY_FROZEN;
        r.blocked = r.why != 0;
    }

    // ---- face geometry

    private void measure(List<NormalizedLandmark> f, int w, int h, double dt, Reading r) {
        double faceW = gap(f, EYE_R_OUT, EYE_L_OUT, w);
        if (faceW < 0.02) return;

        // Eyes: the gap between the lids over the width of the eye. A closed eye is a
        // fraction of an open one, and how small that fraction is for this driver is
        // learned from him. The reference follows a wider opening at once and comes
        // back down at two percent a second, so a few minutes of hard blinking does
        // not leave him measured against somebody else's eyes.
        double open = Math.min(eyeGap(f, w, EYE_R_OUT, EYE_R_IN, EYE_R_UP_A, EYE_R_LO_A, EYE_R_UP_B, EYE_R_LO_B),
                eyeGap(f, w, EYE_L_IN, EYE_L_OUT, EYE_L_UP_A, EYE_L_LO_A, EYE_L_UP_B, EYE_L_LO_B));
        if (eyeRef < 0) eyeRef = Math.max(0.12, open);
        else if (open > eyeRef) eyeRef = open;
        else eyeRef += (open - eyeRef) * Math.min(1, 0.02 * dt);
        eyeRef = Config.clamp(eyeRef, 0.10, 0.42);
        r.open = open;
        r.ref = eyeRef;
        // one eye shut is already a problem, and winking is not something a cab does
        r.shut = Config.clamp((eyeRef - open) / eyeRef, 0, 1) >= 0.55;

        // Head turn. The nose travels across the line of the eyes as the head turns,
        // and half a face width of travel is about where the nose would reach the ear,
        // which is as far as a person can go. So the full travel of this ratio stands
        // for 60 degrees, and the page tunes the number it is compared against.
        double midX = (at(f, EYE_R_OUT).x() + at(f, EYE_L_OUT).x()) / 2;
        double noseDx = at(f, NOSE_TIP).x() - midX;
        double eyeW = Math.max(0.05, at(f, EYE_L_OUT).x() - at(f, EYE_R_OUT).x());
        r.yaw = 60 * Config.clamp(noseDx / (eyeW * 0.5), -1, 1);

        // Something in front of the lower face. The colour of this driver's own skin is
        // read from his forehead and cheeks and the box from the base of the nose to
        // the chin is compared against it. A hand is skin; a phone is not. This is the
        // shape guess the Configuration page warns about, and the rule most likely to
        // need tuning to a particular cab and light.
        r.cover = coverage(f, w, h, faceW, dt);
    }

    private static NormalizedLandmark at(List<NormalizedLandmark> f, int i) {
        return f.get(i);
    }

    /** Distance between two points, in fractions of the picture width. */
    private static double gap(List<NormalizedLandmark> f, int a, int b, int w) {
        NormalizedLandmark pa = f.get(a), pb = f.get(b);
        // x is a fraction of the width and y of the height, so y is put back into the
        // same unit before the two are compared
        double dx = (pa.x() - pb.x()) * w, dy = (pa.y() - pb.y()) * w;
        return Math.hypot(dx, dy) / w;
    }

    private double eyeGap(List<NormalizedLandmark> f, int w, int outer, int inner,
                          int upA, int loA, int upB, int loB) {
        double width = gap(f, outer, inner, w);
        if (width < 0.01) return 1;
        return Math.max(0, Math.min(gap(f, upA, loA, w), gap(f, upB, loB, w)) / width);
    }

    /** The skin this driver presents, as one colour, from three places on his face. */
    private int skinColour(List<NormalizedLandmark> f, int w, int h, double faceW) {
        int[] rs = new int[64], gs = new int[64], bs = new int[64];
        int n = 0;
        int[] centres = {FOREHEAD, CHEEK_R, CHEEK_L};
        int rad = Math.max(1, (int) (faceW * 0.05 * w));
        int jump = Math.max(1, rad / 3);
        for (int c : centres) {
            NormalizedLandmark p = at(f, c);
            int cx = (int) (p.x() * w), cy = (int) (p.y() * h);
            int taken = 0;
            for (int dy = -rad; dy <= rad && taken < 20; dy += jump) {
                for (int dx = -rad; dx <= rad && taken < 20; dx += jump) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= w || y >= h || n >= rs.length) continue;
                    int col = px[y * w + x];
                    rs[n] = (col >> 16) & 255;
                    gs[n] = (col >> 8) & 255;
                    bs[n] = col & 255;
                    n++;
                    taken++;
                }
            }
        }
        if (n < 8) return -1;
        // the middle of what was found, so one bad spot - a cap, a shadow, a strap -
        // cannot decide the colour on its own
        Arrays.sort(rs, 0, n);
        Arrays.sort(gs, 0, n);
        Arrays.sort(bs, 0, n);
        return (rs[n / 2] << 16) | (gs[n / 2] << 8) | bs[n / 2];
    }

    private double coverage(List<NormalizedLandmark> f, int w, int h, double faceW, double dt) {
        int skin = skinColour(f, w, h, faceW);
        if (skin < 0) return 0;
        double x0 = Math.min(at(f, MOUTH_R).x(), at(f, MOUTH_L).x());
        double x1 = Math.max(at(f, MOUTH_R).x(), at(f, MOUTH_L).x());
        double pad = faceW * 0.18;
        double left = Math.max(0, x0 - pad), right = Math.min(1, x1 + pad);
        double top = at(f, NOSE_BASE).y() - faceW * 0.05;
        double bottom = Math.min(1, at(f, CHIN).y() + faceW * 0.12);
        if (bottom <= top || right <= left) return 0;

        int sx = Math.max(0, (int) (left * w)), sy = Math.max(0, (int) (top * h));
        int ex = Math.min(w, (int) (right * w)), ey = Math.min(h, (int) (bottom * h));
        if (ex <= sx || ey <= sy) return 0;
        int stepX = Math.max(1, (ex - sx) / 14), stepY = Math.max(1, (ey - sy) / 14);
        int sr = (skin >> 16) & 255, sg = (skin >> 8) & 255, sb = skin & 255;
        int n = 0, bad = 0;
        for (int y = sy; y < ey; y += stepY) {
            for (int x = sx; x < ex; x += stepX) {
                int c = px[y * w + x];
                // lips are darker and redder than a cheek by design, and so is a
                // shadow, so a point has to be a long way from skin to count
                int d = Math.abs(((c >> 16) & 255) - sr) + Math.abs(((c >> 8) & 255) - sg) + Math.abs((c & 255) - sb);
                if (d > 110) bad++;
                n++;
            }
        }
        if (n < 30) return 0;
        double raw = (double) bad / n;
        // every cab has a background behind the driver's chin, and it is the change
        // that matters. The baseline falls at once when the box is clearer than it has
        // been and climbs by half a percent a second, so a seat back or a jacket
        // settles instead of alarming while a phone held up for a whole call does not.
        if (coverBase < 0) coverBase = raw;
        else if (raw < coverBase) coverBase = raw;
        else coverBase = Math.min(0.5, coverBase + 0.005 * dt);
        return Config.clamp((raw - coverBase) / Math.max(0.08, 1 - coverBase), 0, 1);
    }
}
