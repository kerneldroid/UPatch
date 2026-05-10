package me.bmax.apatch.util;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class APatchKeyHelper {
    protected static final String SUPER_KEY = "super_key";
    protected static final String SUPER_KEY_ENC = "super_key_enc";
    private static final String TAG = "APatchSecurityHelper";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String SKIP_STORE_SUPER_KEY = "skip_store_super_key";
    private static final String SUPER_KEY_IV = "super_key_iv"; // legacy migration only
    private static final String KEY_ALIAS = "APatchSecurityKey";
    private static final String ENCRYPT_MODE = "AES/GCM/NoPadding";
    private static final String V2_PREFIX = "v2";
    private static SharedPreferences prefs = null;

    static {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                generateSecretKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to checkAndGenerateSecretKey", e);
        }
    }

    public static void setSharedPreferences(SharedPreferences sp) {
        prefs = sp;
    }

    private static SharedPreferences requirePrefs() {
        if (prefs == null) {
            throw new IllegalStateException("SharedPreferences not initialized");
        }
        return prefs;
    }

    private static void generateSecretKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (!keyStore.containsAlias(KEY_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);

                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build();

                keyGenerator.init(spec);
                keyGenerator.generateKey();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to generateSecretKey", e);
        }
    }

    private static SecretKey getSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
    }

    private static String encrypt(String orig) {
        try {
            Cipher cipher = Cipher.getInstance(ENCRYPT_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(orig.getBytes(StandardCharsets.UTF_8));
            return V2_PREFIX + ':'
                    + Base64.encodeToString(iv, Base64.NO_WRAP) + ':'
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Failed to encrypt", e);
            return null;
        }
    }

    private static String decryptV2(String encryptedData) {
        try {
            String[] parts = encryptedData.split(":", 3);
            if (parts.length != 3 || !V2_PREFIX.equals(parts[0])) {
                return null;
            }
            byte[] iv = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(parts[2], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(ENCRYPT_MODE);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Failed to decrypt v2", e);
            return null;
        }
    }

    private static String decryptLegacy(String encryptedData) {
        try {
            String legacyIv = requirePrefs().getString(SUPER_KEY_IV, null);
            if (legacyIv == null || legacyIv.isEmpty()) {
                return null;
            }
            Cipher cipher = Cipher.getInstance(ENCRYPT_MODE);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getSecretKey(),
                    new GCMParameterSpec(128, Base64.decode(legacyIv, Base64.DEFAULT))
            );
            return new String(
                    cipher.doFinal(Base64.decode(encryptedData, Base64.DEFAULT)),
                    StandardCharsets.UTF_8
            );
        } catch (Exception e) {
            Log.e(TAG, "Failed to decrypt legacy blob", e);
            return null;
        }
    }

    private static String decrypt(String encryptedData) {
        if (encryptedData == null || encryptedData.isEmpty()) {
            return "";
        }
        if (encryptedData.startsWith(V2_PREFIX + ':')) {
            return decryptV2(encryptedData);
        }
        return decryptLegacy(encryptedData);
    }

    public static boolean shouldSkipStoreSuperKey() {
        return requirePrefs().getInt(SKIP_STORE_SUPER_KEY, 0) != 0;
    }

    public static void clearConfigKey() {
        requirePrefs().edit()
                .remove(SUPER_KEY)
                .remove(SUPER_KEY_ENC)
                .remove(SUPER_KEY_IV)
                .apply();
    }

    public static void setShouldSkipStoreSuperKey(boolean should) {
        clearConfigKey();
        requirePrefs().edit().putInt(SKIP_STORE_SUPER_KEY, should ? 1 : 0).apply();
    }

    public static String readSPSuperKey() {
        String encKey = requirePrefs().getString(SUPER_KEY_ENC, "");
        if (encKey != null && !encKey.isEmpty()) {
            String decrypted = decrypt(encKey);
            if (decrypted == null) {
                return "";
            }
            if (!encKey.startsWith(V2_PREFIX + ':') && !decrypted.isEmpty()) {
                writeSPSuperKey(decrypted);
                requirePrefs().edit().remove(SUPER_KEY).remove(SUPER_KEY_IV).apply();
            }
            return decrypted;
        }

        @Deprecated
        String key = requirePrefs().getString(SUPER_KEY, "");
        if (key != null && !key.isEmpty()) {
            writeSPSuperKey(key);
            requirePrefs().edit().remove(SUPER_KEY).remove(SUPER_KEY_IV).apply();
            return key;
        }
        return "";
    }

    public static void writeSPSuperKey(String key) {
        if (shouldSkipStoreSuperKey()) {
            return;
        }
        String encrypted = encrypt(key);
        if (encrypted == null) {
            return;
        }
        requirePrefs().edit()
                .remove(SUPER_KEY)
                .remove(SUPER_KEY_IV)
                .putString(SUPER_KEY_ENC, encrypted)
                .apply();
    }
}
