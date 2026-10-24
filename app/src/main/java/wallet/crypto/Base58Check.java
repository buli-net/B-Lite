package wallet.crypto;

import android.text.TextUtils;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.base.exceptions.AddressFormatException;

import java.util.Arrays;

import wallet.main.R;
import wallet.ui.LocalizedErrors;

/** Wallet-facing validation/localization adapter around bitcoinj's Base58Check API. */
public final class Base58Check {
    private Base58Check() { }

    public static String encode(byte[] payload) {
        if (payload == null) throw new NullPointerException("payload");
        // Base58Check values used by the wallet have a version byte. Preserve the general
        // zero-length-payload edge case without reimplementing Base58 or the hash primitive.
        if (payload.length == 0) {
            return Base58.encode(Arrays.copyOf(Sha256Hash.hashTwice(payload), 4));
        }
        return Base58.encodeChecked(payload[0] & 0xff,
                Arrays.copyOfRange(payload, 1, payload.length));
    }

    public static byte[] decode(String value) {
        if (TextUtils.isEmpty(value)) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_empty);
        }
        try {
            // Keep the wallet's historical minimum length/error categorization, then rely on
            // bitcoinj for all Base58 decoding and checksum verification.
            if (Base58.decode(value).length < 5) {
                throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_invalid);
            }
        } catch (AddressFormatException.InvalidCharacter e) {
            throw new LocalizedErrors.IllegalArgumentException(
                    R.string.error_base58_character_invalid, e);
        }
        try {
            return Base58.decodeChecked(value);
        } catch (AddressFormatException.InvalidCharacter e) {
            throw new LocalizedErrors.IllegalArgumentException(
                    R.string.error_base58_character_invalid, e);
        } catch (AddressFormatException.InvalidChecksum e) {
            throw new LocalizedErrors.IllegalArgumentException(
                    R.string.error_base58_checksum_invalid, e);
        } catch (AddressFormatException.InvalidDataLength e) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_invalid, e);
        }
    }

    public static String decodePayloadHex(String value) {
        return WifCodec.toHex(decode(value));
    }

    public static String addressHashHex(String address) {
        byte[] payload = decode(address);
        if (payload.length != 21) {
            throw new LocalizedErrors.IllegalArgumentException(
                    R.string.error_bitcoin_address_format_unsupported);
        }
        return WifCodec.toHex(Arrays.copyOfRange(payload, 1, payload.length));
    }
}
