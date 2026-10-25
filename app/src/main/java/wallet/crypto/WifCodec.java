package wallet.crypto;

import org.bitcoinj.base.exceptions.AddressFormatException;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.core.NetworkParameters;
import org.bouncycastle.util.encoders.Hex;

import wallet.main.R;
import wallet.ui.LocalizedErrors;


/** Wallet-specific WIF checks layered over bitcoinj's encoded-private-key API. */
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
        // WIF's 33-byte key data is only valid when the compression marker is 0x01.
        if (payload.length == 34 && payload[33] != 0x01) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_wif_invalid);
        }
        try {
            DumpedPrivateKey decoded = DumpedPrivateKey.fromBase58(parameters.network(), wif);
            return toHex(decoded.getKey().getPrivKeyBytes());
        } catch (AddressFormatException.WrongNetwork e) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_wif_network_mismatch, e);
        } catch (AddressFormatException e) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_wif_invalid, e);
        }
    }

    public static String toHex(byte[] value) {
        if (value == null) throw new NullPointerException("value");
        return Hex.toHexString(value);
    }
}
