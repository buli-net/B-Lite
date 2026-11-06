package wallet.crypto;

import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.core.NetworkParameters;

import wallet.main.R;
import wallet.ui.LocalizedErrors;

/** Thin WIF adapter around bitcoinj; hex formatting is retained for app presentation/export. */
public final class WifCodec {
    private WifCodec() { }

    public static String privateKeyHex(String wif, NetworkParameters parameters) {
        if (parameters == null) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_wif_network_mismatch);
        }
        try {
            // Parse/validate WIF through bitcoinj without NetworkParameters.of(network):
            // bitcoinj 0.17.1 does not register Signet in Networks, although Signet uses test-network WIF prefix 239.
            DumpedPrivateKey decoded = DumpedPrivateKey.fromBase58((org.bitcoinj.base.Network) null, wif);
            // Compare the resolved prefix, not NetworkParameters.of(SIGNET): bitcoinj 0.17.1
            // resolves the shared test-family WIF prefix to Testnet because Signet is not registered.
            int decodedHeader = NetworkParameters.of(decoded.network()).getDumpedPrivateKeyHeader();
            if (decodedHeader != parameters.getDumpedPrivateKeyHeader()) {
                throw new LocalizedErrors.IllegalArgumentException(R.string.error_wif_network_mismatch);
            }
            return toHex(decoded.getKey().getPrivKeyBytes());
        } catch (LocalizedErrors.IllegalArgumentException error) {
            throw error;
        } catch (AddressFormatException error) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_wif_invalid);
        }
    }

    /** Hex formatting for UI/export bytes; key parsing itself is delegated to bitcoinj. */
    public static String toHex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
