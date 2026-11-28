package wallet.watchonly;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.Base58;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import wallet.core.WalletAddressType;
import wallet.network.NetworkConfig;

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

    public static String exportAccountKey(Wallet wallet, AesKey authorizedKey,
                                          BitcoinNetwork network, Format format) {
        if (wallet == null || network == null || format == null) {
            throw new IllegalArgumentException("Account key export data is missing");
        }
        if (wallet.getKeyCrypter() != null
                && (authorizedKey == null || !wallet.checkAESKey(authorizedKey))) {
            throw new IllegalStateException("The wallet must be unlocked before exporting an account key");
        }

        List<DeterministicKeyChain> chains = wallet.getActiveKeyChains();
        for (int i = chains.size() - 1; i >= 0; i--) {
            DeterministicKeyChain sourceChain = chains.get(i);
            DeterministicSeed sourceSeed = sourceChain.getSeed();

            if (sourceSeed != null) {
                DeterministicSeed exportSeed = sourceSeed;
                // Decrypt a temporary keychain copy. Never mutate the live wallet.
                if (sourceSeed.isEncrypted()) {
                    if (authorizedKey == null) {
                        throw new IllegalStateException("The wallet must be unlocked before exporting an account key");
                    }
                    exportSeed = sourceChain.toDecrypted(authorizedKey).getSeed();
                }
                if (exportSeed == null || exportSeed.isEncrypted() || exportSeed.getSeedBytes() == null) {
                    throw new IllegalStateException("The HD seed is not available for account-key export");
                }

                // Derive the requested BIP purpose from the seed itself. getRootKey()
                // can be null for a restored/encrypted keychain even though its seed
                // is available; getWatchingKey() only returns the currently active
                // purpose and therefore cannot export all three SLIP-132 formats.
                DeterministicKey master = HDKeyDerivation.createMasterPrivateKey(exportSeed.getSeedBytes());
                int coinType = network == BitcoinNetwork.MAINNET ? 0 : 1;
                DeterministicKey account = HDKeyDerivation.deriveChildKey(
                        master, new ChildNumber(format.purpose, true));
                account = HDKeyDerivation.deriveChildKey(
                        account, new ChildNumber(coinType, true));
                account = HDKeyDerivation.deriveChildKey(
                        account, new ChildNumber(0, true));
                return encodeAccountKey(account, network, format);
            }

            // A watching HD wallet has no seed. Export only the account node it
            // actually watches and do not relabel it as another derivation purpose.
            DeterministicKey watchingKey = sourceChain.getWatchingKey();
            if (watchingKey != null && watchingKey.getDepth() == 3
                    && matchesOutputFormat(sourceChain.getOutputScriptType(), format)) {
                return encodeAccountKey(watchingKey, network, format);
            }
        }
        throw new IllegalStateException("This wallet has no exportable HD account key");
    }

    private static boolean matchesOutputFormat(ScriptType scriptType, Format format) {
        if (scriptType == null || format == null) return false;
        if (format.purpose == 44) return scriptType == ScriptType.P2PKH;
        if (format.purpose == 84) return scriptType == ScriptType.P2WPKH;
        return false;
    }

    private static String encodeAccountKey(DeterministicKey accountKey,
                                           BitcoinNetwork network, Format format) {
        if (accountKey == null || accountKey.getDepth() != 3) {
            throw new IllegalArgumentException("An account-level HD key is required");
        }
        // Serialize only the public node; never include private key bytes.
        DeterministicKey publicAccount = accountKey.dropPrivateBytes().dropParent();
        String standard = publicAccount.serializePubB58(keySerializationNetwork(network));
        byte[] payload = Base58.decodeChecked(standard);
        if (payload == null || payload.length != 78) {
            throw new IllegalStateException("The HD account key has an invalid encoding");
        }
        byte[] publicPayload = Arrays.copyOfRange(payload, 4, payload.length);
        int version = network == BitcoinNetwork.MAINNET
                ? format.mainnetVersion : format.testnetVersion;
        return encodeExtendedKey(version, publicPayload);
    }

    /** Imports a SLIP-132 account key and derives its addresses for the selected wallet network. */
    public static int importAccountKey(Context context, Wallet wallet, String encoded,
                                       long creationTimeSeconds) throws Exception {
        if (context == null || wallet == null || encoded == null || encoded.trim().isEmpty()) {
            throw new IllegalArgumentException("An account public key is required");
        }
        BitcoinNetwork network = NetworkConfig.get(context);
        String canonical = encoded.replaceAll("[\\s\\p{Z}\\uFEFF\\u200B]+", "");
        Format format = detectFormat(canonical);
        String normalized = normalizeToStandardXpub(canonical, network);
        DeterministicKey accountKey = DeterministicKey.deserializeB58(
                normalized, keySerializationNetwork(network));
        if (accountKey.getDepth() != 3 || accountKey.hasPrivKey()) {
            throw new IllegalArgumentException("An account-level extended public key is required");
        }
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
        try {
            return ensureLookahead(context, wallet);
        } catch (Exception error) {
            preferences.edit().putString(key, stored).commit();
            throw error;
        }
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
            String normalized = normalizeToStandardXpub(encoded, network);
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

    private static Format detectFormat(String encoded) {
        byte[] payload = Base58.decodeChecked(encoded);
        if (payload == null || payload.length != 78) {
            throw new IllegalArgumentException("Invalid extended public key");
        }
        int version = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
        // Extended public keys contain no cryptographic network binding. Accept
        // both standard mainnet and test-network version families, then encode
        // addresses for the wallet's currently selected network. This permits
        // importing an account key from another Bitcoin network intentionally.
        for (Format format : FORMATS) {
            if (version == format.mainnetVersion || version == format.testnetVersion) {
                return format;
            }
        }
        throw new IllegalArgumentException("Unsupported extended public-key version");
    }

    private static String normalizeToStandardXpub(String encoded, BitcoinNetwork network) {
        byte[] payload = Base58.decodeChecked(encoded);
        if (payload == null || payload.length != 78) {
            throw new IllegalArgumentException("Invalid extended public key");
        }
        byte[] body = Arrays.copyOfRange(payload, 4, payload.length);
        int standardVersion = network == BitcoinNetwork.MAINNET ? 0x0488B21E : 0x043587CF;
        return encodeExtendedKey(standardVersion, body);
    }

    /** Encodes a BIP32 extended-key payload with its four-byte version prefix. */
    private static String encodeExtendedKey(int version, byte[] keyPayload) {
        byte[] payload = new byte[4 + keyPayload.length];
        payload[0] = (byte) (version >>> 24);
        payload[1] = (byte) (version >>> 16);
        payload[2] = (byte) (version >>> 8);
        payload[3] = (byte) version;
        System.arraycopy(keyPayload, 0, payload, 4, keyPayload.length);

        byte[] checksum = Sha256Hash.hashTwice(payload);
        byte[] checked = Arrays.copyOf(payload, payload.length + 4);
        System.arraycopy(checksum, 0, checked, payload.length, 4);
        return Base58.encode(checked);
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
