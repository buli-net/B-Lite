package wallet.crypto;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;

/**
 * BIP38 non-EC-multiplied private-key encryption adapter.
 * bitcoinj 0.17.1 exposes BIP38 decryption but no encryption API, so this class retains only
 * the BIP38 envelope assembly while delegating SHA-256, scrypt, Base58Check, and AES primitives
 * to the existing libraries/providers.
 */
public final class Bip38Encoder {
    private static final int N = 16384;
    private static final int R = 8;
    private static final int P = 8;

    private Bip38Encoder() { }

    public static String encrypt(ECKey key, String address, String passphrase) {
        byte[] salt = null;
        byte[] derived = null;
        byte[] passwordBytes = null;
        byte[] secret = null;
        byte[] plain1 = null;
        byte[] plain2 = null;
        byte[] encrypted1 = null;
        byte[] encrypted2 = null;
        byte[] payload = null;
        try {
            salt = Arrays.copyOf(
                    Sha256Hash.hashTwice(address.getBytes(StandardCharsets.US_ASCII)), 4);
            String normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFC);
            passwordBytes = normalized.getBytes(StandardCharsets.UTF_8);
            derived = Scrypt.scrypt(passwordBytes, salt, N, R, P, 64);
            secret = key.getPrivKeyBytes();
            if (secret == null || secret.length != 32) {
                throw new IllegalArgumentException("BIP38 requires a 32-byte private key");
            }
            plain1 = new byte[16];
            plain2 = new byte[16];
            for (int i = 0; i < 16; i++) {
                plain1[i] = (byte) (secret[i] ^ derived[i]);
                plain2[i] = (byte) (secret[i + 16] ^ derived[i + 16]);
            }
            Aes256 aes = new Aes256(derived, 32);
            encrypted1 = aes.encrypt(plain1);
            encrypted2 = aes.encrypt(plain2);

            payload = new byte[39];
            payload[0] = 0x01;
            payload[1] = 0x42;
            payload[2] = (byte) 0xE0; // compressed + non-EC-multiplied
            System.arraycopy(salt, 0, payload, 3, 4);
            System.arraycopy(encrypted1, 0, payload, 7, 16);
            System.arraycopy(encrypted2, 0, payload, 23, 16);
            return Base58.encodeChecked(payload[0] & 0xff,
                    Arrays.copyOfRange(payload, 1, payload.length));
        } finally {
            wipe(salt);
            wipe(derived);
            wipe(passwordBytes);
            wipe(secret);
            wipe(plain1);
            wipe(plain2);
            wipe(encrypted1);
            wipe(encrypted2);
            wipe(payload);
        }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
