package com.fleet.dms;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The events the camera decided on while the page could not be told about them.
 *
 * Android freezes a WebView that is in the background, so the driver who answers a
 * phone call, or locks the screen with the app still watching him, produces events
 * that JavaScript will not see until the page is in front of him again - and a
 * handset that dies or is force-closed in the meantime must not lose them either.
 * That is why this queue is on disk rather than in memory: it is a small JSON array
 * in the app's own preferences, written by the camera service and emptied by the
 * page, which puts every item it finds into the same out-of-coverage queue the rest
 * of the app already uses. The page, not the plugin, is what talks to the database,
 * so the driver's own rights and the row guards on the events table still decide
 * what gets in.
 */
class Outbox {

    private static final String KEY = "outbox";
    // A phone that never comes back to the page is a broken phone, and an unbounded
    // queue on it would grow until the whole app failed to start. Newest kept.
    private static final int MAX = 300;

    private final SharedPreferences sp;

    Outbox(Context c) {
        sp = c.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
    }

    /** ISO 8601 in UTC, the format the database column expects. */
    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    synchronized void add(JSONObject item) {
        JSONArray a = read();
        a.put(item);
        while (a.length() > MAX) a.remove(0);
        write(a);
    }

    /** Everything waiting, oldest first, and the queue left empty. */
    synchronized JSONArray drain() {
        JSONArray a = read();
        if (a.length() > 0) write(new JSONArray());
        return a;
    }

    synchronized int size() {
        return read().length();
    }

    synchronized void clear() {
        write(new JSONArray());
    }

    private JSONArray read() {
        String raw = sp.getString(KEY, null);
        if (raw == null) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            // an array this file cannot read is not worth trying to rescue: the events
            // in it were never sent anyway, and the queue has to be usable again
            return new JSONArray();
        }
    }

    private void write(JSONArray a) {
        sp.edit().putString(KEY, a.toString()).apply();
    }
}
