package wallet.core;

import android.content.Context;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.SegwitAddress;
import org.bitcoinj.base.Sha256Hash;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.BigIntegers;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import wallet.ui.LocalizedErrors;
import wallet.main.R;

/** Address/script types supported by the paper-wallet and imported-key flows. */
public final class WalletAddressType {
    public static final String P2PKH = "P2PKH";
    public static final String P2WPKH = "P2WPKH";
    public static final String P2SH_P2WPKH = "P2SH-P2WPKH";
    public static final String P2TR = "P2TR";

    private WalletAddressType() {
    }

    public static String normalize(String type) {
        if (P2WPKH.equals(type)) return P2WPKH;
        if (P2SH_P2WPKH.equals(type)) return P2SH_P2WPKH;
        if (P2TR.equals(type)) return P2TR;
        return P2PKH;
    }

    public static boolean requiresCompressedKey(String type) {
        return !P2PKH.equals(normalize(type));
    }

    /** True when bitcoinj 0.17.1 can natively spend this imported key/address. */
    public static boolean isNativelySpendable(String type) {
        String normalized = normalize(type);
        return P2PKH.equals(normalized) || P2WPKH.equals(normalized);
    }

    public static Address addressForKey(NetworkParameters parameters, ECKey key, String type) {
        String normalized = normalize(type);
        switch (normalized) {
            case P2WPKH:
                return Address.fromKey(parameters, key, ScriptType.P2WPKH);
            case P2TR:
                return taprootAddress(parameters, key);
            case P2SH_P2WPKH:
                return scriptForKey(parameters, key, normalized).getToAddress(parameters);
            case P2PKH:
            default:
                return LegacyAddress.fromKey(parameters, key);
        }
    }

    /**
     * Creates a BIP341 key-path Taproot address from the key's internal public key.
     * bitcoinj 0.17.1 exposes ScriptType.P2TR but Address.fromKey(..., P2TR) is
     * not implemented on the legacy Address path, so construct the BIP341 tweak
     * explicitly and encode the resulting x-only output key as witness v1.
     */
    private static SegwitAddress taprootAddress(NetworkParameters parameters, ECKey key) {
        ECDomainParameters curve = ECKey.ecDomainParameters();
        ECPoint internalPoint = key.getPubKeyPoint().normalize();

        // BIP341 uses the x-only internal key, i.e. the even-Y representative.
        if (internalPoint.getAffineYCoord().toBigInteger().testBit(0)) {
            internalPoint = internalPoint.negate().normalize();
        }
        byte[] internalX = toFixed32(internalPoint.getAffineXCoord().toBigInteger());

        byte[] tweakHash = taggedHash("TapTweak", internalX);
        java.math.BigInteger tweak = BigIntegers.fromUnsignedByteArray(tweakHash);
        if (tweak.compareTo(curve.getN()) >= 0) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_taproot_invalid_tweak);
        }

        ECPoint outputPoint = internalPoint.add(curve.getG().multiply(tweak)).normalize();
        if (outputPoint.isInfinity()) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_taproot_invalid_output_key);
        }
        byte[] outputX = toFixed32(outputPoint.getAffineXCoord().toBigInteger());
        return SegwitAddress.fromProgram(parameters, 1, outputX);
    }

    private static byte[] taggedHash(String tag, byte[] message) {
        byte[] tagHash = Sha256Hash.hash(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Digest digest = SHA256Digest.newInstance();
        digest.update(tagHash, 0, tagHash.length);
        digest.update(tagHash, 0, tagHash.length);
        digest.update(message, 0, message.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    private static byte[] toFixed32(java.math.BigInteger value) {
        return BigIntegers.asUnsignedByteArray(32, value);
    }

    public static Script scriptForKey(NetworkParameters parameters, ECKey key, String type) {
        String normalized = normalize(type);
        switch (normalized) {
            case P2WPKH:
                return ScriptBuilder.createP2WPKHOutputScript(key);
            case P2SH_P2WPKH:
                return ScriptBuilder.createP2SHOutputScript(
                        ScriptBuilder.createP2WPKHOutputScript(key));
            case P2TR:
                return ScriptBuilder.createOutputScript(taprootAddress(parameters, key));
            case P2PKH:
            default:
                return ScriptBuilder.createP2PKHOutputScript(key);
        }
    }

    public static int labelResId(String type) {
        String normalized = normalize(type);
        if (P2WPKH.equals(normalized)) return R.string.paper_wallet_print_type_p2wpkh;
        if (P2SH_P2WPKH.equals(normalized)) return R.string.paper_wallet_print_type_p2sh_p2wpkh;
        if (P2TR.equals(normalized)) return R.string.paper_wallet_print_type_p2tr;
        return R.string.paper_wallet_print_type_p2pkh;
    }

    public static String label(Context context, String type) {
        return context.getString(labelResId(type));
    }

}
