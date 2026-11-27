package wallet.watchonly;

import org.bitcoinj.base.Base58;

/**
 * Small codec for BIP32 extended public keys and SLIP-132 version prefixes.
 *
 * <p>An extended key payload is 78 bytes: a four-byte version followed by the
 * 74-byte BIP32 key data (depth, parent fingerprint, child number, chain code,
 * and compressed public key). Base58Check adds and verifies the checksum.</p>
 */
final class ExtendedPublicKeyCodec {
    static final int EXTENDED_KEY_PAYLOAD_LENGTH = 78;
    private static final int VERSION_LENGTH = 4;
    private static final int KEY_DATA_LENGTH = EXTENDED_KEY_PAYLOAD_LENGTH - VERSION_LENGTH;

    private ExtendedPublicKeyCodec() {
    }

    /** Reads the four-byte extended-key version in network byte order. */
    static int readVersion(byte[] payload) {
        requirePayload(payload);
        return ((payload[0] & 0xff) << 24)
                | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8)
                | (payload[3] & 0xff);
    }

    /** Returns a copy of the 74-byte BIP32 key data, excluding the version. */
    static byte[] keyData(byte[] payload) {
        requirePayload(payload);
        byte[] keyData = new byte[KEY_DATA_LENGTH];
        System.arraycopy(payload, VERSION_LENGTH, keyData, 0, KEY_DATA_LENGTH);
        return keyData;
    }

    /** Encodes BIP32 key data using the supplied standard or SLIP-132 version. */
    static String encode(int version, byte[] keyData) {
        if (keyData == null || keyData.length != KEY_DATA_LENGTH) {
            throw new IllegalArgumentException("Extended key data must be exactly 74 bytes");
        }
        return Base58.encodeChecked(version, keyData);
    }

    private static void requirePayload(byte[] payload) {
        if (payload == null || payload.length != EXTENDED_KEY_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Extended key payload must be exactly 78 bytes");
        }
    }
}
