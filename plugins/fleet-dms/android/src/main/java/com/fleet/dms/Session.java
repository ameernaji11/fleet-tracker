package com.fleet.dms;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.getcapacitor.Logger;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The driver's sign-in, kept so that the position runner can still send with the app closed.
 *
 * Until now the runner only ever held a token the page handed it in memory, and that is what
 * stranded a trip: Android can freeze the WebView for the whole of a shift, the access token
 * runs out an hour after a driver signs in, and a service with nothing to write its rows with
 * can only collect. The driver's complaint was the shape of it - "+243 records waiting", and
 * the office seeing nothing until he opened the app. So the sign-in is kept on the handset,
 * and the runner refreshes it itself against the same address the page logs in to.
 *
 * What is kept is written once, as one opaque blob, under a key this process never sees in
 * the clear: AES-256-GCM with a key generated inside the Android Keystore, marked
 * non-exportable, which the hardware will only use for this app on this handset. A reader of
 * the file - a backup, an explorer, another app - gets base64 of a ciphertext whose key is
 * not in it, and a key that cannot be taken off the device. There is no password in here and
 * no way to sign in from it that the driver did not already do with his own hands.
 *
 * It is deliberately fragile in one direction: the Keystore key does not survive a factory
 * reset, a restore of an app backup onto different hardware, or the driver clearing his data.
 * Each of those simply loses the blob, and the runner falls back to exactly what it did
 * before - collecting, and sending as soon as a page hands a sign-in over again. Nothing here
 * can be the reason a position is refused, because a sign-in that cannot be read is a sign-in
 * that is not used.
 */
class Session {

    private static final String PREFS = "fleet.track";
    private static final String K_BLOB = "sess";
    private static final String KEY = "fleet.track.session";
    private static final int TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    /**
     * Store one sign-in. Silently does nothing on a handset that will not give up a key: the
     * page still lends a token every fifteen seconds, so the worst answer here is the behaviour
     * this runner always had rather than a trip that stops being recorded.
     */
    static void save(Context c, String json) {
        if (json == null || json.isEmpty()) {
            wipe(c);
            return;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher c1 = Cipher.getInstance("AES/GCM/NoPadding");
            c1.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = c1.doFinal(json.getBytes("UTF-8"));
            byte[] both = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, both, 0, iv.length);
            System.arraycopy(sealed, 0, both, iv.length, sealed.length);
            prefs(c).edit().putString(K_BLOB, Base64.encodeToString(both, Base64.NO_WRAP)).apply();
        } catch (Throwable t) {
            Logger.error("fleet-dms: the sign-in could not be sealed on this phone: " + t, t);
            // A handset that cannot keep one is left with no half-written blob to read back.
            wipe(c);
        }
    }

    /** The stored sign-in as it was saved, or null when there is none or it will not open. */
    static String load(Context c) {
        String raw = prefs(c).getString(K_BLOB, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            byte[] both = Base64.decode(raw, Base64.NO_WRAP);
            if (both == null || both.length <= IV_BYTES) return null;
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(both, 0, iv, 0, IV_BYTES);
            Cipher c1 = Cipher.getInstance("AES/GCM/NoPadding");
            c1.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            // The tag is checked inside doFinal, so a blob edited byte by byte - or one written
            // by a different install of this app - throws rather than handing back nonsense.
            byte[] open = c1.doFinal(both, IV_BYTES, both.length - IV_BYTES);
            return new String(open, "UTF-8");
        } catch (Throwable t) {
            Logger.error("fleet-dms: the sign-in kept on this phone could not be opened", t);
            wipe(c);
            return null;
        }
    }

    /** Forget it: sign-out, a seat given up, a handset that stopped belonging to this driver. */
    static void wipe(Context c) {
        try {
            prefs(c).edit().remove(K_BLOB).apply();
        } catch (Throwable ignore) {
            // a preference file this app cannot write has nothing left to forget either
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The Keystore key, created the first time this phone is asked to keep a sign-in. Asked for
     * with no user authentication: a driver's shift runs behind a locked screen, and a key that
     * wanted a fingerprint could not be used by a service nobody is allowed to wake him for.
     */
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEY, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build());
        return g.generateKey();
    }
}
