package wallet.watchonly;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.Wallet;
import wallet.core.WalletAddressType;

/** Exports the public account key for the wallet's current default HD account. */
public final class AccountXpubExporter {
    private static final int MAINNET_XPUB = 0x0488B21E;
    private static final int TESTNET_TPUB = 0x043587CF;
    private static final int MAINNET_YPUB = 0x049D7CB2;
    private static final int TESTNET_UPUB = 0x044A5262;
    private static final int MAINNET_ZPUB = 0x04B24746;
    private static final int TESTNET_VPUB = 0x045F1CF6;

    private AccountXpubExporter() {
    }

    public static ExportedAccountKey export(Wallet wallet) {
        if (wallet == null) {
            throw new ExportException(ExportException.Reason.WALLET_UNAVAILABLE);
        }

        // getActiveKeyChains() can contain multiple chains when key rotation is
        // configured. The default active chain is the current account and is
        // deliberately the last/default choice made by bitcoinj.
        DeterministicKeyChain chain = wallet.getActiveKeyChain();
        if (chain == null) {
            throw new ExportException(ExportException.Reason.NO_ACTIVE_HD_ACCOUNT);
        }

        DeterministicKey accountKey = chain.getWatchingKey();
        if (accountKey == null || accountKey.getDepth() < 1 || accountKey.getDepth() > 4
                || chain.getAccountPath() == null || chain.getAccountPath().isEmpty()
                || chain.getAccountPath().size() > 4) {
            throw new ExportException(ExportException.Reason.ACCOUNT_KEY_UNAVAILABLE);
        }

        String derivationPath = normalizeDerivationPath(accountKey.getPathAsString());
        int purpose = purposeFromPath(derivationPath);
        String addressType = resolveAddressType(chain.getOutputScriptType(), purpose);
        int version = extendedKeyVersion(wallet.getParams().network(), addressType, purpose);

        // getWatchingKey() may still contain private material in a spending
        // wallet. Strip both private bytes and parent links before serialization.
        DeterministicKey publicAccountKey = accountKey.dropPrivateBytes().dropParent();
        if (publicAccountKey.hasPrivKey()) {
            throw new ExportException(ExportException.Reason.PRIVATE_KEY_NOT_REMOVED);
        }

        String standardPublicKey = publicAccountKey.serializePubB58(wallet.getParams().network());
        byte[] serializedPayload;
        try {
            serializedPayload = Base58.decodeChecked(standardPublicKey);
        } catch (RuntimeException error) {
            throw new ExportException(ExportException.Reason.ACCOUNT_KEY_INVALID, error);
        }
        if (serializedPayload.length != 78) {
            throw new ExportException(ExportException.Reason.ACCOUNT_KEY_INVALID);
        }

        // Re-encode only the 74-byte BIP32 key data with the matching SLIP-132
        // version for BIP49/BIP84. BIP44 and BIP86 use the standard xpub/tpub
        // version; the exact path and address type are shown alongside the key.
        byte[] keyData = new byte[74];
        System.arraycopy(serializedPayload, 4, keyData, 0, keyData.length);
        String encoded = Base58.encodeChecked(version, keyData);
        return new ExportedAccountKey(encoded, derivationPath, addressType);
    }

    private static String resolveAddressType(ScriptType scriptType, int purpose) {
        // The derivation purpose is the most reliable indicator when the
        // account follows a standard BIP44-family path.
        switch (purpose) {
            case 44:
                return WalletAddressType.P2PKH;
            case 49:
                return WalletAddressType.P2SH_P2WPKH;
            case 84:
                return WalletAddressType.P2WPKH;
            case 86:
                return WalletAddressType.P2TR;
            default:
                break;
        }

        // Older bitcoinj BIP32 wallets do not use BIP44 purpose paths. For
        // those wallets use the output script type stored on the key chain.
        if (scriptType == ScriptType.P2PKH) return WalletAddressType.P2PKH;
        if (scriptType == ScriptType.P2WPKH) return WalletAddressType.P2WPKH;
        if (scriptType == ScriptType.P2TR) return WalletAddressType.P2TR;

        // A generic P2SH chain is not enough to prove it is BIP49 P2SH-P2WPKH.
        throw new ExportException(ExportException.Reason.UNSUPPORTED_ADDRESS_TYPE);
    }

    private static int extendedKeyVersion(BitcoinNetwork network, String addressType,
                                          int purpose) {
        boolean mainnet = network == BitcoinNetwork.MAINNET;
        if (WalletAddressType.P2SH_P2WPKH.equals(addressType) && purpose == 49) {
            return mainnet ? MAINNET_YPUB : TESTNET_UPUB;
        }
        if (WalletAddressType.P2WPKH.equals(addressType) && purpose == 84) {
            return mainnet ? MAINNET_ZPUB : TESTNET_VPUB;
        }
        return mainnet ? MAINNET_XPUB : TESTNET_TPUB;
    }

    private static String normalizeDerivationPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new ExportException(ExportException.Reason.ACCOUNT_PATH_UNAVAILABLE);
        }
        String normalized = path.trim();
        if (normalized.startsWith("M/")) {
            normalized = "m/" + normalized.substring(2);
        } else if (normalized.equals("M")) {
            normalized = "m";
        }
        return normalized;
    }

    /** Returns the first hardened path component, or -1 for a non-standard path. */
    private static int purposeFromPath(String path) {
        if (path == null || !path.startsWith("m/")) return -1;
        int slash = path.indexOf('/', 2);
        String first = slash < 0 ? path.substring(2) : path.substring(2, slash);
        if (!(first.endsWith("'") || first.endsWith("h") || first.endsWith("H"))) {
            return -1;
        }
        String digits = first.substring(0, first.length() - 1);
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static final class ExportException extends IllegalStateException {
        public enum Reason {
            WALLET_UNAVAILABLE,
            NO_ACTIVE_HD_ACCOUNT,
            ACCOUNT_KEY_UNAVAILABLE,
            ACCOUNT_PATH_UNAVAILABLE,
            ACCOUNT_KEY_INVALID,
            UNSUPPORTED_ADDRESS_TYPE,
            PRIVATE_KEY_NOT_REMOVED
        }

        private final Reason reason;

        private ExportException(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        private ExportException(Reason reason, Throwable cause) {
            super(reason.name(), cause);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    public static final class ExportedAccountKey {
        private final String encodedKey;
        private final String derivationPath;
        private final String addressType;

        private ExportedAccountKey(String encodedKey, String derivationPath, String addressType) {
            this.encodedKey = encodedKey;
            this.derivationPath = derivationPath;
            this.addressType = addressType;
        }

        public String getEncodedKey() {
            return encodedKey;
        }

        public String getDerivationPath() {
            return derivationPath;
        }

        public String getAddressType() {
            return addressType;
        }
    }
}
