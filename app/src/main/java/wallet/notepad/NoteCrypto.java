package wallet.notepad;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class NoteCrypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "wallet.notepad.content";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;

    static final class EncryptedContent {
        final byte[] iv;
        final byte[] ciphertext;

        EncryptedContent(byte[] iv, byte[] ciphertext) {
            this.iv = iv;
            this.ciphertext = ciphertext;
        }
    }

    private NoteCrypto() {
    }

    static EncryptedContent encrypt(String title, String content) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("title", title == null ? "" : title);
        payload.put("content", content == null ? "" : content);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] ciphertext = cipher.doFinal(
                payload.toString().getBytes(StandardCharsets.UTF_8));
        return new EncryptedContent(cipher.getIV(), ciphertext);
    }

    static String[] decrypt(byte[] iv, byte[] ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        byte[] plaintext = cipher.doFinal(ciphertext);
        try {
            JSONObject payload = new JSONObject(
                    new String(plaintext, StandardCharsets.UTF_8));
            return new String[]{
                    payload.optString("title", ""),
                    payload.optString("content", "")
            };
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }
}
