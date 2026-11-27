package wallet.watchonly;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Base58;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import wallet.core.WalletAddressType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Derives a bounded BIP32 account-key address range for import into the wallet's watched set. */
final class XpubWatchOnlyImporter {
    static final int DEFAULT_RANGE = 100;
    static final int MAX_RANGE = 1000;

    private XpubWatchOnlyImporter() {
    }

    static List<Address> deriveAccountAddresses(NetworkParameters parameters, String encodedKey,
                                                String scriptType, int range) {
        if (parameters == null || encodedKey == null || encodedKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing account public key");
        }
        if (range < 1 || range > MAX_RANGE) {
            throw new IllegalArgumentException("Address range must be between 1 and " + MAX_RANGE);
        }

        String normalized = normalizePublicExtendedKey(parameters, encodedKey.trim(), scriptType);
        DeterministicKey account = DeterministicKey.deserializeB58(normalized, parameters.network());
        // bitcoinj's legacy BIP32 account paths can be shallower than the
        // BIP44-family depth-3 path. Account keys exported by this app may be
        // at depths 1-4; a master key (depth 0) is never accepted here.
        if (account.hasPrivKey() || account.getDepth() < 1 || account.getDepth() > 4) {
            throw new IllegalArgumentException("Expected a public account-level extended key");
        }

        List<Address> addresses = new ArrayList<>(range * 2);
        for (int branchIndex = 0; branchIndex <= 1; branchIndex++) {
            DeterministicKey branch = HDKeyDerivation.deriveChildKey(
                    account, new ChildNumber(branchIndex, false));
            for (int addressIndex = 0; addressIndex < range; addressIndex++) {
                DeterministicKey child = HDKeyDerivation.deriveChildKey(
                        branch, new ChildNumber(addressIndex, false));
                addresses.add(WalletAddressType.addressForKey(parameters, child, scriptType));
            }
        }
        return addresses;
    }

    /**
     * Normalizes standard SLIP-132 public prefixes (ypub/zpub/upub/vpub) to the
     * network's BIP32 xpub/tpub header. The selected script type remains explicit
     * in the UI and is used when the child address scripts are generated.
     */
    private static String normalizePublicExtendedKey(NetworkParameters parameters, String encoded,
                                                      String selectedScriptType) {
        byte[] payload = Base58.decodeChecked(encoded);
        if (payload.length != 78) {
            throw new IllegalArgumentException("Invalid extended public key length");
        }
        int version = ((payload[0] & 0xff) << 24)
                | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8)
                | (payload[3] & 0xff);
        boolean mainnet = parameters.network() == org.bitcoinj.base.BitcoinNetwork.MAINNET;
        boolean publicVersion;
        if (mainnet) {
            publicVersion = version == 0x0488b21e
                    || version == 0x049d7cb2
                    || version == 0x04b24746;
        } else {
            publicVersion = version == 0x043587cf
                    || version == 0x044a5262
                    || version == 0x045f1cf6;
        }
        if (!publicVersion) {
            throw new IllegalArgumentException("Extended public key does not match the selected network");
        }
        // SLIP-132 prefixes carry an output-type hint. Reject mismatched choices
        // instead of silently deriving a different address family from the same key.
        String encodedScriptType = null;
        if (mainnet && version == 0x049d7cb2 || !mainnet && version == 0x044a5262) {
            encodedScriptType = WalletAddressType.P2SH_P2WPKH;
        } else if (mainnet && version == 0x04b24746 || !mainnet && version == 0x045f1cf6) {
            encodedScriptType = WalletAddressType.P2WPKH;
        }
        if (encodedScriptType != null && !encodedScriptType.equals(WalletAddressType.normalize(selectedScriptType))) {
            throw new IllegalArgumentException("The extended public key prefix does not match the selected address type");
        }
        if (payload[45] != 0x02 && payload[45] != 0x03) {
            throw new IllegalArgumentException("Extended key does not contain a compressed public key");
        }
        byte[] keyData = Arrays.copyOfRange(payload, 4, payload.length);
        return Base58.encodeChecked(parameters.getBip32HeaderP2PKHpub(), keyData);
    }
}
