package com.fleet.dms;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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

    private final File dir;
    private final ArrayDeque<byte[]> ring = new ArrayDeque<>();
    private final ArrayDeque<Long> ringUs = new ArrayDeque<>();
    private final ExecutorService enc = Executors.newSingleThreadExecutor();

    private int fw, fh, fps = 5;
    private final int colour;
    private final boolean semi;
    private Capture run;

    /** A clip being assembled: the pictures taken before the alert, and the ones after. */
    private static class Capture {
        final String cid;
        // The size is fixed when the clip starts: an encoder configured for one picture
        // cannot be handed a different one, and the camera is free to change size at any
        // moment of a trip, so a clip keeps the size its own frames were made at.
        final int w, h;
        final List<byte[]> frames = new ArrayList<>();
        final List<Long> us = new ArrayList<>();
        long endUs;

        Capture(String cid, int w, int h) {
            this.cid = cid;
            this.w = w;
            this.h = h;
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
        Capture done = null;
        synchronized (this) {
            if (!ring.isEmpty() && ring.peekLast().length != yuv.length) {
                // the camera changed size mid-trip, and a clip cannot be made of two
                // sizes, so both the ring and anything being gathered start again
                ring.clear();
                ringUs.clear();
                run = null;
            }
            ring.addLast(yuv);
            ringUs.addLast(us);
            while (ring.size() > Math.max(2, keepFrames)) {
                ring.removeFirst();
                ringUs.removeFirst();
            }
            if (run != null) {
                run.frames.add(yuv);
                run.us.add(us);
                if (us >= run.endUs) {
                    done = run;
                    run = null;
                }
            }
        }
        if (done != null) enc.execute(() -> write(done));
    }

    /**
     * Start gathering around an alert: `before` pictures come out of the ring, then
     * `afterSeconds` more are added as the camera keeps feeding. An alert while one is
     * already being gathered is dropped rather than starting a second encoder on a
     * handset that is already reading a face.
     */
    void begin(String cid, int before, double afterSeconds) {
        Capture built = null;
        boolean straight = false;
        synchronized (this) {
            if (run != null || ring.isEmpty() || before <= 0 || fw < 2) return;
            built = new Capture(cid, fw, fh);
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
            built.endUs = built.us.get(built.us.size() - 1) + (long) (Math.max(0, afterSeconds) * 1000000);
            if (afterSeconds > 0) run = built;
            else straight = true;
        }
        // "nothing after the alert" is a choice the page can make; the ring alone is
        // then the whole clip and there is no point waiting for frames to arrive
        if (straight) {
            final Capture one = built;
            enc.execute(() -> write(one));
        }
    }

    /** Write whatever has been gathered so far. Used when the trip ends mid-clip. */
    void finish() {
        Capture c;
        synchronized (this) {
            c = run;
            run = null;
        }
        if (c != null && !c.frames.isEmpty()) enc.execute(() -> write(c));
    }

    void close() {
        finish();
        enc.shutdown();
    }

    File fileFor(String cid) {
        return new File(dir, cid + ".mp4");
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
            long t0 = c.us.get(0);
            while (!eos && guard++ < 20000) {
                if (!inputDone) {
                    int in = mc.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = mc.getInputBuffer(in);
                        if (buf == null) {
                            inputDone = true;
                        } else if (i < c.frames.size()) {
                            buf.clear();
                            buf.put(c.frames.get(i));
                            mc.queueInputBuffer(in, 0, c.frames.get(i).length, c.us.get(i) - t0, 0);
                            i++;
                        } else {
                            mc.queueInputBuffer(in, 0, 0, i > 0 ? c.us.get(i - 1) - t0 : 0,
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
        } finally {
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
        if (tmp.exists() && tmp.length() > 1024) tmp.renameTo(to);
        else if (tmp.exists()) tmp.delete();
    }

    // ---- picture to YUV

    /**
     * One picture ready for the encoder: a plane of brightness and a colour plane at
     * half size, in the order this phone asked for. Sides are rounded down to even
     * numbers because a chroma plane that samples pairs of pixels cannot have an odd
     * one, and an encoder rejects the frame for it.
     */
    private byte[] toYuv(Bitmap b) {
        int w0 = b.getWidth() & ~1, h0 = b.getHeight() & ~1;
        if (w0 < 16 || h0 < 16) return null;
        int size = w0 * h0 * 3 / 2;
        fw = w0;
        fh = h0;
        int[] px = new int[w0 * h0];
        b.getPixels(px, 0, w0, 0, 0, w0, h0);
        byte[] yuv = new byte[size];
        int plane = fw * fh, cs = fw / 2, ch = fh / 2;
        for (int y = 0; y < fh; y++) {
            int row = y * fw, srow = y * w0;
            for (int x = 0; x < fw; x++) {
                int c = px[srow + x];
                yuv[row + x] = (byte) (16 + ((66 * ((c >> 16) & 255) + 129 * ((c >> 8) & 255)
                        + 25 * (c & 255) + 128) >> 8));
            }
        }
        for (int cy = 0; cy < ch; cy++) {
            int srow = (cy * 2) * w0;
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
            run = null;
        }
    }
}
