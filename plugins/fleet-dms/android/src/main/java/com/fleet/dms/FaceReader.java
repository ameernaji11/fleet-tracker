package com.fleet.dms;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Detection;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector;
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetectorResult;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What the picture of the driver says, as plain readings.
 *
 * Two models run on the handset and neither is asked to name a rule: the face model
 * returns 478 points on the face and the object model names what the driver is holding,
 * and everything below is measured from those points and from the colours inside the
 * small areas they describe. That is why the numbers are ratios of the driver's own
 * face rather than absolute sizes - a phone mounted close, a tall driver, a night cab
 * and a sunlit one all give the same reading for the same eyelid.
 *
 * The two models are a 3.7 MB and a 4.6 MB file the build downloads into this module's
 * assets. If they are not there, nothing here throws: the reader says why once, the
 * camera keeps recording, and the page shows which of the eyes of the system are not
 * fitted - because a build without the object model can still watch the eyes and the
 * head, and only loses the rules that need to know what is in the driver's hand.
 */
class FaceReader {

    /** The asset names the build puts in place; see .github/workflows/build-apk.yml. */
    static final String MODEL = "face_landmarker.task";
    static final String OBJECT_MODEL = "efficientdet_lite0.tflite";

    // FaceMesh point numbers. Two eyelid pairs per eye are measured and the smaller
    // gap is used, so a pair whose centre point sits a little off the lid still
    // closes to the same answer.
    private static final int EYE_R_OUT = 33, EYE_R_IN = 133, EYE_R_UP_A = 159, EYE_R_LO_A = 145,
            EYE_R_UP_B = 160, EYE_R_LO_B = 158;
    private static final int EYE_L_IN = 362, EYE_L_OUT = 263, EYE_L_UP_A = 386, EYE_L_LO_A = 374,
            EYE_L_UP_B = 385, EYE_L_LO_B = 384;
    private static final int NOSE_TIP = 1, NOSE_BASE = 198, CHIN = 152,
            MOUTH_R = 61, MOUTH_L = 291, FOREHEAD = 10, CHEEK_R = 234, CHEEK_L = 454;
    // The lips: 82 and 87 are the middle of the inner edge of the upper and lower lip,
    // so their distance is the opening itself rather than the opening plus the lips,
    // and 13 and 14 are the same two points on the outer edge as a second opinion.
    private static final int LIP_UP = 82, LIP_LO = 87, LIP_UP_OUT = 13, LIP_LO_OUT = 14;
    private static final int LAST_INDEX = CHEEK_L;

    /**
     * The classes of the object model that mean something to a driver. Names first -
     * that is what the model file carries - and the class numbers of the same list as
     * the fallback, because a model whose label text did not travel in the file still
     * reports the same eighty numbers. Both come from one COCO list, so 67 is a cell
     * phone here for the same reason it is one there.
     */
    private static final String[] PHONE_N = {"cell phone"};
    private static final String[] DRINK_N = {"cup", "bottle", "wine glass"};
    private static final String[] FOOD_N = {"banana", "apple", "sandwich", "orange", "broccoli",
            "carrot", "hot dog", "pizza", "donut", "cake", "bowl"};
    private static final int[] PHONE_I = {67};
    private static final int[] DRINK_I = {41, 39, 40};
    private static final int[] FOOD_I = {46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 45};
    /** What {@link #kindOf} answers for a class this feature has no use for. */
    static final int NOTHING = 0, PHONE = 1, DRINK = 2, FOOD = 3;

    /** Why the camera is judged blocked, so the event can say which of them it was. */
    static final int WHY_DARK = 1, WHY_FLAT = 2, WHY_FROZEN = 4, WHY_NOFACE = 8;

    /**
     * One thing the object model named, in the pixels of the same upright picture the
     * face was measured from. Where it is and how big it is are only useful beside the
     * face, which is why the face's own points travel in the same reading.
     */
    static class Box {
        double cx, cy, w, h;
        int kind;

        Box(int kind, double cx, double cy, double w, double h) {
            this.kind = kind;
            this.cx = cx;
            this.cy = cy;
            this.w = w;
            this.h = h;
        }
    }

    /** The answer for one frame. What to do about it is Rules' decision, not this one. */
    static class Reading {
        long at;
        boolean face;     // a face was found at all
        boolean shut;     // these eyes, at this moment, are closed
        boolean blocked;  // the lens is covered, or the picture has stopped changing
        boolean seeing;   // the object model is fitted and answered this frame
        int why;          // the WHY_ bits that say so
        double open;      // eyelid gap over eye width
        double ref;       // the same ratio for this driver's eyes when they are open
        double yaw;       // degrees the head is turned away from the road, signed
        double cover;     // 0..1, how much of the lower face is not this driver's skin
        double mouth;     // the lips' opening over the width of the mouth
        double parted;    // the same opening above the level this driver rests at
        double lipSkin;   // 0..1 of the strip over the lips that is his own skin colour
        double lipDark;   // 0..1 of that strip that is far darker than his skin
        double faceCx, faceCy, faceW;   // the face, in the picture's own pixels
        double mouthCx, mouthCy, noseCy, eyeCy;
        Box phone, drink, food;         // whichever of them the model named, or null
    }

    private final Context ctx;
    private FaceLandmarker lm;
    private ObjectDetector od;
    private String error;
    private String odError;
    private boolean tried, odTried;
    // Whether the frame is worth asking a second model about. The service answers this
    // from the rules the page has switched on, so a fleet that watches only the eyes
    // never pays for naming what is in a hand. Written on the page's thread and read on
    // the camera's, which is why it is the one field here that is volatile.
    private volatile boolean wantObjects = true;

    private int[] px, prev;
    private int pw, ph;
    private boolean havePrev;
    private double lastAt = -1;

    // The three things this reader learns about the driver in front of the lens.
    private double eyeRef = -1;
    private double coverBase = -1;
    private double mouthRest = -1;
    // Set once, for the whole trip, if the object model names things but never says
    // where. The rules then fall back to measuring colour instead, and say so.
    private boolean boxesBroken;

    FaceReader(Context c) {
        ctx = c;
    }

    String error() {
        return error;
    }

    /** Why the object model is not answering, said separately from the face model. */
    String objectError() {
        return odError;
    }

    boolean ready() {
        return lm != null;
    }

    boolean seeing() {
        return od != null && !boxesBroken;
    }

    /**
     * Turn the second model on or off. It is loaded by the next frame that wants it
     * rather than here, because this is answered on the page's thread and a few
     * hundred milliseconds of model loading is not the page's business.
     */
    void wantObjects(boolean yes) {
        wantObjects = yes;
    }

    /**
     * Load the face model on first use. It is asked for from the camera callback thread
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

    /**
     * Load the object model on first use, the same way. Ten classes at a threshold of
     * half is deliberately generous: a driver's hand comes back as several candidates
     * and the one that matters is the phone in it rather than the best-scoring thing in
     * the cab, which is nearly always the man himself.
     */
    private void ensureObjects() {
        if (odTried || !wantObjects) return;
        odTried = true;
        try {
            BaseOptions bo = BaseOptions.builder().setModelAssetPath(OBJECT_MODEL).build();
            od = ObjectDetector.createFromOptions(ctx,
                    ObjectDetector.ObjectDetectorOptions.builder()
                            .setBaseOptions(bo)
                            .setRunningMode(RunningMode.IMAGE)
                            .setMaxResults(10)
                            .setScoreThreshold(0.45f)
                            .build());
        } catch (Throwable t) {
            odError = describe(t);
            od = null;
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    /** Release the native graphs. Called when the trip ends, and never on a camera frame. */
    void close() {
        closeOne(lm);
        lm = null;
        closeOne(od);
        od = null;
        tried = false;
        odTried = false;
        error = null;
        odError = null;
        boxesBroken = false;
        eyeRef = -1;
        coverBase = -1;
        mouthRest = -1;
        havePrev = false;
        lastAt = -1;
    }

    private static void closeOne(AutoCloseable c) {
        try {
            if (c != null) c.close();
        } catch (Exception ignore) {
            // a graph that refuses to close is already gone
        }
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
        ensureObjects();
        MPImage img = null;
        try {
            img = new BitmapImageBuilder(bmp).build();
        } catch (Throwable t) {
            // a picture the framework will not wrap is a frame with nothing in it; the
            // two models below are simply not asked
            img = null;
        }
        if (lm != null && img != null) {
            List<List<NormalizedLandmark>> got;
            try {
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
        // Asked after the face, not before: the face gives the scale every one of these
        // readings is compared against, and the two graphs share nothing but this frame.
        if (od != null && img != null) {
            try {
                objects(od.detect(img), w, h, r);
            } catch (Throwable t) {
                // as with the face: a refused frame names nothing in it, and the rules
                // that need a named object simply see none this time
            }
            r.seeing = !boxesBroken;
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
        double faceW = gap(f, EYE_R_OUT, EYE_L_OUT, w, h);
        if (faceW < 0.02) return;

        // The face's own landmarks handed back as pixels of this picture, so the boxes
        // the object model names can be compared with them. Distances here are taken in
        // the same unit the model uses - pixels of the width - and everything a rule
        // asks is divided by the width of this face, which is what makes the thresholds
        // the page sets mean the same thing in every cab and at every mounting.
        double xR = xpos(f, EYE_R_OUT, w), xL = xpos(f, EYE_L_OUT, w);
        r.faceW = Math.abs(xL - xR);
        r.faceCx = (xR + xL) / 2;
        r.faceCy = (ypos(f, EYE_R_OUT, h) + ypos(f, EYE_L_OUT, h) + ypos(f, CHIN, h)) / 3;
        r.eyeCy = (ypos(f, EYE_R_OUT, h) + ypos(f, EYE_L_OUT, h)) / 2;
        r.noseCy = ypos(f, NOSE_TIP, h);
        r.mouthCx = (xpos(f, MOUTH_R, w) + xpos(f, MOUTH_L, w)) / 2;
        r.mouthCy = (ypos(f, LIP_UP_OUT, h) + ypos(f, LIP_LO_OUT, h)) / 2;
        double mouthW = Math.max(1, gap(f, MOUTH_R, MOUTH_L, w, h) * w);

        // Eyes: the gap between the lids over the width of the eye. A closed eye is a
        // fraction of an open one, and how small that fraction is for this driver is
        // learned from him. The reference follows a wider opening at once and comes
        // back down at two percent a second, so a few minutes of hard blinking does
        // not leave him measured against somebody else's eyes.
        double open = Math.min(eyeGap(f, w, h, EYE_R_OUT, EYE_R_IN, EYE_R_UP_A, EYE_R_LO_A, EYE_R_UP_B, EYE_R_LO_B),
                eyeGap(f, w, h, EYE_L_IN, EYE_L_OUT, EYE_L_UP_A, EYE_L_LO_A, EYE_L_UP_B, EYE_L_LO_B));
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

        // The lips: the opening between them over the width of the mouth, which is the
        // same shape of number as the eyes and means the same thing in every face. The
        // inner pair is the opening; the outer pair is asked as well because it is the
        // one that survives a dark cab, and the thickness of two lips is taken back off
        // it so a closed mouth reads as closed rather than as a third of a yawn.
        double inner = gap(f, LIP_UP, LIP_LO, w, h) * w;
        double outer = gap(f, LIP_UP_OUT, LIP_LO_OUT, w, h) * w - mouthW * 0.18;
        r.mouth = Math.max(0, Math.max(inner, outer)) / mouthW;
        // the level this driver's mouth rests at, learned from him the same way
        if (mouthRest < 0) mouthRest = r.mouth;
        else if (r.mouth < mouthRest) mouthRest = r.mouth;
        else mouthRest = Math.min(0.14, mouthRest + 0.01 * dt);
        r.parted = Math.max(0, r.mouth - mouthRest);

        // What the face belongs to, read once and used twice: the coverage of the lower
        // face and the colour of the lips are both measured against this driver's own
        // skin rather than against somebody else's idea of what skin looks like.
        int skin = skinColour(f, w, h, faceW);

        // Something in front of the lower face. The colour of this driver's own skin is
        // read from his forehead and cheeks and the box from the base of the nose to
        // the chin is compared against it. A hand is skin; a phone is not. This is the
        // shape guess the Configuration page warns about, and the rule most likely to
        // need tuning to a particular cab and light.
        r.cover = coverage(skin, f, w, h, faceW, dt);
        lips(skin, f, w, h, mouthW, r);
    }

    private static double xpos(List<NormalizedLandmark> f, int i, int w) {
        return f.get(i).x() * w;
    }

    private static double ypos(List<NormalizedLandmark> f, int i, int h) {
        return f.get(i).y() * h;
    }

    private static NormalizedLandmark at(List<NormalizedLandmark> f, int i) {
        return f.get(i);
    }

    /** Distance between two points, in fractions of the picture width. */
    private static double gap(List<NormalizedLandmark> f, int a, int b, int w, int h) {
        NormalizedLandmark pa = f.get(a), pb = f.get(b);
        // x is a fraction of the width and y of the height, so each is put back into
        // pixels of its own axis before the two are compared. The old habit of scaling
        // both by the width cancels out of a ratio of two measurements taken the same
        // wrong way, which is why the eyes never showed it - but the width of a mouth
        // is horizontal and its opening is vertical, and a yawn has to be measured
        // against a number the page sets in the real world.
        double dx = (pa.x() - pb.x()) * w, dy = (pa.y() - pb.y()) * h;
        return Math.hypot(dx, dy) / w;
    }

    private double eyeGap(List<NormalizedLandmark> f, int w, int h, int outer, int inner,
                          int upA, int loA, int upB, int loB) {
        double width = gap(f, outer, inner, w, h);
        if (width < 0.01) return 1;
        return Math.max(0, Math.min(gap(f, upA, loA, w, h), gap(f, upB, loB, w, h)) / width);
    }

    /**
     * What the lips themselves are made of.
     *
     * Three states of this one small box are worth telling apart and all three change
     * its colour: an open mouth shows the dark inside of it, closed lips show a band
     * redder and darker than a cheek, and a hand laid over the mouth shows this
     * driver's own skin in a place his own skin should not be. That last one is the
     * only shape a cigarette, a sweet and a wiped mouth all have in common when the
     * object model cannot name the thing, which is why the rule that uses it is the one
     * the Configuration page tells the fleet to tune before it trusts.
     */
    private void lips(int skin, List<NormalizedLandmark> f, int w, int h, double mouthW, Reading r) {
        if (skin < 0 || mouthW < 6 || r.faceW < 6) return;
        double xa = xpos(f, MOUTH_R, w), xb = xpos(f, MOUTH_L, w);
        // the corners are pulled in so the cheeks at either end of a smile are not
        // counted as the lips, and the band is as tall as the opening plus a little
        double left = Math.min(xa, xb) + mouthW * 0.12, right = Math.max(xa, xb) - mouthW * 0.12;
        double mid = (ypos(f, LIP_UP, h) + ypos(f, LIP_LO, h)) / 2;
        double half = Math.max(2, Math.abs(ypos(f, LIP_UP, h) - ypos(f, LIP_LO, h)) / 2 + mouthW * 0.08);
        int sx = Config.clamp((int) left, 0, w - 1), ex = Config.clamp((int) right, sx + 1, w);
        int sy = Config.clamp((int) (mid - half), 0, h - 1), ey = Config.clamp((int) (mid + half), sy + 1, h);
        if (ex <= sx || ey <= sy) return;
        int stepX = Math.max(1, (ex - sx) / 12), stepY = Math.max(1, (ey - sy) / 6);
        int sr = (skin >> 16) & 255, sg = (skin >> 8) & 255, sb = skin & 255;
        int skinL = Math.max(12, luma(skin));
        int n = 0, sk = 0, dk = 0;
        for (int y = sy; y < ey; y += stepY) {
            for (int x = sx; x < ex; x += stepX) {
                int c = px[y * w + x];
                int d = Math.abs(((c >> 16) & 255) - sr) + Math.abs(((c >> 8) & 255) - sg) + Math.abs((c & 255) - sb);
                if (d < 55) sk++;
                if (luma(c) < skinL * 0.62) dk++;
                n++;
            }
        }
        if (n < 12) return;
        r.lipSkin = (double) sk / n;
        r.lipDark = (double) dk / n;
    }

    /**
     * What the driver is holding, in the pixels of this picture.
     *
     * The model names everything it is confident about, and this keeps the best box of
     * each of the three kinds a rule can act on. Where that box is is left to Rules:
     * a phone in the far corner of a cab is a phone on the seat, not a phone in use,
     * and only the face can say which of the two it is.
     */
    private void objects(ObjectDetectorResult res, int w, int h, Reading r) {
        if (res == null || res.detections() == null) return;
        double[] best = new double[4];
        Box[] pick = new Box[4];
        for (Detection d : res.detections()) {
            if (d == null || d.categories() == null) continue;
            int kind = NOTHING;
            double score = 0;
            for (Category c : d.categories()) {
                if (c == null) continue;
                int k = kindOf(c);
                if (k != NOTHING && c.score() > score) {
                    score = c.score();
                    kind = k;
                }
            }
            if (kind == NOTHING || score < best[kind]) continue;
            android.graphics.RectF b = d.boundingBox();
            if (b == null || b.width() <= 1 || b.height() <= 1) {
                // the model named something but this build cannot say where: that is a
                // file or a version that does not fit, not a driver holding nothing, and
                // the difference decides whether the colour fallback is used. It is
                // remembered for the whole trip because one such frame is enough to
                // know the geometry will never arrive.
                boxesBroken = true;
                odError = "The object model names things but gives no place for them.";
                continue;
            }
            // the man himself: a box over nearly the whole frame is the driver, and a
            // driver is not a thing in his hand
            if (b.width() > w * 0.9 && b.height() > h * 0.9) continue;
            best[kind] = score;
            pick[kind] = new Box(kind, b.centerX(), b.centerY(), b.width(), b.height());
        }
        r.phone = pick[PHONE];
        r.drink = pick[DRINK];
        r.food = pick[FOOD];
    }

    /** Which of the three kinds a name of the model is, or NOTHING for the other seventy. */
    private static int kindOf(Category c) {
        String n = c.categoryName();
        if (n == null || n.trim().isEmpty()) n = c.displayName();
        n = n == null ? "" : n.trim().toLowerCase(Locale.ROOT);
        if (!n.isEmpty()) {
            for (String s : PHONE_N) if (n.equals(s)) return PHONE;
            for (String s : DRINK_N) if (n.equals(s)) return DRINK;
            for (String s : FOOD_N) if (n.equals(s)) return FOOD;
        }
        int i = c.index();
        for (int s : PHONE_I) if (i == s) return PHONE;
        for (int s : DRINK_I) if (i == s) return DRINK;
        for (int s : FOOD_I) if (i == s) return FOOD;
        return NOTHING;
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

    private double coverage(int skin, List<NormalizedLandmark> f, int w, int h, double faceW, double dt) {
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
