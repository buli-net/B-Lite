package wallet.watchonly;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.SigNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.KeyChain;
import org.bitcoinj.wallet.KeyChainGroup;
import org.bitcoinj.wallet.Wallet;
import org.junit.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import wallet.core.WalletAddressType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the complete account-xpub export -> watch-only import path.
 * Uses a fixed BIP32 entropy so every key and address is reproducible.
 */
public class AccountXpubRoundTripTest {
    private static final int RANGE = 100;

    @Test
    public void mainnet_roundTripsReceiveAndChangeForAllSupportedAddressTypes() {
        assertRoundTrip(MainNetParams.get(), BitcoinNetwork.MAINNET, 44,
                WalletAddressType.P2PKH, "xpub");
        assertRoundTrip(MainNetParams.get(), BitcoinNetwork.MAINNET, 49,
                WalletAddressType.P2SH_P2WPKH, "ypub");
        assertRoundTrip(MainNetParams.get(), BitcoinNetwork.MAINNET, 84,
                WalletAddressType.P2WPKH, "zpub");
        assertRoundTrip(MainNetParams.get(), BitcoinNetwork.MAINNET, 86,
                WalletAddressType.P2TR, "xpub");
    }

    @Test
    public void signet_roundTripsReceiveAndChangeForAllSupportedAddressTypes() {
        assertRoundTrip(SigNetParams.get(), BitcoinNetwork.SIGNET, 44,
                WalletAddressType.P2PKH, "tpub");
        assertRoundTrip(SigNetParams.get(), BitcoinNetwork.SIGNET, 49,
                WalletAddressType.P2SH_P2WPKH, "upub");
        assertRoundTrip(SigNetParams.get(), BitcoinNetwork.SIGNET, 84,
                WalletAddressType.P2WPKH, "vpub");
        assertRoundTrip(SigNetParams.get(), BitcoinNetwork.SIGNET, 86,
                WalletAddressType.P2TR, "tpub");
    }

    @Test
    public void importerRejectsSlip132PrefixWhenSelectedAddressTypeDoesNotMatch() {
        NetworkParameters params = MainNetParams.get();
        Context.propagate(Context.getOrCreate(params));
        DeterministicKeyChain chain = newChain(84, BitcoinNetwork.MAINNET);
        Wallet wallet = newWallet(params, chain);
        String zpub = AccountXpubExporter.export(wallet).getEncodedKey();

        assertThrows(IllegalArgumentException.class, () ->
                XpubWatchOnlyImporter.deriveAccountAddresses(
                        params, zpub, WalletAddressType.P2PKH, 1));
    }

    private static void assertRoundTrip(NetworkParameters params, BitcoinNetwork network,
                                        int purpose, String addressType, String expectedPrefix) {
        // bitcoinj 0.17.1 requires a network Context on the current thread before
        // constructing deterministic key chains or wallets. Mainnet and Signet
        // tests must install the matching context independently.
        Context.propagate(Context.getOrCreate(params));
        DeterministicKeyChain sourceChain = newChain(purpose, network);
        Wallet sourceWallet = newWallet(params, sourceChain);

        AccountXpubExporter.ExportedAccountKey exported =
                AccountXpubExporter.export(sourceWallet);
        assertTrue("wrong extended-key prefix for purpose " + purpose,
                exported.getEncodedKey().startsWith(expectedPrefix));
        assertEquals(addressType, exported.getAddressType());
        String normalizedXpub = XpubWatchOnlyImporter.normalizePublicExtendedKey(
                params, exported.getEncodedKey(), addressType);
        DeterministicKey importedAccountKey = DeterministicKey.deserializeB58(
                normalizedXpub, params.network());
        assertFalse("exported account key must never contain private key bytes",
                importedAccountKey.hasPrivKey());

        // These are the source wallet's actual independently issued key branches:
        // RECEIVE_FUNDS is m/.../0/index and CHANGE is m/.../1/index.
        List<DeterministicKey> receiveKeys =
                sourceChain.getKeys(KeyChain.KeyPurpose.RECEIVE_FUNDS, RANGE);
        List<DeterministicKey> changeKeys =
                sourceChain.getKeys(KeyChain.KeyPurpose.CHANGE, RANGE);
        List<String> sourceAddresses = new ArrayList<>(RANGE * 2);
        for (DeterministicKey key : receiveKeys) {
            sourceAddresses.add(WalletAddressType.addressForKey(params, key, addressType).toString());
        }
        for (DeterministicKey key : changeKeys) {
            sourceAddresses.add(WalletAddressType.addressForKey(params, key, addressType).toString());
        }

        // Exercise the same public import method called by WatchOnlyWalletActivity.
        List<Address> imported = XpubWatchOnlyImporter.deriveAccountAddresses(
                params, exported.getEncodedKey(), addressType, RANGE);
        List<String> importedAddresses = new ArrayList<>(imported.size());
        for (Address address : imported) importedAddresses.add(address.toString());

        assertEquals("import must return 100 receive + 100 change addresses",
                RANGE * 2, importedAddresses.size());
        assertEquals("every imported address must match source wallet order and branch "
                        + "(purpose=" + purpose + ", network=" + network + ")",
                sourceAddresses, importedAddresses);

        // Match the actual WatchOnlyWalletActivity persistence step and verify
        // each derived address becomes the exact output script watched by wallet.
        Wallet watchOnlyWallet = new Wallet(params,
                KeyChainGroup.builder(params.network()).build());
        int added = watchOnlyWallet.addWatchedAddresses(imported);
        assertEquals("all imported scripts must be registered for watching",
                RANGE * 2, added);
        Set<Script> watchedScripts = new HashSet<>(watchOnlyWallet.getWatchedScripts());
        for (Address address : imported) {
            assertTrue("watch-only wallet is missing script for " + address,
                    watchedScripts.contains(ScriptBuilder.createOutputScript(address)));
        }
    }

    private static DeterministicKeyChain newChain(int purpose, BitcoinNetwork network) {
        String coinType = network == BitcoinNetwork.MAINNET ? "0H" : "1H";
        HDPath accountPath = HDPath.parsePath("M/" + purpose + "H/" + coinType + "/0H");
        byte[] entropy = new byte[16];
        for (int i = 0; i < entropy.length; i++) entropy[i] = (byte) (i + 1);
        return DeterministicKeyChain.builder()
                .entropy(entropy, Instant.ofEpochSecond(1))
                .accountPath(accountPath)
                // The test compares our address script builder for all purpose paths;
                // bitcoinj only natively serializes P2PKH/P2WPKH output-script types.
                .outputScriptType(ScriptType.P2WPKH)
                .build();
    }

    private static Wallet newWallet(NetworkParameters params, DeterministicKeyChain chain) {
        KeyChainGroup group = KeyChainGroup.builder(params.network()).addChain(chain).build();
        return new Wallet(params, group);
    }
}
