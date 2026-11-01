package com.fleet.dms;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The few seconds an alert leaves behind.
 *
 * There is no such thing as a video that was not recorded, so the camera keeps its
 * own last seconds in memory for the whole trip - a ring of pictures, no file open,
 * nothing written. When a rule fires, the ring is handed over and the seconds after
 * the alert are added to it as the camera keeps feeding. Only then is an encoder
 * started, and what it does is plain: pictures in, one MP4 out.
 *
 * What the alert said is written into the pictures themselves on the way through, so
 * an administrator watching a clip sees the driver's own reading, the speed and the
 * seconds before or after the moment it happened, over the picture it belongs to. A
 * clip that can be watched beside a row but not carried away from it - sent on a
 * messenger, printed into an incident file, kept after the account that made it is
 * deleted - is a clip that will be described in an email instead of watched, and the
 * description will be wrong.
 *
 * The file is named after the event it belongs to, which is the client id the phone
 * gives the row in the database. Nothing uploads it in this build - the clip phase
 * reads these files back by that name and puts them where an administrator can watch
 * them - which is why an event and its picture are linked by an id the phone
 * invented rather than anything the server handed out.
 */
class Clip {

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    // A handset that is never driven again should not fill itself with clips nobody
    // will watch, whatever the retention days say.
    private static final int MAX_FILES = 400;
    /**
     * How many alerts can be gathering their seconds at the same time. Four covers the
     * worst the rules do in practice - a driver who raises a phone over a mouth that is
     * already talking, which the frame now refuses to count twice but which the older
     * build did fire together - and it is a cap on memory rather than on attention:
     * every one of these is a few hundred kilobytes of pictures on a phone that is
     * already holding a model and a camera open.
     */
    private static final int MAX_RUNS = 4;

    private final File dir;
    private final ArrayDeque<byte[]> ring = new ArrayDeque<>();
    private final ArrayDeque<Long> ringUs = new ArrayDeque<>();
    private final ExecutorService enc = Executors.newSingleThreadExecutor();

    private int fw, fh, fps = 5;
    private final int colour;
    private final boolean semi;
    private final List<Capture> runs = new ArrayList<>();
    // Counted so the page can say "the camera refused to film three of your alerts"
    // rather than leave an administrator looking for a video that was never made.
    private int dropped;
    // The last clip this refused to write, with the clip's own name on the front of it,
    // because the only thing worse than a missing video is a missing video nobody can
    // trace back to the alert it was meant to belong to.
    private volatile String lastError = "";

    /** A clip being assembled: the pictures taken before the alert, and the ones after. */
    private static class Capture {
        final String cid;
        // The size is fixed when the clip starts: an encoder configured for one picture
        // cannot be handed a different one, and the camera is free to change size at any
        // moment of a trip, so a clip keeps the size its own frames were made at.
        final int w, h;
        final List<byte[]> frames = new ArrayList<>();
        final List<Long> us = new ArrayList<>();
        // What is written across every picture of this clip, and which of the frames is
        // the one the alert was raised on: the offset printed beside the words is
        // measured from it, so a viewer can see how long before the warning the driver
        // did the thing the row says he did.
        final String[] lines;
        int alertAt;
        long endUs;

        Capture(String cid, int w, int h, String[] lines) {
            this.cid = cid;
            this.w = w;
            this.h = h;
            this.lines = lines;
        }
    }

    Clip(Context c) {
        dir = new File(c.getFilesDir(), "dms/clips");
        if (!dir.exists()) dir.mkdirs();
        colour = pickColour();
        semi = colour == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
    }

    /**
     * Which planar form this phone's own encoder takes as bytes. Most take both, the
     * choice is made once, and the picture conversion follows it. Guessing here is how
     * a build ends up with green-tinted or garbled clips on some handsets.
     */
    private static int pickColour() {
        try {
            int fallback = -1;
            for (MediaCodecInfo i : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!i.isEncoder()) continue;
                for (String t : i.getSupportedTypes()) {
                    if (!MIME.equalsIgnoreCase(t)) continue;
                    for (int f : i.getCapabilitiesForType(t).colorFormats) {
                        if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return f;
                        if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar && fallback < 0) fallback = f;
                    }
                }
            }
            if (fallback >= 0) return fallback;
        } catch (Exception ignore) {
            // a phone that will not say what it supports gets the usual answer
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
    }

    void setRate(int framesPerSecond) {
        fps = Config.clamp(framesPerSecond, 1, 15);
    }

    /**
     * One picture into the ring, called for every frame the camera hands over, alert
     * or no alert: the seconds before an incident are only there because they were
     * kept before anybody knew one was happening.
     */
    void push(Bitmap bmp, long us, int keepFrames) {
        byte[] yuv = toYuv(bmp);
        if (yuv == null) return;
        List<Capture> done = null;
        synchronized (this) {
            if (!ring.isEmpty() && ring.peekLast().length != yuv.length) {
                // the camera changed size mid-trip, and a clip cannot be made of two
                // sizes. What each clip has already gathered is its own old size and
                // encodes on its own, so this is the end of them rather than their loss
                if (!runs.isEmpty()) {
                    done = new ArrayList<>(runs);
                    runs.clear();
                }
                ring.clear();
                ringUs.clear();
            }
            ring.addLast(yuv);
            ringUs.addLast(us);
            while (ring.size() > Math.max(2, keepFrames)) {
                ring.removeFirst();
                ringUs.removeFirst();
            }
            for (Iterator<Capture> it = runs.iterator(); it.hasNext(); ) {
                Capture c = it.next();
                c.frames.add(yuv);
                c.us.add(us);
                if (us >= c.endUs) {
                    it.remove();
                    if (done == null) done = new ArrayList<>(1);
                    done.add(c);
                }
            }
        }
        if (done != null) {
            for (Capture finished : done) {
                // a lambda may only take a variable it can rely on not changing, so each
                // one is handed over under its own name rather than round the loop
                enc.execute(() -> write(finished));
            }
        }
    }

    /**
     * Start gathering around an alert: `before` pictures come out of the ring, then
     * `afterSeconds` more are added as the camera keeps feeding.
     *
     * A fourth alert while three are already being gathered is the one an administrator
     * wants to see - four rules firing inside one second of each other is one driver
     * doing one thing - so the oldest is closed with the seconds it has rather than the
     * newest being refused. Both are written; neither is thrown away.
     */
    void begin(String cid, int before, double afterSeconds, String[] lines) {
        Capture built;
        boolean straight = false;
        List<Capture> done = null;
        synchronized (this) {
            if (ring.isEmpty() || before <= 0 || fw < 2) {
                // nothing was ever in the ring: a trip that has not had a single frame
                // of camera yet, which is a rule firing before the lens has answered
                dropped++;
                return;
            }
            while (runs.size() >= MAX_RUNS) {
                Capture old = runs.remove(0);
                if (done == null) done = new ArrayList<>(1);
                done.add(old);
            }
            built = new Capture(cid, fw, fh, lines);
            // a ring has no index, and the two of them are always the same length, so
            // the oldest pictures are stepped over together and the rest copied in order
            Iterator<byte[]> fs = ring.iterator();
            Iterator<Long> ts = ringUs.iterator();
            for (int skip = Math.max(0, ring.size() - before); skip > 0 && fs.hasNext(); skip--) {
                fs.next();
                ts.next();
            }
            while (fs.hasNext()) {
                built.frames.add(fs.next());
                built.us.add(ts.next());
            }
            // the frame that raised the alert is the newest one in the ring: the
            // picture is put here before the rules are asked what it shows, so the
            // moment the driver is warned about is a moment this clip contains
            built.alertAt = built.frames.size() - 1;
            built.endUs = built.us.get(built.us.size() - 1) + (long) (Math.max(0, afterSeconds) * 1000000);
            if (afterSeconds > 0) runs.add(built);
            else straight = true;
        }
        if (done != null) for (Capture old : done) enc.execute(() -> write(old));
        // "nothing after the alert" is a choice the page can make; the ring alone is
        // then the whole clip and there is no point waiting for frames to arrive
        if (straight) enc.execute(() -> write(built));
    }

    /** How many alerts this trip asked for a picture of and could not be given one. */
    int dropped() {
        return dropped;
    }

    /** The last clip this refused to write, named by the event it was meant to belong to. */
    String lastError() {
        return lastError;
    }

    /** Write whatever has been gathered so far. Used when the trip ends mid-clip. */
    void finish() {
        List<Capture> all;
        synchronized (this) {
            all = new ArrayList<>(runs);
            runs.clear();
        }
        for (Capture c : all) if (!c.frames.isEmpty()) enc.execute(() -> write(c));
    }

    void close() {
        finish();
        enc.shutdown();
    }

    File fileFor(String cid) {
        return new File(dir, cid + ".mp4");
    }

    /**
     * Whether a name the page gave can be a clip of this service's own making. A clip
     * is named after the event id the camera invented, which is a UUID, and the page is
     * only ever meant to hand one of those back: anything with a slash or a dot in it
     * is a way out of the clip folder, and is refused before a file is opened.
     */
    private static boolean ownName(String cid) {
        return cid != null && cid.matches("[0-9a-fA-F-]{1,64}");
    }

    /** The clips still on this phone, each named by the event it belongs to. */
    List<String> cids() {
        List<String> out = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all != null) {
            for (File f : all) {
                String n = f.getName();
                if (!n.endsWith(".mp4") || n.endsWith(".tmp.mp4")) continue;
                String cid = n.substring(0, n.length() - 4);
                if (ownName(cid)) out.add(cid);
            }
        }
        return out;
    }

    // A clip is a few seconds at 320x240, which is tens of kilobytes; anything far
    // beyond that is not a clip this code wrote, and reading it into memory would be
    // the phone's last act anyway.
    private static final long MAX_READ = 4L * 1024 * 1024;

    /** The whole file, or null when this phone no longer has it. */
    byte[] read(String cid) {
        if (!ownName(cid)) return null;
        File f = fileFor(cid);
        if (!f.isFile() || f.length() <= 0 || f.length() > MAX_READ) return null;
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Throwable t) {
            // a file the phone will not give up costs an event its picture only
            return null;
        }
    }

    /** Put a clip away once the server has it, so the phone does not carry it twice. */
    void drop(String cid) {
        if (!ownName(cid)) return;
        File f = fileFor(cid);
        if (f.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** How many pictures are waiting on this phone, and how much room they take. */
    int count() {
        File[] all = dir.listFiles();
        int n = 0;
        if (all != null) for (File f : all) if (f.getName().endsWith(".mp4")) n++;
        return n;
    }

    long bytes() {
        File[] all = dir.listFiles();
        long t = 0;
        if (all != null) for (File f : all) if (f.getName().endsWith(".mp4")) t += f.length();
        return t;
    }

    // ---- the encoder

    /**
     * Encode one clip. Runs on its own thread, writes to a temporary name and renames
     * at the end, so a phase that comes later never finds a half-written file where it
     * expects a picture. Every failure here costs an event its video and nothing else.
     */
    private void write(Capture c) {
        int fw = c.w, fh = c.h;
        if (c.frames.isEmpty() || fw < 2 || fh < 2) return;
        File to = fileFor(c.cid);
        File tmp = new File(dir, c.cid + ".tmp.mp4");
        if (tmp.exists()) tmp.delete();
        MediaCodec mc = null;
        MediaMuxer mx = null;
        int track = -1;
        boolean eos = false;
        Captioner cap = null;
        try {
            mc = MediaCodec.createEncoderByType(MIME);
            MediaFormat f = MediaFormat.createVideoFormat(MIME, fw, fh);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, colour);
            f.setInteger(MediaFormat.KEY_BIT_RATE, Math.min(1400000, Math.max(180000, fw * fh * fps / 4)));
            f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            mc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mc.start();

            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            int i = 0, guard = 0;
            // The pictures reach the ring as fast as this phone can think, which is rarely
            // the rate the camera was asked for: a model that needs 350 ms over a frame
            // leaves one every 350 ms, and a film stamped with those readings of the clock
            // hangs on every picture and lurches over the next. A wall-clock correction in
            // the middle of a trip is worse still, and freezes the clip for seconds. Each
            // picture is therefore given its own equal share of the rate the Configuration
            // page set, so a clip plays as one smooth film at that rate however long its
            // pictures really took to come. The seconds burned across the picture are
            // still the real ones, from the ring's own clock: see Captioner.offset. A sped
            // up record of an incident is only evidence if it still says when.
            long step = 1000000L / Math.max(1, fps);
            // the words this clip carries, written across every picture of it on the way
            // through the encoder - see Captioner for why it is the picture and not the
            // row that has to say them
            if (c.lines != null && c.lines.length > 0) cap = new Captioner(fw, fh);
            while (!eos && guard++ < 20000) {
                if (!inputDone) {
                    int in = mc.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = mc.getInputBuffer(in);
                        if (buf == null) {
                            inputDone = true;
                        } else if (i < c.frames.size()) {
                            // the ring's own bytes when there is nothing to write across
                            // them, and the same picture with the reading on it when there
                            // is: the encoder is fed one or the other, never both
                            byte[] frame = cap == null ? c.frames.get(i) : cap.burn(c, i);
                            buf.clear();
                            buf.put(frame);
                            mc.queueInputBuffer(in, 0, frame.length, (long) i * step, 0);
                            i++;
                        } else {
                            mc.queueInputBuffer(in, 0, 0, i > 0 ? (long) (i - 1) * step : 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }
                int out = mc.dequeueOutputBuffer(bi, 10000);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (mx == null) {
                        mx = new MediaMuxer(tmp.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        track = mx.addTrack(mc.getOutputFormat());
                        mx.start();
                    }
                    continue;
                }
                if (out < 0) {
                    if (inputDone && i >= c.frames.size() && out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // nothing left to feed and nothing coming out: an encoder that
                        // never marks the end of the stream must not be waited on
                        if (guard > 300 && mx == null) break;
                        if (mx != null && guard > 600) break;
                    }
                    continue;
                }
                if (mx != null && track >= 0 && bi.size > 0
                        && (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    ByteBuffer ob = mc.getOutputBuffer(out);
                    if (ob != null) {
                        ob.position(bi.offset);
                        ob.limit(bi.offset + bi.size);
                        mx.writeSampleData(track, ob, bi);
                    }
                }
                if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                mc.releaseOutputBuffer(out, false);
            }
        } catch (Throwable t) {
            // an encoder that refuses costs this event its picture; the event itself
            // is already in the queue and the trip carries on
            if (tmp.exists()) tmp.delete();
            // named rather than swallowed, because a fleet whose camera has quietly
            // stopped making pictures has no other way of finding that out
            lastError = c.cid + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        } finally {
            if (cap != null) cap.close();
            if (mx != null) {
                try {
                    mx.stop();
                } catch (Exception ignore) {
                    // a muxer that never got a sample has nothing to finish
                }
                try {
                    mx.release();
                } catch (Exception ignore) {
                    // already gone
                }
            }
            if (mc != null) {
                try {
                    mc.stop();
                } catch (Exception ignore) {
                    // already stopped
                }
                try {
                    mc.release();
                } catch (Exception ignore) {
                    // already released
                }
            }
        }
        if (tmp.exists() && tmp.length() > 1024) {
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(to);
        } else if (tmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            // an encoder that produced less than a kilobyte wrote no picture at all,
            // and a clip that never existed is the one thing a silent pipeline loses
            lastError = c.cid + ": the encoder wrote nothing";
        }
    }

    // ---- picture to YUV, and back

    /**
     * One picture ready for the encoder: a plane of brightness and a colour plane at
     * half size, in the order this phone asked for. Sides are rounded down to even
     * numbers because a chroma plane that samples pairs of pixels cannot have an odd
     * one, and an encoder rejects the frame for it.
     */
    private byte[] toYuv(Bitmap b) {
        int w0 = b.getWidth() & ~1, h0 = b.getHeight() & ~1;
        if (w0 < 16 || h0 < 16) return null;
        fw = w0;
        fh = h0;
        int[] px = new int[w0 * h0];
        b.getPixels(px, 0, w0, 0, 0, w0, h0);
        return pack(px, w0, w0, h0);
    }

    /**
     * The same conversion from pixels that are already in hand. Split off because the
     * caption has its own copy of a picture - the ring's bytes turned back into colour,
     * written across, and turned back again - and must never touch the size the camera
     * thread is working to.
     */
    private byte[] pack(int[] px, int sw, int w, int h) {
        byte[] yuv = new byte[w * h * 3 / 2];
        int plane = w * h, cs = w / 2, ch = h / 2;
        for (int y = 0; y < h; y++) {
            int row = y * w, srow = y * sw;
            for (int x = 0; x < w; x++) {
                int c = px[srow + x];
                yuv[row + x] = (byte) (16 + ((66 * ((c >> 16) & 255) + 129 * ((c >> 8) & 255)
                        + 25 * (c & 255) + 128) >> 8));
            }
        }
        for (int cy = 0; cy < ch; cy++) {
            int srow = (cy * 2) * sw;
            for (int cx = 0; cx < cs; cx++) {
                int c = px[srow + cx * 2];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, bl = c & 255;
                int u = 128 + ((-38 * r - 74 * g + 112 * bl + 128) >> 8);
                int v = 128 + ((112 * r - 94 * g - 18 * bl + 128) >> 8);
                if (semi) {
                    // V and U alternate in one plane
                    int o = plane + cy * cs * 2 + cx * 2;
                    yuv[o] = (byte) v;
                    yuv[o + 1] = (byte) u;
                } else {
                    yuv[plane + cy * cs + cx] = (byte) u;
                    yuv[plane + ch * cs + cy * cs + cx] = (byte) v;
                }
            }
        }
        return yuv;
    }

    /** The encoder's own bytes back as colours, in the same order this phone asked for. */
    private void unpack(byte[] yuv, int w, int h, int[] out) {
        int plane = w * h, cs = w / 2, ch = h / 2;
        for (int y = 0; y < h; y++) {
            int row = y * w, cy = y >> 1, crow = cy * cs;
            int semiRow = plane + cy * w;
            for (int x = 0; x < w; x++) {
                int yy = (yuv[row + x] & 255) - 16;
                if (yy < 0) yy = 0;
                int c = x >> 1, u, v;
                if (semi) {
                    v = (yuv[semiRow + c * 2] & 255) - 128;
                    u = (yuv[semiRow + c * 2 + 1] & 255) - 128;
                } else {
                    u = (yuv[plane + crow + c] & 255) - 128;
                    v = (yuv[plane + ch * cs + crow + c] & 255) - 128;
                }
                out[row + x] = 0xFF000000
                        | clip8((1186 * yy + 1620 * v) >> 10) << 16
                        | clip8((1186 * yy - 405 * u - 813 * v) >> 10) << 8
                        | clip8((1186 * yy + 2018 * u) >> 10);
            }
        }
    }

    private static int clip8(int x) {
        return x < 0 ? 0 : x > 255 ? 255 : x;
    }

    /**
     * The words an alert leaves behind, written into the pictures of its own clip.
     *
     * A recording that only makes sense beside its row in a dashboard is a recording
     * that gets described in an email instead of watched. Writing the reading, the
     * speed, the driver's name and the seconds before or after the warning across the
     * frames themselves is what lets one of these files be sent to the person who
     * investigates an incident and still say what it is a picture of - and it is done
     * here, in the encoder's own thread, because that is the one moment the picture is
     * already in colour and nobody is waiting on the frame.
     */
    private final class Captioner {
        private final int w, h;
        private final int[] argb;
        private final Bitmap img;
        private final Canvas cv;
        private final Paint back = new Paint();
        private final Paint ink = new Paint();
        private byte[] made;
        private int at = -1;

        Captioner(int w, int h) {
            this.w = w;
            this.h = h;
            this.argb = new int[w * h];
            this.img = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            this.cv = new Canvas(img);
            back.setColor(0x8C000000);
            back.setStyle(Paint.Style.FILL);
            ink.setColor(0xFFFFFFFF);
            ink.setAntiAlias(true);
            ink.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            ink.setShadowLayer(2f, 0f, 1f, 0xE0000000);
        }

        /** The picture of frame `i`, with this clip's words across its bottom. */
        byte[] burn(Capture c, int i) {
            // an input buffer that was not ready the first time asks for the same frame
            // again, and a second round of conversion would only draw the same words
            if (i == at && made != null) return made;
            unpack(c.frames.get(i), w, h, argb);
            img.setPixels(argb, 0, w, 0, 0, w, h);
            String[] all = new String[c.lines.length + 1];
            all[0] = offset(c, i);
            System.arraycopy(c.lines, 0, all, 1, c.lines.length);
            draw(all);
            // The words were painted onto the picture, not into this array, and it is the
            // array the encoder is handed: reading the drawn result back is the only thing
            // that puts a caption in the file at all. Without it every clip leaves the phone
            // with its event, its speed and its driver invisible, and nothing on the phone
            // or the dashboard ever says so, because the clip looks perfectly watchable.
            img.getPixels(argb, 0, w, 0, 0, w, h);
            made = pack(argb, w, w, h);
            at = i;
            return made;
        }

        /** How far this picture stands from the frame the alert was raised on. */
        private String offset(Capture c, int i) {
            int n = c.us.size();
            if (n == 0) return "";
            // the frame the rules saw is the one this clip was started on, so its index
            // is inside the picture; the step past the end is only a clip that was cut
            // short by a camera that stopped answering, and its last frame is the best
            // answer it can give for where the alert sat
            long gap = n > 1 ? (c.us.get(n - 1) - c.us.get(0)) / (n - 1) : 200000L;
            long alertUs = c.alertAt < n ? c.us.get(c.alertAt) : c.us.get(n - 1) + gap;
            double s = (c.us.get(i) - alertUs) / 1000000.0;
            if (Math.abs(s) < 0.15) return "the moment the alert was raised";
            return String.format(Locale.US, "%.1f s %s the alert", Math.abs(s), s < 0 ? "before" : "after");
        }

        private void draw(String[] lines) {
            // one size for all of them, shrunk until the longest fits the picture: a
            // driver's name in a script this handset cannot measure wide must not run
            // off the edge of the file an administrator will later have to read
            float size = Math.max(9f, h / 17f);
            ink.setTextSize(size);
            float widest = 1;
            // measured at the size it is about to be drawn at, and cut to 120
            // characters first - a name long enough to need measuring twice is a name
            // that needs shortening, not a name that needs a smaller picture
            for (String s : lines) if (s != null && !s.isEmpty())
                widest = Math.max(widest, ink.measureText(s, 0, Math.min(s.length(), 120)));
            float room = w - 12f;
            if (widest > room) {
                size = Math.max(6f, size * room / widest);
                ink.setTextSize(size);
            }
            float lineH = size * 1.35f;
            int n = 0;
            for (String s : lines) if (s != null && !s.isEmpty()) n++;
            float bandH = n * lineH + size * 0.7f;
            float top = h - bandH - 4f;
            cv.drawRect(2f, top, w - 2f, h - 2f, back);
            float y = top + size;
            for (String s : lines) {
                if (s == null || s.isEmpty()) continue;
                // drawn exactly as far as it was measured: a word cut by a shrinking
                // that never saw its tail would run off the edge all the same
                if (s.length() > 120) s = s.substring(0, 120);
                cv.drawText(s, 6f, Math.min(y, h - 5f), ink);
                y += lineH;
            }
        }

        void close() {
            try {
                img.recycle();
            } catch (Throwable ignore) {
                // a picture the graphics already took back has nowhere left to leak
            }
        }
    }

    // ---- housekeeping

    /**
     * A picture of a driver is not kept forever. Anything older than the page's
     * retention days goes here, which runs when the camera starts, so a phone used
     * every week never grows past its own window.
     */
    void age(int keepDays) {
        File[] all = dir.listFiles();
        if (all == null) return;
        long cut = System.currentTimeMillis() - Math.max(1, keepDays) * 86400000L;
        List<File> live = new ArrayList<>();
        for (File f : all) {
            if (f.getName().endsWith(".tmp.mp4")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();   // a half-written clip from a phone that died mid-encode
                continue;
            }
            if (!f.getName().endsWith(".mp4")) continue;
            if (f.lastModified() < cut) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } else live.add(f);
        }
        while (live.size() > MAX_FILES) {
            File oldest = live.get(0);
            for (File f : live) if (f.lastModified() < oldest.lastModified()) oldest = f;
            //noinspection ResultOfMethodCallIgnored
            oldest.delete();
            live.remove(oldest);
        }
    }

    /** The sign-out path: the pictures on this phone stop belonging to this driver. */
    void clearAll() {
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) f.delete();
        synchronized (this) {
            ring.clear();
            ringUs.clear();
            runs.clear();
        }
        dropped = 0;
        lastError = "";
    }
}
