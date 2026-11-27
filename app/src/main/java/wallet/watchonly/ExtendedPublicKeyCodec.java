package wallet.watchonly;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.Sha256Hash;

import java.util.Arrays;

/**
 * Base58Check codec for BIP32 extended public keys.
 *
 * Extended-key version fields are four bytes. bitcoinj Base58.encodeChecked(int, byte[])
 * is for one-byte version prefixes (addresses/WIF), so it must not be used for xpub/ypub/zpub.
 */
final class ExtendedPublicKeyCodec {
    static final int EXTENDED_KEY_PAYLOAD_LENGTH = 78;
    static final int EXTENDED_KEY_DATA_LENGTH = 74;
    private static final int VERSION_LENGTH = 4;
    private static final int CHECKSUM_LENGTH = 4;

    private ExtendedPublicKeyCodec() {
    }

    static String encode(int version, byte[] extendedKeyData) {
        if (extendedKeyData == null || extendedKeyData.length != EXTENDED_KEY_DATA_LENGTH) {
            throw new IllegalArgumentException("Extended key data must be exactly 74 bytes");
        }

        byte[] payload = new byte[EXTENDED_KEY_PAYLOAD_LENGTH];
        payload[0] = (byte) (version >>> 24);
        payload[1] = (byte) (version >>> 16);
        payload[2] = (byte) (version >>> 8);
        payload[3] = (byte) version;
        System.arraycopy(extendedKeyData, 0, payload, VERSION_LENGTH,
                extendedKeyData.length);

        byte[] checksum = Sha256Hash.hashTwice(payload);
        byte[] checked = Arrays.copyOf(payload, payload.length + CHECKSUM_LENGTH);
        System.arraycopy(checksum, 0, checked, payload.length, CHECKSUM_LENGTH);
        return Base58.encode(checked);
    }

    static int readVersion(byte[] payload) {
        if (payload == null || payload.length != EXTENDED_KEY_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Invalid extended key payload length");
        }
        return ((payload[0] & 0xff) << 24)
                | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8)
                | (payload[3] & 0xff);
    }

    static byte[] keyData(byte[] payload) {
        if (payload == null || payload.length != EXTENDED_KEY_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Invalid extended key payload length");
        }
        return Arrays.copyOfRange(payload, VERSION_LENGTH, payload.length);
    }
}
