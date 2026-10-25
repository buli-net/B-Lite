package wallet.crypto;

import org.bouncycastle.crypto.generators.SCrypt;

import wallet.main.R;
import wallet.ui.LocalizedErrors;

/** Thin adapter to Bouncy Castle's RFC 7914 scrypt implementation. */
final class Scrypt {
    private Scrypt() { }

    static byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int dkLen) {
        if (n <= 1 || (n & (n - 1)) != 0) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_scrypt_n_invalid);
        }
        return SCrypt.generate(password, salt, n, r, p, dkLen);
    }
}
