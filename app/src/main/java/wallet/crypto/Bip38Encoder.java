package wallet.crypto;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.bouncycastle.crypto.generators.SCrypt;

import wallet.main.R;
import wallet.ui.LocalizedErrors;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.Normalizer;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * BIP38 non-EC-multiply encryption adapter.
 * bitcoinj 0.17.1 implements BIP38 decoding only, so the app keeps this missing
 * operation while delegating Base58Check, SHA-256d and scrypt to its libraries.
 */
public final class Bip38Encoder {
    private static final int N = 16384;
    private static final int R = 8;
    private static final int P = 8;

    private Bip38Encoder() { }

    public static String encrypt(ECKey key, String address, String passphrase) {
        byte[] addressBytes = null;
        byte[] salt = null;
        byte[] passwordBytes = null;
        byte[] derived = null;
        byte[] secret = null;
        byte[] mixed = null;
        byte[] aesKey = null;
        byte[] encrypted = null;
        byte[] payload = null;
        try {
            addressBytes = address.getBytes(StandardCharsets.US_ASCII);
            salt = Arrays.copyOf(Sha256Hash.hashTwice(addressBytes), 4);
            passwordBytes = Normalizer.normalize(passphrase, Normalizer.Form.NFC)
                    .getBytes(StandardCharsets.UTF_8);
            derived = SCrypt.generate(passwordBytes, salt, N, R, P, 64);
            secret = key.getPrivKeyBytes();
            mixed = Arrays.copyOf(secret, 32);
            for (int i = 0; i < 32; i++) mixed[i] ^= derived[i];

            aesKey = Arrays.copyOfRange(derived, 32, 64);
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
            encrypted = cipher.doFinal(mixed);

            // Base58.encodeChecked prepends the 0x01 BIP38 prefix and appends SHA-256d checksum.
            // Body bytes: 0x42 (non-EC-multiply), 0xE0 (compressed flag), address hash, encrypted key.
            payload = new byte[38];
            payload[0] = 0x42;
            payload[1] = (byte) 0xE0;
            System.arraycopy(salt, 0, payload, 2, 4);
            System.arraycopy(encrypted, 0, payload, 6, 32);
            return Base58.encodeChecked(0x01, payload);
        } catch (GeneralSecurityException error) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_bip38_encrypt_failed, error);
        } finally {
            wipe(passwordBytes);
            wipe(derived);
            wipe(secret);
            wipe(mixed);
            wipe(aesKey);
            wipe(encrypted);
            wipe(payload);
            wipe(salt);
            wipe(addressBytes);
        }
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }
}
