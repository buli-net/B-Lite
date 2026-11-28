package wallet.watchonly;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import wallet.core.WalletAddressType;
import wallet.network.NetworkConfig;
import wallet.security.WalletSecurity;

/** Persists account-level public keys and registers their receive/change scripts as watched. */
public final class AccountXpubStore {
    private static final String PREFS = "account_xpub_watch";
    private static final int GAP_LIMIT = 100;
    private static final int MAX_LOOKAHEAD = 10_000;

    public static final class Format {
        public final String label;
        public final String walletType;
        public final int purpose;
        public final int mainnetVersion;
        public final int testnetVersion;

        private Format(String label, String walletType, int purpose,
                       int mainnetVersion, int testnetVersion) {
            this.label = label;
            this.walletType = walletType;
            this.purpose = purpose;
            this.mainnetVersion = mainnetVersion;
            this.testnetVersion = testnetVersion;
        }
    }

    private static final Format[] FORMATS = new Format[]{
            new Format("xpub", WalletAddressType.P2PKH, 44, 0x0488B21E, 0x043587CF),
            new Format("ypub", WalletAddressType.P2SH_P2WPKH, 49, 0x049D7CB2, 0x044A5262),
            new Format("zpub", WalletAddressType.P2WPKH, 84, 0x04B24746, 0x045F1CF6)
    };

    private AccountXpubStore() { }

    public static Format[] formats() {
        return FORMATS.clone();
    }

    public static Format formatAt(int index) {
        if (index < 0 || index >= FORMATS.length) return FORMATS[2];
        return FORMATS[index];
    }

    public static String exportAccountKey(Wallet wallet, DeterministicSeed seed,
                                          BitcoinNetwork network, Format format) {
        if (wallet == null || seed == null || network == null || format == null) {
            throw new IllegalArgumentException("Account key export data is missing");
        }
        byte[] sourceSeedBytes = seed.getSeedBytes();
        if (sourceSeedBytes == null || sourceSeedBytes.length < 16) {
            throw new IllegalStateException("The wallet HD seed is unavailable");
        }
        byte[] seedBytes = Arrays.copyOf(sourceSeedBytes, sourceSeedBytes.length);
        DeterministicKey key = null;
        try {
            key = HDKeyDerivation.createMasterPrivateKey(seedBytes);
            int coinType = network == BitcoinNetwork.MAINNET ? 0 : 1;
            key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(format.purpose, true));
            key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(coinType, true));
            key = HDKeyDerivation.deriveChildKey(key, new ChildNumber(0, true));

            // Keep only the public account node before serialization. No private
            // bytes or private parent chain are needed for an account xpub.
            key = key.dropPrivateBytes().dropParent();
            // bitcoinj 0.17.1's NetworkParameters registry does not resolve Signet
            // for extended-key serialization. Signet uses the test-network BIP32
            // version family, so serialize as testnet before applying SLIP-132.
            String standard = key.serializePubB58(keySerializationNetwork(network));
            byte[] payload = Base58.decodeChecked(standard);
            if (payload == null || payload.length != 78) {
                throw new IllegalStateException("The HD account key has an invalid encoding");
            }
            byte[] publicPayload = Arrays.copyOfRange(payload, 4, payload.length);
            int version = network == BitcoinNetwork.MAINNET
                    ? format.mainnetVersion : format.testnetVersion;
            return Base58.encodeChecked(version, publicPayload);
        } finally {
            Arrays.fill(seedBytes, (byte) 0);
        }
    }

    /** Imports a mainnet xpub/ypub/zpub or test-network tpub/upub/vpub account key. */
    public static int importAccountKey(Context context, Wallet wallet, String encoded,
                                       long creationTimeSeconds) throws Exception {
        if (context == null || wallet == null || encoded == null || encoded.trim().isEmpty()) {
            throw new IllegalArgumentException("An account public key is required");
        }
        BitcoinNetwork network = NetworkConfig.get(context);
        Format format = detectFormat(encoded.trim(), network);
        String normalized = normalizeToStandardXpub(encoded.trim(), network, format);
        DeterministicKey accountKey = DeterministicKey.deserializeB58(
                normalized, keySerializationNetwork(network));
        if (accountKey.getDepth() != 3 || accountKey.hasPrivKey()) {
            throw new IllegalArgumentException("An account-level extended public key is required");
        }
        String canonical = encoded.trim();
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = preferencesKey(network);
        String stored = preferences.getString(key, "[]");
        org.json.JSONArray records = new org.json.JSONArray(stored);
        for (int i = 0; i < records.length(); i++) {
            org.json.JSONObject record = records.optJSONObject(i);
            if (record != null && canonical.equals(record.optString("key"))) return -1;
        }
        org.json.JSONObject record = new org.json.JSONObject();
        record.put("key", canonical);
        record.put("type", format.walletType);
        record.put("birthday", Math.max(0L, creationTimeSeconds));
        records.put(record);
        if (!preferences.edit().putString(key, records.toString()).commit()) {
            throw new IllegalStateException("The account public key could not be saved");
        }
        return ensureLookahead(context, wallet);
    }

    /** Re-registers imported account scripts after the wallet is opened and extends its gap window. */
    public static int ensureLookahead(Context context, Wallet wallet) throws Exception {
        if (context == null || wallet == null) return 0;
        Set<Script> existing = new HashSet<>(wallet.getWatchedScripts());
        Set<Script> additions = importedAccountScripts(context, wallet);
        additions.removeAll(existing);
        if (additions.isEmpty()) return 0;
        return wallet.addWatchedScripts(new ArrayList<>(additions));
    }

    /** Returns the scripts derived from all imported account keys, including receive and change. */
    public static Set<Script> importedAccountScripts(Context context, Wallet wallet) throws Exception {
        Set<Script> result = new HashSet<>();
        if (context == null || wallet == null) return result;
        BitcoinNetwork network = NetworkConfig.get(context);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        org.json.JSONArray records = new org.json.JSONArray(
                preferences.getString(preferencesKey(network), "[]"));

        for (int recordIndex = 0; recordIndex < records.length(); recordIndex++) {
            org.json.JSONObject record = records.optJSONObject(recordIndex);
            if (record == null) continue;
            String encoded = record.optString("key", "");
            String walletType = WalletAddressType.normalize(
                    record.optString("type", WalletAddressType.P2WPKH));
            Format format = formatForWalletType(walletType);
            if (encoded.isEmpty() || format == null) continue;
            String normalized = normalizeToStandardXpub(encoded, network, format);
            DeterministicKey account = DeterministicKey.deserializeB58(
                    normalized, keySerializationNetwork(network));
            if (account.getDepth() != 3 || account.hasPrivKey()) continue;

            for (int branch = 0; branch <= 1; branch++) {
                DeterministicKey branchKey = HDKeyDerivation.deriveChildKey(
                        account, new ChildNumber(branch, false));
                List<Script> branchScripts = new ArrayList<>();
                int limit = GAP_LIMIT;
                while (true) {
                    if (limit > MAX_LOOKAHEAD) break;
                    branchScripts.clear();
                    for (int index = 0; index < limit; index++) {
                        DeterministicKey child = HDKeyDerivation.deriveChildKey(
                                branchKey, new ChildNumber(index, false));
                        branchScripts.add(WalletAddressType.scriptForKey(
                                wallet.getParams(), child, walletType));
                    }
                    int highestUsed = highestUsedIndex(wallet, branchScripts);
                    if (highestUsed < limit - GAP_LIMIT || limit >= MAX_LOOKAHEAD) break;
                    limit = Math.min(MAX_LOOKAHEAD, limit + GAP_LIMIT);
                }
                result.addAll(branchScripts);
            }
        }
        return result;
    }

    public static int importedAccountCount(Context context) {
        if (context == null) return 0;
        try {
            org.json.JSONArray records = new org.json.JSONArray(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(preferencesKey(NetworkConfig.get(context)), "[]"));
            return records.length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static long oldestCreationTime(Context context) {
        if (context == null) return 0L;
        long oldest = 0L;
        try {
            org.json.JSONArray records = new org.json.JSONArray(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(preferencesKey(NetworkConfig.get(context)), "[]"));
            for (int i = 0; i < records.length(); i++) {
                org.json.JSONObject record = records.optJSONObject(i);
                if (record == null) continue;
                long birthday = record.optLong("birthday", 0L);
                if (oldest == 0L || (birthday > 0L && birthday < oldest)) oldest = birthday;
            }
        } catch (Exception ignored) { }
        return oldest;
    }

    private static int highestUsedIndex(Wallet wallet, List<Script> scripts) {
        java.util.Map<Script, Integer> indices = new java.util.HashMap<>();
        for (int i = 0; i < scripts.size(); i++) indices.put(scripts.get(i), i);
        int highest = -1;
        for (Transaction transaction : wallet.getTransactions(true)) {
            for (TransactionOutput output : transaction.getOutputs()) {
                Integer index = indices.get(output.getScriptPubKey());
                if (index != null && index > highest) highest = index;
            }
        }
        return highest;
    }

    private static Format detectFormat(String encoded, BitcoinNetwork network) {
        byte[] payload = Base58.decodeChecked(encoded);
        if (payload == null || payload.length != 78) {
            throw new IllegalArgumentException("Invalid extended public key");
        }
        int version = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
        for (Format format : FORMATS) {
            int expected = network == BitcoinNetwork.MAINNET
                    ? format.mainnetVersion : format.testnetVersion;
            if (version == expected) return format;
        }
        throw new IllegalArgumentException("The account public key belongs to another network or is not public");
    }

    private static String normalizeToStandardXpub(String encoded, BitcoinNetwork network, Format format) {
        byte[] payload = Base58.decodeChecked(encoded);
        if (payload == null || payload.length != 78) {
            throw new IllegalArgumentException("Invalid extended public key");
        }
        byte[] body = Arrays.copyOfRange(payload, 4, payload.length);
        int standardVersion = network == BitcoinNetwork.MAINNET ? 0x0488B21E : 0x043587CF;
        return Base58.encodeChecked(standardVersion, body);
    }

    private static BitcoinNetwork keySerializationNetwork(BitcoinNetwork network) {
        return network == BitcoinNetwork.MAINNET
                ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET;
    }

    private static Format formatForWalletType(String walletType) {
        for (Format format : FORMATS) {
            if (format.walletType.equals(walletType)) return format;
        }
        return null;
    }

    private static String preferencesKey(BitcoinNetwork network) {
        return "account_keys_" + network.name().toLowerCase(java.util.Locale.ROOT);
    }
}
