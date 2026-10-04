package wallet.security;

import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;

public final class WalletSecurity {

    private static volatile AesKey sessionKey;

    public static final int ERROR_ALREADY_ENCRYPTED = 1;
    public static final int ERROR_LOCKED = 2;
    public static final int ERROR_NO_DETERMINISTIC_SEED = 3;
    public static final int ERROR_UNSUPPORTED_ENCRYPTION = 4;

    public static final class WalletSecurityException extends IllegalStateException {
        private final int code;

        public WalletSecurityException(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    private WalletSecurity() {
    }

    public static boolean isEncrypted(Wallet wallet) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        return seed != null && seed.isEncrypted();
    }

    public static AesKey getSessionKey() {
        return sessionKey;
    }

    public static void clearSessionKey() {
        sessionKey = null;
    }

    public static boolean unlock(Wallet wallet, String password) {
        if (!isEncrypted(wallet)) {
            sessionKey = null;
            return true;
        }

        AesKey key = deriveKey(wallet.getKeyCrypter(), password);
        if (!wallet.checkAESKey(key)) {
            return false;
        }

        sessionKey = key;
        return true;
    }

    public static void encrypt(Wallet wallet, String password) {
        if (isEncrypted(wallet)) {
            throw new WalletSecurityException(ERROR_ALREADY_ENCRYPTED);
        }

        KeyCrypterScrypt crypter = new KeyCrypterScrypt();
        AesKey key = crypter.deriveKey(password);
        wallet.encrypt(crypter, key);
        sessionKey = key;
    }

    public static void decrypt(Wallet wallet) {
        AesKey key = sessionKey;
        if (key == null || !wallet.checkAESKey(key)) {
            throw new WalletSecurityException(ERROR_LOCKED);
        }

        wallet.decrypt(key);
        sessionKey = null;
    }

    public static DeterministicSeed getDecryptedSeed(Wallet wallet) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        if (seed == null) {
            throw new WalletSecurityException(ERROR_NO_DETERMINISTIC_SEED);
        }

        if (!seed.isEncrypted()) {
            return seed;
        }

        AesKey key = sessionKey;
        if (key == null || !wallet.checkAESKey(key)) {
            throw new WalletSecurityException(ERROR_LOCKED);
        }

        KeyCrypter crypter = wallet.getKeyCrypter();
        return seed.decrypt(crypter, "", key);
    }

    private static AesKey deriveKey(KeyCrypter crypter, String password) {
        if (!(crypter instanceof KeyCrypterScrypt)) {
            throw new WalletSecurityException(ERROR_UNSUPPORTED_ENCRYPTION);
        }
        return ((KeyCrypterScrypt) crypter).deriveKey(password);
    }
}
