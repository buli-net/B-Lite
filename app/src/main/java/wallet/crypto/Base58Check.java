package wallet.crypto;

import android.text.TextUtils;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.exceptions.AddressFormatException;

import java.util.Arrays;

import wallet.main.R;
import wallet.ui.LocalizedErrors;

/**
 * Thin app-facing adapter around bitcoinj Base58Check operations.
 * Keeps app-localized validation messages without reimplementing Base58 or checksums.
 */
public final class Base58Check {
    private Base58Check() { }

    /** Returns decoded version/payload bytes (the four-byte checksum is removed). */
    public static byte[] decode(String value) {
        if (TextUtils.isEmpty(value)) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_empty);
        }
        try {
            byte[] payload = Base58.decodeChecked(value);
            // bitcoinj allows the checksum-only encoding; this app's callers expect a version/data byte.
            if (payload.length == 0) {
                throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_invalid);
            }
            return payload;
        } catch (AddressFormatException.InvalidCharacter error) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_character_invalid);
        } catch (AddressFormatException.InvalidChecksum error) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_checksum_invalid);
        } catch (AddressFormatException.InvalidDataLength error) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_invalid);
        } catch (AddressFormatException error) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_base58_invalid);
        }
    }

    public static String decodePayloadHex(String value) {
        return WifCodec.toHex(decode(value));
    }

    /** Extracts the 20-byte hash from a Base58Check legacy address. */
    public static String addressHashHex(String address) {
        byte[] payload = decode(address);
        if (payload.length != 21) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_bitcoin_address_format_unsupported);
        }
        return WifCodec.toHex(Arrays.copyOfRange(payload, 1, payload.length));
    }
}
