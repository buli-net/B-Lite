package wallet.crypto;

import wallet.ui.LocalizedErrors;
import wallet.main.R;

import org.bitcoinj.core.NetworkParameters;
import java.util.Arrays;

public final class WifCodec {
    private WifCodec() { }

    public static String privateKeyHex(String wif, NetworkParameters parameters) {
        byte[] payload = Base58Check.decode(wif);
        if (payload.length != 33 && payload.length != 34) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_wif_invalid);
        }
        if (parameters == null || (payload[0] & 0xff) != parameters.getDumpedPrivateKeyHeader()) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_wif_network_mismatch);
        }
        return toHex(Arrays.copyOfRange(payload, 1, 33));
    }

    public static String toHex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
