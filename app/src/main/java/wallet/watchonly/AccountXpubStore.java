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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import wallet.core.WalletAddressType;
import wallet.network.NetworkConfig;

/** Persists account-level public keys and registers their receive/change scripts as watched. */
public final class AccountXpubStore {
    private static final String PREFS = "account_xpub_watch";
    // The legacy repair must run once only. Reclassifying both namespaces on every
    // read/import/delete can move records while the user switches active networks.
    private static final String PREF_NETWORK_MIGRATION_V1 = "account_keys_network_migration_v1_done";
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

    /** Raised when an extended-key version belongs to a different wallet network. */
    public static final class NetworkMismatchException extends IllegalArgumentException {
        public final BitcoinNetwork keyNetwork;
        public final BitcoinNetwork walletNetwork;

        private NetworkMismatchException(BitcoinNetwork keyNetwork, BitcoinNetwork walletNetwork) {
            super("The extended public key belongs to " + keyNetwork
                    + " but the active wallet is " + walletNetwork);
            this.keyNetwork = keyNetwork;
            this.walletNetwork = walletNetwork;
        }
    }

    /** Public metadata for an imported account key; the full key is kept for actions only. */
    public static final class AccountInfo {
        public final String key;
        public final String type;
        public final String label;
        public final String preview;
        public final long birthday;
        public final BitcoinNetwork network;

        private AccountInfo(String key, String type, long birthday, BitcoinNetwork network) {
            this.key = key;
            this.type = type;
            this.birthday = birthday;
            this.network = network;
            this.preview = abbreviatedKey(key);
            this.label = network.name() + " · "
                    + type.toUpperCase(java.util.Locale.ROOT) + " · " + preview;
        }
    }

    /** One derived address, with the account key and BIP44 branch/index that own it. */
    public static final class AccountAddressInfo {
        public final String key;
        public final String accountLabel;
        public final String address;
        public final String walletType;
        public final int branch;
        public final int index;
        public final Script script;

        private AccountAddressInfo(String key, String accountLabel, String address,
                                   String walletType, int branch, int index, Script script) {
            this.key = key;
            this.accountLabel = accountLabel;
            this.address = address;
            this.walletType = walletType;
            this.branch = branch;
            this.index = index;
            this.script = script;
        }

        public String derivationPath() {
            return "m/" + branch + "/" + index;
        }
    }

    private static final Format[] FORMATS = new Format[]{
            new Format("Legacy", WalletAddressType.P2PKH, 44, 0x0488B21E, 0x043587CF),
            new Format("Nested SegWit", WalletAddressType.P2SH_P2WPKH, 49, 0x049D7CB2, 0x044A5262),
            new Format("Native SegWit", WalletAddressType.P2WPKH, 84, 0x04B24746, 0x045F1CF6)
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
        BitcoinNetwork walletNetwork = networkForWallet(wallet);
        if (network != walletNetwork) {
            throw new IllegalArgumentException("The selected network does not match the active wallet");
        }
        network = walletNetwork;
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
        String standard = publicAccount.serializePubB58(extendedKeySerializationNetwork(network));
        byte[] payload = Base58.decodeChecked(standard);
        if (payload == null || payload.length != 78) {
            throw new IllegalStateException("The HD account key has an invalid encoding");
        }
        byte[] publicPayload = Arrays.copyOfRange(payload, 4, payload.length);
        int version = network == BitcoinNetwork.MAINNET
                ? format.mainnetVersion : format.testnetVersion;
        return encodeExtendedKey(version, publicPayload);
    }

    /** Imports a SLIP-132 account key only into the matching active wallet network. */
    public static int importAccountKey(Context context, Wallet wallet, String encoded,
                                       long creationTimeSeconds) throws Exception {
        return importAccountKey(context, wallet, encoded, creationTimeSeconds, null);
    }

    /**
     * Imports an account key. SLIP-132 prefixes (ypub/zpub/upub/vpub) identify the
     * script type themselves. Standard xpub/tpub prefixes do not: use the type
     * selected in the UI for those ambiguous prefixes.
     */
    public static synchronized int importAccountKey(Context context, Wallet wallet, String encoded,
                                                    long creationTimeSeconds, Format selectedFormat)
            throws Exception {
        if (context == null || wallet == null || encoded == null || encoded.trim().isEmpty()) {
            throw new IllegalArgumentException("An account public key is required");
        }
        BitcoinNetwork network = networkForWallet(wallet);
        String canonical = encoded.replaceAll("[\\s\\p{Z}\\uFEFF\\u200B]+", "");
        validateKeyNetwork(canonical, network);
        Format detectedFormat = detectFormat(canonical);
        Format format = hasAmbiguousStandardPrefix(canonical) && selectedFormat != null
                ? selectedFormat : detectedFormat;
        DeterministicKey accountKey = deserializeAccountKey(canonical, network, format);
        migrateMisfiledAccountRecords(context);
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
        record.put("network", network.name());
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
        BitcoinNetwork network = networkForWallet(wallet);
        migrateMisfiledAccountRecords(context);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        org.json.JSONArray records = new org.json.JSONArray(
                preferences.getString(preferencesKey(network), "[]"));
        for (int i = 0; i < records.length(); i++) {
            org.json.JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            String encoded = record.optString("key", "");
            String walletType = WalletAddressType.normalize(
                    record.optString("type", WalletAddressType.P2WPKH));
            Format format = formatForWalletType(walletType);
            if (encoded.isEmpty() || format == null) continue;
            try {
                DeterministicKey account = deserializeAccountKey(encoded, network, format);
                result.addAll(deriveAccountScripts(wallet, account, walletType));
            } catch (IllegalArgumentException invalidKey) {
                // Continue with other stored account keys.
            }
        }
        return result;
    }

    /**
     * Returns every currently watched lookahead address grouped by its imported account key.
     * The same derivation routine is used for Mainnet and Signet; only the active wallet's
     * address parameters and the stored network namespace differ.
     */
    public static List<AccountAddressInfo> importedAccountAddresses(Context context, Wallet wallet)
            throws Exception {
        List<AccountAddressInfo> result = new ArrayList<>();
        if (context == null || wallet == null) return result;
        BitcoinNetwork network = networkForWallet(wallet);
        migrateMisfiledAccountRecords(context);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        org.json.JSONArray records = new org.json.JSONArray(
                preferences.getString(preferencesKey(network), "[]"));
        for (int i = 0; i < records.length(); i++) {
            org.json.JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            String encoded = record.optString("key", "");
            String walletType = WalletAddressType.normalize(
                    record.optString("type", WalletAddressType.P2WPKH));
            Format format = formatForWalletType(walletType);
            if (encoded.isEmpty() || format == null) continue;
            try {
                DeterministicKey account = deserializeAccountKey(encoded, network, format);
                AccountInfo owner = new AccountInfo(encoded, format.label,
                        record.optLong("birthday", 0L), network);
                result.addAll(deriveAccountAddressInfos(wallet, account, walletType, owner));
            } catch (IllegalArgumentException invalidKey) {
                // One damaged record must not hide the other imported account keys.
            }
        }
        return result;
    }

    /** Returns the derived addresses for one key, used by its expandable management group. */
    public static List<AccountAddressInfo> importedAccountAddresses(
            Context context, Wallet wallet, String encodedKey) throws Exception {
        List<AccountAddressInfo> result = new ArrayList<>();
        if (context == null || wallet == null || encodedKey == null || encodedKey.isEmpty()) {
            return result;
        }
        BitcoinNetwork network = networkForWallet(wallet);
        migrateMisfiledAccountRecords(context);
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        org.json.JSONArray records = new org.json.JSONArray(
                preferences.getString(preferencesKey(network), "[]"));
        for (int i = 0; i < records.length(); i++) {
            org.json.JSONObject record = records.optJSONObject(i);
            if (record == null || !encodedKey.equals(record.optString("key", ""))) continue;
            String walletType = WalletAddressType.normalize(
                    record.optString("type", WalletAddressType.P2WPKH));
            Format format = formatForWalletType(walletType);
            if (format == null) return result;
            DeterministicKey account = deserializeAccountKey(encodedKey, network, format);
            AccountInfo owner = new AccountInfo(encodedKey, format.label,
                    record.optLong("birthday", 0L), network);
            result.addAll(deriveAccountAddressInfos(wallet, account, walletType, owner));
            break;
        }
        return result;
    }

    /**
     * Returns imported account keys for the active wallet network.
     *
     * During a network switch WalletAppKit is stopped before the replacement wallet is
     * ready, so UI callers can temporarily have no Wallet instance. Returning an empty
     * list in that interval made the saved account list look deleted. NetworkConfig is
     * updated before the replacement kit starts; use it only while wallet is null, and
     * prefer the Wallet's own network whenever one is available.
     */
    public static List<AccountInfo> importedAccounts(Context context, Wallet wallet) {
        if (context == null) return Collections.emptyList();
        BitcoinNetwork network = wallet != null
                ? networkForWallet(wallet)
                : NetworkConfig.get(context.getApplicationContext());
        if (network != BitcoinNetwork.MAINNET && network != BitcoinNetwork.SIGNET) {
            return Collections.emptyList();
        }
        return importedAccountsForNetwork(context, network);
    }

    /** Backward-compatible accessor for callers that do not yet hold a wallet instance. */
    public static List<AccountInfo> importedAccounts(Context context) {
        return context == null ? Collections.emptyList()
                : importedAccountsForNetwork(context, NetworkConfig.get(context));
    }

    private static List<AccountInfo> importedAccountsForNetwork(
            Context context, BitcoinNetwork network) {
        try {
            migrateMisfiledAccountRecords(context);
            org.json.JSONArray records = new org.json.JSONArray(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(preferencesKey(network), "[]"));
            List<AccountInfo> result = new ArrayList<>();
            for (int i = 0; i < records.length(); i++) {
                org.json.JSONObject record = records.optJSONObject(i);
                if (record == null) continue;
                String key = record.optString("key", "");
                String type = WalletAddressType.normalize(
                        record.optString("type", WalletAddressType.P2WPKH));
                Format format = formatForWalletType(type);
                if (key.isEmpty() || format == null) continue;
                result.add(new AccountInfo(key, format.label,
                        record.optLong("birthday", 0L), network));
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * Maps every currently derived account script to the key that generated it.
     * This is used by wallet selectors so lookahead addresses are not anonymous.
     */
    public static Map<Script, String> accountLabelsByScript(Context context, Wallet wallet)
            throws Exception {
        Map<Script, String> labels = new LinkedHashMap<>();
        for (AccountAddressInfo address : importedAccountAddresses(context, wallet)) {
            String owner = address.accountLabel + " · " + address.derivationPath();
            String previous = labels.get(address.script);
            if (previous == null) {
                labels.put(address.script, owner);
            } else if (!previous.contains(owner)) {
                labels.put(address.script, previous + " / " + owner);
            }
        }
        return labels;
    }

    /** Removes one imported account key and only its scripts not shared by another xpub. */
    public static synchronized int removeAccountKey(Context context, Wallet wallet, String encodedKey)
            throws Exception {
        if (context == null || wallet == null || encodedKey == null || encodedKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Account key is required");
        }
        Context appContext = context.getApplicationContext();
        BitcoinNetwork network = networkForWallet(wallet);
        migrateMisfiledAccountRecords(context);
        SharedPreferences preferences = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String prefKey = preferencesKey(network);
        String stored = preferences.getString(prefKey, "[]");
        org.json.JSONArray records = new org.json.JSONArray(stored);
        org.json.JSONArray kept = new org.json.JSONArray();
        Set<Script> removedAccountScripts = new HashSet<>();
        boolean found = false;

        for (int i = 0; i < records.length(); i++) {
            org.json.JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            String key = record.optString("key", "");
            if (encodedKey.equals(key)) {
                found = true;
                String walletType = WalletAddressType.normalize(
                        record.optString("type", WalletAddressType.P2WPKH));
                Format format = formatForWalletType(walletType);
                if (format != null) {
                    DeterministicKey account = deserializeAccountKey(key, network, format);
                    // Lookahead can grow after activity near the gap-limit edge. The old
                    // implementation derived only the current window, so scripts added by
                    // an earlier, larger window could survive deletion. Enumerate the full
                    // supported range when removing a key, then intersect with actual watches.
                    removedAccountScripts.addAll(deriveAccountScriptsForRemoval(
                            wallet, account, walletType));
                }
            } else {
                kept.put(record);
            }
        }
        if (!found) return 0;
        if (!preferences.edit().putString(prefKey, kept.toString()).commit()) {
            throw new IllegalStateException("Unable to update the imported account-key list");
        }

        try {
            // Preserve every address still derivable from another imported xpub,
            // including scripts from that key's older expanded lookahead window.
            Set<Script> retainedScripts = new HashSet<>();
            for (int i = 0; i < kept.length(); i++) {
                org.json.JSONObject record = kept.optJSONObject(i);
                if (record == null) continue;
                String retainedKey = record.optString("key", "");
                String retainedType = WalletAddressType.normalize(
                        record.optString("type", WalletAddressType.P2WPKH));
                Format retainedFormat = formatForWalletType(retainedType);
                if (retainedKey.isEmpty() || retainedFormat == null) continue;
                try {
                    DeterministicKey retainedAccount = deserializeAccountKey(
                            retainedKey, network, retainedFormat);
                    retainedScripts.addAll(deriveAccountScriptsForRemoval(
                            wallet, retainedAccount, retainedType));
                } catch (IllegalArgumentException invalidRetainedKey) {
                    // Skip damaged records but keep all other valid account scopes.
                }
            }
            removedAccountScripts.removeAll(retainedScripts);
            removedAccountScripts.retainAll(new HashSet<>(wallet.getWatchedScripts()));
            if (!removedAccountScripts.isEmpty()) {
                List<Script> removalList = new ArrayList<>(removedAccountScripts);
                wallet.removeWatchedScripts(removalList);
                Set<Script> remaining = new HashSet<>(wallet.getWatchedScripts());
                remaining.retainAll(removedAccountScripts);
                if (!remaining.isEmpty()) {
                    throw new IllegalStateException(
                            "The wallet still contains addresses owned only by the deleted account key");
                }
            }
            return removedAccountScripts.size();
        } catch (Exception error) {
            preferences.edit().putString(prefKey, stored).commit();
            throw error;
        }
    }

    /** Derives the complete bounded range through the same branch routine used by import/display. */
    private static Set<Script> deriveAccountScriptsForRemoval(
            Wallet wallet, DeterministicKey account, String walletType) {
        Set<Script> result = new HashSet<>();
        if (wallet == null || account == null) return result;
        for (int branch = 0; branch <= 1; branch++) {
            DeterministicKey branchKey = HDKeyDerivation.deriveChildKey(
                    account, new ChildNumber(branch, false));
            result.addAll(deriveAccountBranchScripts(wallet, branchKey, walletType, true));
        }
        return result;
    }

    private static Set<Script> deriveAccountScripts(Wallet wallet, DeterministicKey account,
                                                     String walletType) {
        Set<Script> result = new HashSet<>();
        if (wallet == null || account == null) return result;
        for (int branch = 0; branch <= 1; branch++) {
            DeterministicKey branchKey = HDKeyDerivation.deriveChildKey(
                    account, new ChildNumber(branch, false));
            result.addAll(deriveAccountBranchScripts(wallet, branchKey, walletType));
        }
        return result;
    }

    private static List<AccountAddressInfo> deriveAccountAddressInfos(
            Wallet wallet, DeterministicKey account, String walletType, AccountInfo owner) {
        List<AccountAddressInfo> result = new ArrayList<>();
        if (wallet == null || account == null || owner == null) return result;
        for (int branch = 0; branch <= 1; branch++) {
            DeterministicKey branchKey = HDKeyDerivation.deriveChildKey(
                    account, new ChildNumber(branch, false));
            List<Script> scripts = deriveAccountBranchScripts(wallet, branchKey, walletType);
            for (int index = 0; index < scripts.size(); index++) {
                Script script = scripts.get(index);
                try {
                    String address = script.getToAddress(wallet.getParams()).toString();
                    result.add(new AccountAddressInfo(owner.key, owner.label, address,
                            walletType, branch, index, script));
                } catch (Exception ignored) {
                    // Skip an address that cannot be rendered, without hiding other children.
                }
            }
        }
        return result;
    }

    /**
     * Shared branch derivation for all networks and operations. Normal import/display
     * extends the BIP44 gap window from wallet history; deletion requests the full
     * bounded range to catch scripts left by earlier, larger lookahead windows.
     */
    private static List<Script> deriveAccountBranchScripts(
            Wallet wallet, DeterministicKey branchKey, String walletType) {
        return deriveAccountBranchScripts(wallet, branchKey, walletType, false);
    }

    private static List<Script> deriveAccountBranchScripts(
            Wallet wallet, DeterministicKey branchKey, String walletType, boolean fullRange) {
        List<Script> branchScripts = new ArrayList<>();
        if (wallet == null || branchKey == null) return branchScripts;
        int limit = fullRange ? MAX_LOOKAHEAD : GAP_LIMIT;
        while (limit <= MAX_LOOKAHEAD) {
            branchScripts.clear();
            for (int index = 0; index < limit; index++) {
                DeterministicKey child = HDKeyDerivation.deriveChildKey(
                        branchKey, new ChildNumber(index, false));
                branchScripts.add(WalletAddressType.scriptForKey(
                        wallet.getParams(), child, walletType));
            }
            if (fullRange) break;
            int highestUsed = highestUsedIndex(wallet, branchScripts);
            if (highestUsed < limit - GAP_LIMIT || limit >= MAX_LOOKAHEAD) break;
            limit = Math.min(MAX_LOOKAHEAD, limit + GAP_LIMIT);
        }
        return new ArrayList<>(branchScripts);
    }

    private static String abbreviatedKey(String key) {
        if (key == null || key.length() <= 20) return key == null ? "" : key;
        return key.substring(0, 8) + "…" + key.substring(key.length() - 8);
    }

    public static int importedAccountCount(Context context, Wallet wallet) {
        if (context == null || wallet == null) return 0;
        return importedAccountCountForNetwork(context, networkForWallet(wallet));
    }

    public static int importedAccountCount(Context context) {
        return context == null ? 0
                : importedAccountCountForNetwork(context, NetworkConfig.get(context));
    }

    private static int importedAccountCountForNetwork(Context context, BitcoinNetwork network) {
        try {
            migrateMisfiledAccountRecords(context);
            org.json.JSONArray records = new org.json.JSONArray(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(preferencesKey(network), "[]"));
            return records.length();
        } catch (Exception ignored) {
            return 0;
        }
    }

    public static long oldestCreationTime(Context context, Wallet wallet) {
        if (context == null || wallet == null) return 0L;
        return oldestCreationTimeForNetwork(context, networkForWallet(wallet));
    }

    public static long oldestCreationTime(Context context) {
        return context == null ? 0L
                : oldestCreationTimeForNetwork(context, NetworkConfig.get(context));
    }

    private static long oldestCreationTimeForNetwork(Context context, BitcoinNetwork network) {
        long oldest = 0L;
        try {
            migrateMisfiledAccountRecords(context);
            org.json.JSONArray records = new org.json.JSONArray(context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(preferencesKey(network), "[]"));
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

    /**
     * Decodes an account-level public key.
     *
     * Standard BIP32 account keys have depth 3. Some exporters deliberately clear
     * the depth byte to hide the derivation path while retaining the account node's
     * parent fingerprint and hardened child number. Accept that detached form too,
     * but do not mistake an ordinary master xpub (zero depth and zero parent
     * fingerprint) for an account key.
     */
    private static DeterministicKey deserializeAccountKey(String encoded,
                                                           BitcoinNetwork network,
                                                           Format expectedFormat) {
        byte[] payload = Base58.decodeChecked(encoded);
        if (payload == null || payload.length != 78) {
            throw new IllegalArgumentException("Invalid extended public key");
        }

        Format actualFormat = detectFormat(encoded);
        // Standard xpub/tpub prefixes are deliberately script-type agnostic. The
        // selected wallet type is authoritative for those keys; SLIP-132 prefixes
        // must still match their encoded script type.
        if (expectedFormat != null && !hasAmbiguousStandardPrefix(encoded)
                && !expectedFormat.walletType.equals(actualFormat.walletType)) {
            throw new IllegalArgumentException("The extended public-key format does not match the saved account type");
        }

        int depth = payload[4] & 0xff;
        int parentFingerprint = ((payload[5] & 0xff) << 24)
                | ((payload[6] & 0xff) << 16)
                | ((payload[7] & 0xff) << 8)
                | (payload[8] & 0xff);
        int childNumber = ((payload[9] & 0xff) << 24)
                | ((payload[10] & 0xff) << 16)
                | ((payload[11] & 0xff) << 8)
                | (payload[12] & 0xff);

        boolean standardAccountDepth = depth == 3;
        boolean detachedAccountDepth = depth == 0
                && parentFingerprint != 0
                && (childNumber & 0x80000000) != 0;
        if (!standardAccountDepth && !detachedAccountDepth) {
            throw new IllegalArgumentException(
                    "An account-level extended public key is required (BIP32 depth must be 3, or a detached account key)");
        }

        // All supported xpub/ypub/zpub/tpub/upub/vpub version bytes identify
        // public extended keys. Also verify the key-data marker before decoding.
        int publicKeyMarker = payload[45] & 0xff;
        if (publicKeyMarker != 0x02 && publicKeyMarker != 0x03) {
            throw new IllegalArgumentException("A public extended key is required; private extended keys are not accepted");
        }

        String normalized = normalizeToStandardXpub(encoded, network);
        DeterministicKey account = DeterministicKey.deserializeB58(
                normalized, extendedKeySerializationNetwork(network));
        if (!account.isWatching()) {
            throw new IllegalArgumentException("A public extended key is required; private key material is not accepted");
        }
        return account;
    }

    /** Standard xpub/tpub version bytes do not identify the derivation script type. */
    private static boolean hasAmbiguousStandardPrefix(String encoded) {
        byte[] payload = Base58.decodeChecked(encoded);
        if (payload == null || payload.length != 78) return false;
        int version = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
        return version == 0x0488B21E || version == 0x043587CF;
    }

    /** Resolve the network from the Wallet itself so asynchronous UI/network switches cannot misfile keys. */
    public static BitcoinNetwork networkForWallet(Wallet wallet) {
        if (wallet == null) throw new IllegalArgumentException("Wallet is required");
        if (BitcoinNetwork.MAINNET.equals(wallet.network())) return BitcoinNetwork.MAINNET;
        if (BitcoinNetwork.SIGNET.equals(wallet.network())) return BitcoinNetwork.SIGNET;
        throw new IllegalArgumentException("Unsupported wallet network");
    }

    /** Mainnet uses xpub/ypub/zpub; Signet uses the shared test-network tpub/upub/vpub family. */
    private static void validateKeyNetwork(String encoded, BitcoinNetwork walletNetwork) {
        BitcoinNetwork keyNetwork = keyNetworkFromVersion(encoded);
        if (keyNetwork == null) {
            throw new IllegalArgumentException("Unsupported extended public-key version");
        }
        if (keyNetwork != walletNetwork) {
            throw new NetworkMismatchException(keyNetwork, walletNetwork);
        }
    }

    private static Format detectFormat(String encoded) {
        byte[] payload = Base58.decodeChecked(encoded);
        if (payload == null || payload.length != 78) {
            throw new IllegalArgumentException("Invalid extended public key");
        }
        int version = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
        // The Mainnet or Signet-family version was already checked by
        // validateKeyNetwork(); this method now only identifies script format.
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

    /**
     * Selects bitcoinj's BIP32 extended-key serialization parameters only.
     * Signet shares the test-network extended-key version family, so bitcoinj
     * uses TESTNET here to encode/decode xpub payloads. This does not select
     * the wallet's chain, address network, or synchronization network.
     */
    private static BitcoinNetwork extendedKeySerializationNetwork(BitcoinNetwork network) {
        return network == BitcoinNetwork.MAINNET
                ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET;
    }

    private static Format formatForWalletType(String walletType) {
        for (Format format : FORMATS) {
            if (format.walletType.equals(walletType)) return format;
        }
        return null;
    }

    /**
     * One-time repair for records written by older builds under the wrong preference
     * namespace. Never reclassify the two live lists during normal reads or imports:
     * after this migration, each network namespace is authoritative and independent.
     * The key version family is used only for this initial repair.
     */
    private static synchronized void migrateMisfiledAccountRecords(Context context) {
        if (context == null) return;
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (preferences.getBoolean(PREF_NETWORK_MIGRATION_V1, false)) return;

        String mainKey = preferencesKey(BitcoinNetwork.MAINNET);
        String signetKey = preferencesKey(BitcoinNetwork.SIGNET);
        String mainStored = preferences.getString(mainKey, "[]");
        String signetStored = preferences.getString(signetKey, "[]");
        try {
            org.json.JSONArray mainRecords = new org.json.JSONArray(mainStored);
            org.json.JSONArray signetRecords = new org.json.JSONArray(signetStored);
            org.json.JSONArray mainFixed = new org.json.JSONArray();
            org.json.JSONArray signetFixed = new org.json.JSONArray();

            appendMigratedRecords(mainRecords, BitcoinNetwork.MAINNET, mainFixed, signetFixed);
            appendMigratedRecords(signetRecords, BitcoinNetwork.SIGNET, mainFixed, signetFixed);

            // Commit both repaired namespaces and the completion marker atomically.
            // Once marked, subsequent operations never rewrite the other network's list.
            boolean committed = preferences.edit()
                    .putString(mainKey, mainFixed.toString())
                    .putString(signetKey, signetFixed.toString())
                    .putBoolean(PREF_NETWORK_MIGRATION_V1, true)
                    .commit();
            if (!committed) {
                throw new IllegalStateException("Unable to separate Mainnet and Signet account-key records");
            }
        } catch (org.json.JSONException invalidStore) {
            // Do not set the marker: a later launch can retry after the stored data is repaired.
            throw new IllegalStateException("The saved account-key list is invalid", invalidStore);
        }
    }

    private static void appendMigratedRecords(org.json.JSONArray source,
                                              BitcoinNetwork sourceNetwork,
                                              org.json.JSONArray mainTarget,
                                              org.json.JSONArray signetTarget)
            throws org.json.JSONException {
        for (int i = 0; i < source.length(); i++) {
            org.json.JSONObject record = source.optJSONObject(i);
            if (record == null) continue;
            String encoded = record.optString("key", "");
            BitcoinNetwork keyNetwork = keyNetworkFromVersion(encoded);
            BitcoinNetwork destination = keyNetwork == null ? sourceNetwork : keyNetwork;
            org.json.JSONArray target = destination == BitcoinNetwork.MAINNET
                    ? mainTarget : signetTarget;
            boolean duplicate = false;
            for (int j = 0; j < target.length(); j++) {
                org.json.JSONObject existing = target.optJSONObject(j);
                if (existing != null && encoded.equals(existing.optString("key", ""))) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) target.put(record);
        }
    }

    private static BitcoinNetwork keyNetworkFromVersion(String encoded) {
        if (encoded == null || encoded.isEmpty()) return null;
        try {
            byte[] payload = Base58.decodeChecked(encoded);
            if (payload == null || payload.length != 78) return null;
            int version = ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16)
                    | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
            if (version == 0x0488B21E || version == 0x049D7CB2 || version == 0x04B24746) {
                return BitcoinNetwork.MAINNET;
            }
            if (version == 0x043587CF || version == 0x044A5262 || version == 0x045F1CF6) {
                return BitcoinNetwork.SIGNET;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Network-scoped data prevents Mainnet and Signet imports from mixing; all operations share this implementation. */
    private static String preferencesKey(BitcoinNetwork network) {
        return "account_keys_" + network.name().toLowerCase(java.util.Locale.ROOT);
    }
}
