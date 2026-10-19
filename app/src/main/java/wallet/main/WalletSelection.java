package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.Wallet;

/** Stores and resolves the wallet/address currently selected in the main UI. */
public final class WalletSelection {

    private static final String PREFS = "wallet_selection";
    private static final String KEY_WATCH_ADDRESS = "selected_watch_address";
    private static final String KEY_IMPORTED_ADDRESS = "selected_imported_address";
    private static final String REQUEST_NESTED_PUBKEY_PREFIX = "request_nested_pubkey_";

    // Cache the expensive Request Nested-SegWit classification for each live wallet.
    // The watched-script set is part of the cache key; registration changes also
    // invalidate the cache. This preserves the baseline's Nested-SegWit-only rules.
    private static final class RequestScriptCacheEntry {
        final java.util.Set<Script> watchedScripts;
        final java.util.Set<Script> requestScripts;

        RequestScriptCacheEntry(java.util.Set<Script> watched, java.util.Set<Script> request) {
            watchedScripts = new java.util.HashSet<>(watched);
            requestScripts = java.util.Collections.unmodifiableSet(
                    new java.util.LinkedHashSet<>(request));
        }
    }

    private static final java.util.Map<Wallet, RequestScriptCacheEntry> REQUEST_SCRIPT_CACHE =
            new java.util.WeakHashMap<>();

    private static synchronized void invalidateRequestScriptCache(Wallet wallet) {
        if (wallet == null) REQUEST_SCRIPT_CACHE.clear();
        else REQUEST_SCRIPT_CACHE.remove(wallet);
    }

    private WalletSelection() {
    }

    private static SharedPreferences prefs(Context context) {
        BitcoinNetwork network = NetworkConfig.get(context);
        String name = network == BitcoinNetwork.MAINNET
                ? PREFS
                : PREFS + "_" + NetworkConfig.storageName(network);
        return context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public static String getSelectedWatchAddress(Context context) {
        String value = prefs(context).getString(KEY_WATCH_ADDRESS, null);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    public static void selectMain(Context context) {
        prefs(context).edit().remove(KEY_WATCH_ADDRESS).remove(KEY_IMPORTED_ADDRESS).apply();
    }

    public static void selectWatchAddress(Context context, String address) {
        if (address == null || address.trim().isEmpty()) {
            selectMain(context);
            return;
        }
        prefs(context).edit()
                .putString(KEY_WATCH_ADDRESS, address.trim())
                .remove(KEY_IMPORTED_ADDRESS)
                .apply();
    }

    public static void registerRequestNestedAddress(Context context, String address, byte[] publicKey) {
        if (context == null || address == null || address.trim().isEmpty()
                || publicKey == null || publicKey.length == 0) return;
        prefs(context).edit()
                .putString(REQUEST_NESTED_PUBKEY_PREFIX + address.trim(),
                        android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP))
                .apply();
        invalidateRequestScriptCache(null);
    }

    public static ECKey findRequestNestedKeyForScript(
            Context context, Wallet wallet, Script script) {
        if (context == null || wallet == null || script == null) return null;
        String address = addressForScript(script, wallet.getParams());
        if (address == null) return null;
        if (getImportedScripts(context, wallet).contains(script)) return null;

        // A script is a Request Nested-SegWit output only when that exact address
        // was registered by RequestCoinsActivity. Do not infer ownership merely
        // because a P2SH-P2WPKH script can be derived from some wallet/lookahead key.
        String encodedPublicKey = prefs(context).getString(
                REQUEST_NESTED_PUBKEY_PREFIX + address, null);
        if (encodedPublicKey == null) return null;
        try {
            byte[] publicKey = android.util.Base64.decode(
                    encodedPublicKey, android.util.Base64.NO_WRAP);
            ECKey key = findKeyByPublicKey(wallet, publicKey);
            if (key != null && isMatchingNestedScript(wallet, key, script)
                    && address.equals(addressForScript(
                            WalletAddressType.scriptForKey(
                                    wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH),
                            wallet.getParams()))) {
                return key;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static ECKey findKeyByPublicKey(Wallet wallet, byte[] publicKey) {
        for (ECKey key : wallet.getIssuedReceiveKeys()) {
            if (java.util.Arrays.equals(key.getPubKey(), publicKey)) return key;
        }
        for (ECKey key : wallet.getImportedKeys()) {
            if (java.util.Arrays.equals(key.getPubKey(), publicKey)) return key;
        }
        return wallet.findKeyFromPubKey(publicKey);
    }

    private static boolean isMatchingNestedScript(Wallet wallet, ECKey key, Script script) {
        try {
            return WalletAddressType.scriptForKey(
                    wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH).equals(script);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isRequestNestedScript(Context context, Wallet wallet, Script script) {
        return findRequestNestedKeyForScript(context, wallet, script) != null;
    }

    public static boolean isReadOnlySelection(Context context, Wallet wallet) {
        Script selected = findSelectedScript(context, wallet);
        return selected != null && !isRequestNestedScript(context, wallet, selected);
    }

    public static String addressForScript(Script script, NetworkParameters parameters) {
        if (script == null || parameters == null) return null;
        try {
            return script.getToAddress(parameters).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Derives possible Nested SegWit counterpart scripts for current HD/lookahead keys.
     * This set is used only to clean up inferred watch-script pollution from old builds;
     * it must never be used to decide which outputs belong to a Request.
     */
    private static java.util.Set<Script> deriveRequestNestedScripts(Wallet wallet) {
        java.util.LinkedHashSet<Script> expected = new java.util.LinkedHashSet<>();
        if (wallet == null) return expected;
        for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
            // Include both issued keys and the current lookahead window. Some bitcoinj
            // versions expose issued receive keys separately from getLeafKeys().
            for (DeterministicKey key : chain.getLeafKeys()) {
                try {
                    expected.add(WalletAddressType.scriptForKey(
                            wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH));
                } catch (Exception ignored) {
                    // One invalid key must not prevent recognition of other scripts.
                }
            }
        }
        for (ECKey key : wallet.getIssuedReceiveKeys()) {
            try {
                expected.add(WalletAddressType.scriptForKey(
                        wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH));
            } catch (Exception ignored) {
                // Keep scanning the remaining issued receive keys.
            }
        }
        return expected;
    }

    /**
     * Builds scripts that are owned by the main deterministic receive keychain.
     * These scripts must never be copied across restore as independent watch-only
     * entries: older versions persisted Request outputs in watchedScripts, even when
     * the private key was already owned by the main wallet.
     */
    public static java.util.Set<Script> getMainWalletReceiveScripts(Wallet wallet) {
        java.util.LinkedHashSet<Script> scripts = new java.util.LinkedHashSet<>();
        if (wallet == null) return scripts;
        try {
            for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
                for (DeterministicKey key : chain.getLeafKeys()) {
                    addReceiveScripts(scripts, wallet, key);
                }
            }
            for (ECKey key : wallet.getIssuedReceiveKeys()) {
                addReceiveScripts(scripts, wallet, key);
            }
        } catch (Exception ignored) {
            // Return only scripts successfully derived; callers retain unknown scripts
            // rather than deleting possible user-owned watch-only entries.
        }
        return scripts;
    }

    private static void addReceiveScripts(
            java.util.Set<Script> scripts, Wallet wallet, ECKey key) {
        if (scripts == null || wallet == null || key == null) return;
        for (String type : new String[]{
                WalletAddressType.P2PKH,
                WalletAddressType.P2WPKH,
                WalletAddressType.P2SH_P2WPKH,
                WalletAddressType.P2TR}) {
            try {
                scripts.add(WalletAddressType.scriptForKey(wallet.getParams(), key, type));
            } catch (Exception ignored) {
                // This wallet/library combination may not support every address type.
            }
        }
    }

    /**
     * Removes obsolete Request-created P2PKH/P2WPKH scripts from the watch-only set.
     * Earlier versions added every non-Legacy Request output to watchedScripts, causing
     * wallet-owned SegWit addresses to appear as separate watch-only balances. The keys
     * remain in the active HD keychain, so bitcoinj can recognize their ordinary P2PKH
     * and P2WPKH outputs directly; only P2SH-P2WPKH still needs explicit script watching.
     *
     * Safe to call on both Mainnet and Signet: scripts are derived using the active wallet's
     * parameters and deterministic keys, not network-specific prefixes or constants.
     */
    public static int removeRequestNativeWatchedScripts(Wallet wallet) {
        if (wallet == null) return 0;
        java.util.Set<Script> walletOwnedNativeScripts = new java.util.HashSet<>();
        try {
            for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
                for (DeterministicKey key : chain.getLeafKeys()) {
                    walletOwnedNativeScripts.add(WalletAddressType.scriptForKey(
                            wallet.getParams(), key, WalletAddressType.P2PKH));
                    walletOwnedNativeScripts.add(WalletAddressType.scriptForKey(
                            wallet.getParams(), key, WalletAddressType.P2WPKH));
                }
            }
            for (ECKey key : wallet.getIssuedReceiveKeys()) {
                walletOwnedNativeScripts.add(WalletAddressType.scriptForKey(
                        wallet.getParams(), key, WalletAddressType.P2PKH));
                walletOwnedNativeScripts.add(WalletAddressType.scriptForKey(
                        wallet.getParams(), key, WalletAddressType.P2WPKH));
            }
        } catch (Exception ignored) {
            // Do not modify watch-only state if the HD keychain cannot be inspected safely.
            return 0;
        }

        java.util.List<Script> obsolete = new java.util.ArrayList<>();
        for (Script script : wallet.getWatchedScripts()) {
            if (walletOwnedNativeScripts.contains(script)) obsolete.add(script);
        }
        if (obsolete.isEmpty()) return 0;
        wallet.removeWatchedScripts(obsolete);
        return obsolete.size();
    }

    /**
     * Reconcile only explicitly registered Request Nested-SegWit addresses.
     * Older builds added the nested counterpart of every HD/lookahead key to
     * watchedScripts. That made unrelated P2SH outputs appear to belong to the
     * main wallet. Remove those inferred scripts unless they are explicitly
     * registered Requests or belong to a separately imported address.
     */
    public static int ensureRequestNestedScripts(Context context, Wallet wallet) {
        if (wallet == null) return 0;
        int changes = removeRequestNativeWatchedScripts(wallet);
        if (context == null) {
            invalidateRequestScriptCache(wallet);
            return changes;
        }

        java.util.Set<Script> registered = deriveRegisteredRequestNestedScripts(context, wallet);
        registered.removeAll(getImportedScripts(context, wallet));
        java.util.Set<Script> allDerivedNested = deriveRequestNestedScripts(wallet);
        java.util.Set<Script> importedScripts = getImportedScripts(context, wallet);

        java.util.List<Script> obsolete = new java.util.ArrayList<>();
        for (Script watched : wallet.getWatchedScripts()) {
            if (allDerivedNested.contains(watched)
                    && !registered.contains(watched)
                    && !importedScripts.contains(watched)) {
                obsolete.add(watched);
            }
        }
        if (!obsolete.isEmpty()) {
            wallet.removeWatchedScripts(obsolete);
            changes += obsolete.size();
        }

        java.util.Set<Script> existing = new java.util.HashSet<>(wallet.getWatchedScripts());
        java.util.List<Script> missing = new java.util.ArrayList<>();
        for (Script script : registered) {
            if (!existing.contains(script)) missing.add(script);
        }
        if (!missing.isEmpty()) changes += wallet.addWatchedScripts(missing);
        invalidateRequestScriptCache(wallet);
        return changes;
    }

    /** Compatibility overload for call sites without an application Context. */
    public static int ensureRequestNestedScripts(Wallet wallet) {
        if (wallet == null) return 0;
        int changes = removeRequestNativeWatchedScripts(wallet);
        // Never guess Request ownership from an HD lookahead key. The Context
        // overload is required to restore the exact registered addresses.
        invalidateRequestScriptCache(wallet);
        return changes;
    }

    /**
     * When an explicitly registered Request Nested SegWit output is received,
     * mark its corresponding deterministic key as used. Unregistered P2SH
     * outputs must not advance the Request keychain or become main-wallet coins.
     */
    public static void observeRequestNestedTransaction(
            Context context, Wallet wallet, Transaction transaction) {
        if (context == null || wallet == null || transaction == null) return;
        java.util.Set<Script> requestScripts = getRequestNestedScripts(context, wallet);
        if (requestScripts.isEmpty()) return;
        java.util.List<DeterministicKeyChain> chains = wallet.getActiveKeyChains();
        boolean advanced = false;
        for (TransactionOutput output : transaction.getOutputs()) {
            Script outputScript = output.getScriptPubKey();
            if (!requestScripts.contains(outputScript)) continue;
            ECKey requestKey = findRequestNestedKeyForScript(context, wallet, outputScript);
            if (requestKey == null) continue;
            for (DeterministicKeyChain chain : chains) {
                boolean matched = false;
                for (DeterministicKey key : chain.getLeafKeys()) {
                    if (java.util.Arrays.equals(key.getPubKey(), requestKey.getPubKey())
                            && isMatchingNestedScript(wallet, key, outputScript)) {
                        chain.markKeyAsUsed(key);
                        advanced = true;
                        matched = true;
                        break;
                    }
                }
                if (matched) break;
            }
        }
        if (advanced) ensureRequestNestedScripts(context, wallet);
    }

    public static String getSelectedImportedAddress(Context context) {
        String value = prefs(context).getString(KEY_IMPORTED_ADDRESS, null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static void selectImportedAddress(Context context, String address) {
        if (address == null || address.trim().isEmpty()) {
            selectMain(context);
            return;
        }
        prefs(context).edit()
                .putString(KEY_IMPORTED_ADDRESS, address.trim())
                .remove(KEY_WATCH_ADDRESS)
                .apply();
    }

    public static Script findSelectedImportedScript(Context context, Wallet wallet) {
        if (wallet == null) return null;
        String selected = getSelectedImportedAddress(context);
        if (selected == null) return null;
        ECKey key = findImportedKey(wallet, selected);
        if (key == null) {
            selectMain(context);
            return null;
        }
        try {
            String type = ImportedWalletStore.getAddressType(context, selected);
            return WalletAddressType.scriptForKey(wallet.getParams(), key, type);
        } catch (Exception ignored) {
            selectMain(context);
            return null;
        }
    }

    /** Returns imported addresses registered by the management screen and backed by wallet keys. */
    public static java.util.List<String> getImportedAddresses(Context context, Wallet wallet) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (wallet == null) return result;
        for (String address : ImportedWalletStore.getAddresses(context)) {
            if (findImportedKey(wallet, address) != null) {
                result.add(address);
            }
        }
        return result;
    }

    /** Legacy fallback for callers that do not have a Context; derives every supported address. */
    public static java.util.List<String> getImportedAddresses(Wallet wallet) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (wallet == null) return new java.util.ArrayList<>(result);
        for (ECKey key : wallet.getImportedKeys()) {
            for (String type : new String[]{
                    WalletAddressType.P2PKH,
                    WalletAddressType.P2WPKH,
                    WalletAddressType.P2SH_P2WPKH,
                    WalletAddressType.P2TR}) {
                try {
                    result.add(WalletAddressType.addressForKey(wallet.getParams(), key, type).toString());
                } catch (Exception ignored) {
                }
            }
        }
        return new java.util.ArrayList<>(result);
    }

    /** Resolves an imported address to its exact output script by testing all supported key/address forms. */
    public static Script findImportedScriptForAddress(Wallet wallet, String address) {
        ECKey key = findImportedKey(wallet, address);
        if (key == null) return null;
        for (String type : new String[]{
                WalletAddressType.P2PKH,
                WalletAddressType.P2WPKH,
                WalletAddressType.P2SH_P2WPKH,
                WalletAddressType.P2TR}) {
            try {
                org.bitcoinj.base.Address derived =
                        WalletAddressType.addressForKey(wallet.getParams(), key, type);
                if (address.trim().equals(derived.toString())) {
                    return WalletAddressType.scriptForKey(wallet.getParams(), key, type);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Finds the private key for any imported address generated by the supported address types. */
    public static ECKey findImportedKey(Wallet wallet, String address) {
        if (wallet == null || address == null || address.trim().isEmpty()) return null;
        String cleanAddress = address.trim();
        for (ECKey key : wallet.getImportedKeys()) {
            for (String type : new String[]{
                    WalletAddressType.P2PKH,
                    WalletAddressType.P2WPKH,
                    WalletAddressType.P2SH_P2WPKH,
                    WalletAddressType.P2TR}) {
                try {
                    if (cleanAddress.equals(
                            WalletAddressType.addressForKey(wallet.getParams(), key, type).toString())) {
                        return key;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public static boolean selectedImportedUsesWatchedOutputs(Context context) {
        String address = getSelectedImportedAddress(context);
        return address != null && !WalletAddressType.isNativelySpendable(
                ImportedWalletStore.getAddressType(context, address));
    }

    public static boolean importedAddressUsesWatchedOutputs(Context context, String address) {
        return address != null && !WalletAddressType.isNativelySpendable(
                ImportedWalletStore.getAddressType(context, address));
    }

    public static Coin selectedImportedAvailableBalance(Wallet wallet, Script script) {
        if (wallet == null || script == null) return Coin.ZERO;
        Coin total = Coin.ZERO;
        for (TransactionOutput output : wallet.getUnspents()) {
            if (script.equals(output.getScriptPubKey()) && output.isAvailableForSpending()
                    && output.getParentTransactionDepthInBlocks() > 0) {
                total = total.add(output.getValue());
            }
        }
        return total;
    }

    public static Coin selectedImportedAvailableBalance(Context context, Wallet wallet, Script script) {
        if (wallet == null || script == null) return Coin.ZERO;
        Coin total = Coin.ZERO;
        Iterable<TransactionOutput> outputs = selectedImportedUsesWatchedOutputs(context)
                ? wallet.getWatchedOutputs(false) : wallet.getUnspents();
        for (TransactionOutput output : outputs) {
            if (script.equals(output.getScriptPubKey()) && output.isAvailableForSpending()
                    && output.getParentTransactionDepthInBlocks() > 0) {
                total = total.add(output.getValue());
            }
        }
        return total;
    }

    /** Returns true when another registered imported address still uses this key. */
    public static boolean isKeyUsedByOtherImportedAddresses(
            Context context, Wallet wallet, String removedAddress, ECKey key) {
        if (wallet == null || key == null) return false;
        for (String address : ImportedWalletStore.getAddresses(context)) {
            if (address.equals(removedAddress)) continue;
            ECKey other = findImportedKey(wallet, address);
            if (other != null && other.getPubKeyPoint().equals(key.getPubKeyPoint())) {
                return true;
            }
        }
        return false;
    }

    /** Returns the selected watch-only script, or null when the main wallet is selected. */
    public static Script findSelectedScript(Context context, Wallet wallet) {
        if (wallet == null) {
            return null;
        }

        String selected = getSelectedWatchAddress(context);
        if (selected == null) {
            return null;
        }

        NetworkParameters parameters = wallet.getParams();
        for (Script script : wallet.getWatchedScripts()) {
            try {
                String address = script.getToAddress(parameters).toString();
                if (selected.equals(address)) {
                    // Older versions exposed request-owned Nested SegWit scripts as
                    // separate selector entries. Migrate that selection back to main.
                    if (isRequestNestedScript(context, wallet, script)) {
                        selectMain(context);
                        return null;
                    }
                    return script;
                }
            } catch (Exception ignored) {
                // A watched script without an address representation cannot be selected here.
            }
        }

        // The selected address may have been deleted. Fall back to the main wallet.
        selectMain(context);
        return null;
    }

    /** Returns true when the output belongs to one of the explicitly watched scripts. */
    public static boolean isWatchedOutput(Wallet wallet, TransactionOutput output) {
        return wallet != null && output != null
                && isWatchedScript(wallet, output.getScriptPubKey());
    }

    /** Returns true when the script is explicitly watched by the wallet. */
    public static boolean isWatchedScript(Wallet wallet, Script script) {
        if (wallet == null || script == null) {
            return false;
        }
        return wallet.getWatchedScripts().contains(script);
    }

    /**
     * Returns only exact, explicitly registered Request Nested-SegWit scripts.
     * A matching address type or a derivable lookahead script alone is not enough
     * to classify an output as a Request belonging to the main wallet.
     */
    public static java.util.Set<Script> getRequestNestedScripts(Context context, Wallet wallet) {
        if (context == null || wallet == null) return java.util.Collections.emptySet();
        synchronized (WalletSelection.class) {
            java.util.Set<Script> watched = new java.util.HashSet<>(wallet.getWatchedScripts());
            RequestScriptCacheEntry cached = REQUEST_SCRIPT_CACHE.get(wallet);
            if (cached == null || !cached.watchedScripts.equals(watched)) {
                java.util.Set<Script> scripts = deriveRegisteredRequestNestedScripts(context, wallet);
                REQUEST_SCRIPT_CACHE.put(wallet, new RequestScriptCacheEntry(watched, scripts));
                cached = REQUEST_SCRIPT_CACHE.get(wallet);
            }
            java.util.LinkedHashSet<Script> result =
                    new java.util.LinkedHashSet<>(cached.requestScripts);
            result.removeAll(getImportedScripts(context, wallet));
            return result;
        }
    }

    /** Builds the exact Request scripts from persisted address-to-public-key mappings. */
    private static java.util.Set<Script> deriveRegisteredRequestNestedScripts(
            Context context, Wallet wallet) {
        java.util.LinkedHashSet<Script> scripts = new java.util.LinkedHashSet<>();
        if (context == null || wallet == null) return scripts;
        for (java.util.Map.Entry<String, ?> entry : prefs(context).getAll().entrySet()) {
            String preferenceKey = entry.getKey();
            if (preferenceKey == null || !preferenceKey.startsWith(REQUEST_NESTED_PUBKEY_PREFIX)
                    || !(entry.getValue() instanceof String)) continue;
            String address = preferenceKey.substring(REQUEST_NESTED_PUBKEY_PREFIX.length());
            try {
                byte[] publicKey = android.util.Base64.decode(
                        (String) entry.getValue(), android.util.Base64.NO_WRAP);
                ECKey key = findKeyByPublicKey(wallet, publicKey);
                if (key == null) continue;
                Script script = WalletAddressType.scriptForKey(
                        wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH);
                if (address.equals(addressForScript(script, wallet.getParams()))
                        && isMatchingNestedScript(wallet, key, script)) {
                    scripts.add(script);
                }
            } catch (Exception ignored) {
                // Ignore stale mappings from a different wallet/network.
            }
        }
        return scripts;
    }

    private static Coin mainUnspentBalance(Context context, Wallet wallet, boolean confirmedOnly) {
        if (wallet == null) return Coin.ZERO;
        java.util.Set<Script> requestScripts = context == null
                ? java.util.Collections.emptySet() : getRequestNestedScripts(context, wallet);
        java.util.Set<Script> importedScripts = context == null
                ? java.util.Collections.emptySet() : getImportedScripts(context, wallet);
        java.util.Map<String, TransactionOutput> unique = new java.util.LinkedHashMap<>();
        java.util.List<Iterable<TransactionOutput>> sources = new java.util.ArrayList<>();
        sources.add(wallet.getUnspents());
        if (!requestScripts.isEmpty()) sources.add(wallet.getWatchedOutputs(false));
        for (Iterable<TransactionOutput> source : sources) {
            for (TransactionOutput output : source) {
                if (output == null || !output.isAvailableForSpending()) continue;
                if (confirmedOnly && output.getParentTransactionDepthInBlocks() <= 0) continue;
                Script script = output.getScriptPubKey();
                boolean requestOutput = requestScripts.contains(script);
                // P2SH outputs belong to the main scope only when the exact script
                // was registered by Request Nested SegWit. Other P2SH alternatives
                // derived from HD/lookahead keys are not automatically Request coins.
                boolean mainOwnedOutput = !org.bitcoinj.script.ScriptPattern.isP2SH(script)
                        && output.isMine(wallet)
                        && !isWatchedOutput(wallet, output)
                        && !importedScripts.contains(script);
                if (!requestOutput && !mainOwnedOutput) continue;
                String key = output.getParentTransactionHash() + ":" + output.getIndex();
                unique.put(key, output);
            }
        }
        long total = 0L;
        for (TransactionOutput output : unique.values()) total += output.getValue().value;
        return Coin.valueOf(total);
    }

    /** Main balance is derived from distinct spendable outputs, including Request Nested P2SH once. */
    public static Coin mainEstimatedBalance(Context context, Wallet wallet) {
        return mainUnspentBalance(context, wallet, false);
    }

    public static Coin mainAvailableBalance(Context context, Wallet wallet) {
        return mainUnspentBalance(context, wallet, true);
    }

    /** Compatibility overload for callers without an application context. */
    public static Coin mainEstimatedBalance(Wallet wallet) {
        return wallet == null ? Coin.ZERO
                : wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE);
    }

    /** Compatibility overload for callers without an application context. */
    public static Coin mainAvailableBalance(Wallet wallet) {
        return wallet == null ? Coin.ZERO
                : wallet.getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE);
    }

    /**
     * Returns the scripts belonging to every registered imported wallet.
     *
     * Main-wallet operations must exclude these scripts entirely. An imported
     * wallet is a separate wallet scope even when its key also lives inside
     * the same bitcoinj Wallet instance.
     */
    public static java.util.Set<Script> getImportedScripts(Context context, Wallet wallet) {
        java.util.HashSet<Script> scripts = new java.util.HashSet<>();
        if (context == null || wallet == null) {
            return scripts;
        }
        for (String address : ImportedWalletStore.getAddresses(context)) {
            Script script = findImportedScriptForAddress(wallet, address);
            if (script != null) {
                scripts.add(script);
            }
        }
        return scripts;
    }

    /**
     * Coin selector restricted to the currently selected wallet scope.
     * The main wallet excludes watched outputs, unregistered P2SH outputs and
     * registered imported-wallet outputs. Request Nested SegWit inputs are handled
     * by their dedicated signer using only explicitly registered scripts.
     */
    public static CoinSelector mainCoinSelector(Context context, Wallet wallet) {
        if (wallet == null) {
            throw new IllegalArgumentException("wallet == null");
        }
        final CoinSelector delegate = wallet.getCoinSelector();
        final Script importedScript = findSelectedImportedScript(context, wallet);
        final java.util.Set<Script> importedScripts = importedScript == null
                ? getImportedScripts(context, wallet)
                : java.util.Collections.emptySet();
        return (target, candidates) -> {
            java.util.ArrayList<TransactionOutput> filtered = new java.util.ArrayList<>();
            for (TransactionOutput output : candidates) {
                if (importedScript != null) {
                    if (importedScript.equals(output.getScriptPubKey())) {
                        filtered.add(output);
                    }
                } else if (!isWatchedOutput(wallet, output)
                        && !org.bitcoinj.script.ScriptPattern.isP2SH(output.getScriptPubKey())
                        && !importedScripts.contains(output.getScriptPubKey())) {
                    filtered.add(output);
                }
            }
            return delegate.select(target, filtered);
        };
    }

}
