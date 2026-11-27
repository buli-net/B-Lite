package wallet.watchonly;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.Wallet;

import java.util.List;

/** Exports only the active HD account public key in the SLIP-132 Native SegWit format. */
public final class AccountXpubExporter {
    private static final int MAINNET_ZPUB = 0x04b24746;
    private static final int TESTNET_VPUB = 0x045f1cf6;

    private AccountXpubExporter() {
    }

    public static ExportedAccountKey export(Wallet wallet) {
        if (wallet == null) {
            throw new IllegalArgumentException("Wallet is not available");
        }

        List<DeterministicKeyChain> chains = wallet.getActiveKeyChains();
        if (chains == null || chains.size() != 1) {
            throw new IllegalStateException("Expected exactly one active HD account");
        }

        DeterministicKeyChain chain = chains.get(0);
        if (chain.getOutputScriptType() != ScriptType.P2WPKH) {
            throw new IllegalStateException("The active wallet is not a Native SegWit HD wallet");
        }
        DeterministicKey accountKey = chain.getWatchingKey();
        if (accountKey == null || accountKey.getDepth() != 3) {
            throw new IllegalStateException("The active HD account public key is unavailable");
        }
        // getWatchingKey() may still carry private material in a spending wallet.
        // Drop it before any serialization; only the public account node is retained.
        accountKey = accountKey.dropPrivateBytes().dropParent();
        if (accountKey.hasPrivKey()) {
            throw new IllegalStateException("Private key material could not be excluded");
        }

        // WalletAppKit is created with ScriptType.P2WPKH and NetworkConfig's BIP43
        // structure, which maps to BIP84: m/84'/coin_type'/0'.
        String path = wallet.getParams().network() == BitcoinNetwork.MAINNET
                ? "m/84'/0'/0'" : "m/84'/1'/0'";
        String serialized = accountKey.serializePubB58(wallet.getParams().network());
        byte[] serializedPayload = Base58.decodeChecked(serialized);
        if (serializedPayload.length != 78) {
            throw new IllegalStateException("Unexpected extended public key payload");
        }
        byte[] keyData = new byte[74];
        System.arraycopy(serializedPayload, 4, keyData, 0, keyData.length);
        int version = wallet.getParams().network() == BitcoinNetwork.MAINNET
                ? MAINNET_ZPUB : TESTNET_VPUB;
        String encoded = Base58.encodeChecked(version, keyData);
        return new ExportedAccountKey(encoded, path);
    }

    public static final class ExportedAccountKey {
        private final String encodedKey;
        private final String derivationPath;

        private ExportedAccountKey(String encodedKey, String derivationPath) {
            this.encodedKey = encodedKey;
            this.derivationPath = derivationPath;
        }

        public String getEncodedKey() {
            return encodedKey;
        }

        public String getDerivationPath() {
            return derivationPath;
        }
    }
}
