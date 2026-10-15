package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
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
    }

    public static ECKey findRequestNestedKeyForScript(
            Context context, Wallet wallet, Script script) {
        if (context == null || wallet == null || script == null) return null;
        String address = addressForScript(script, wallet.getParams());
        if (address == null) return null;

        String encodedPublicKey = prefs(context).getString(
                REQUEST_NESTED_PUBKEY_PREFIX + address, null);
        if (encodedPublicKey != null) {
            try {
                byte[] publicKey = android.util.Base64.decode(
                        encodedPublicKey, android.util.Base64.NO_WRAP);
                ECKey key = findKeyByPublicKey(wallet, publicKey);
                if (key != null && isMatchingRequestScript(wallet, key, script)) {
                    return key;
                }
            } catch (Exception ignored) {
            }
        }

        // Also recognize scripts created before the public key mapping was stored.
        // The key for a request output may be inside the deterministic lookahead window
        // after a mnemonic restore, even though bitcoinj has not issued it yet.
        // Search every active chain's leaf keys so restored P2SH outputs remain spendable.
        for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
            for (DeterministicKey key : chain.getLeafKeys()) {
                if (isMatchingRequestScript(wallet, key, script)) {
                    registerRequestNestedAddress(context, address, key.getPubKey());
                    return key;
                }
            }
        }
        for (ECKey key : wallet.getIssuedReceiveKeys()) {
            if (isMatchingRequestScript(wallet, key, script)) {
                registerRequestNestedAddress(context, address, key.getPubKey());
                return key;
            }
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

    /**
     * Returns true when a script is one of the three Request output formats owned by
     * this HD key. bitcoinj natively spends only the configured key-chain output type;
     * the other formats are explicitly watched and use the custom signer.
     */
    private static boolean isMatchingRequestScript(Wallet wallet, ECKey key, Script script) {
        try {
            return WalletAddressType.scriptForKey(wallet.getParams(), key, WalletAddressType.P2PKH).equals(script)
                    || WalletAddressType.scriptForKey(wallet.getParams(), key, WalletAddressType.P2WPKH).equals(script)
                    || isMatchingNestedScript(wallet, key, script);
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

    /** Stable, network-specific scope key for Coin Control and transaction selection. */
    public static String getSelectionScope(Context context, Wallet wallet) {
        String network = NetworkConfig.storageName(NetworkConfig.get(context));
        Script importedScript = findSelectedImportedScript(context, wallet);
        if (importedScript != null) {
            String address = getSelectedImportedAddress(context);
            if (address != null) return network + ":imported:" + address;
        }
        Script watchScript = findSelectedScript(context, wallet);
        if (watchScript != null) {
            String address = addressForScript(watchScript, wallet.getParams());
            if (address != null) return network + ":watch:" + address;
        }
        return network + ":main";
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
     * Removes watched entries which duplicate the native script format of their own
     * deterministic key chain. Do not blindly remove P2PKH/P2WPKH: for a wallet whose
     * native chain is P2WPKH, P2PKH is an alternate Request format that must remain
     * watched; for a legacy chain, the opposite is true.
     */
    public static int removeRequestNativeWatchedScripts(Wallet wallet) {
        if (wallet == null) return 0;
        java.util.Set<Script> nativeScripts = new java.util.HashSet<>();
        try {
            for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
                String nativeType = addressTypeFor(chain.getOutputScriptType());
                for (DeterministicKey key : chain.getLeafKeys()) {
                    nativeScripts.add(WalletAddressType.scriptForKey(
                            wallet.getParams(), key, nativeType));
                }
            }
            for (ECKey key : wallet.getIssuedReceiveKeys()) {
                String nativeType = nativeAddressTypeForKey(wallet, key);
                nativeScripts.add(WalletAddressType.scriptForKey(
                        wallet.getParams(), key, nativeType));
            }
        } catch (Exception ignored) {
            // Do not alter persisted watch state when key ownership cannot be inspected.
            return 0;
        }

        java.util.List<Script> obsolete = new java.util.ArrayList<>();
        for (Script script : wallet.getWatchedScripts()) {
            if (nativeScripts.contains(script)) obsolete.add(script);
        }
        if (obsolete.isEmpty()) return 0;
        wallet.removeWatchedScripts(obsolete);
        return obsolete.size();
    }

    private static String addressTypeFor(ScriptType scriptType) {
        return scriptType == ScriptType.P2WPKH
                ? WalletAddressType.P2WPKH : WalletAddressType.P2PKH;
    }

    private static String nativeAddressTypeForKey(Wallet wallet, ECKey key) {
        if (wallet != null && key != null) {
            byte[] target = key.getPubKey();
            for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
                for (DeterministicKey candidate : chain.getLeafKeys()) {
                    if (java.util.Arrays.equals(target, candidate.getPubKey())) {
                        return addressTypeFor(chain.getOutputScriptType());
                    }
                }
            }
        }
        // The app creates P2WPKH wallets by default. All issued receive keys should be
        // present in an active chain, but this fallback avoids accidentally watching a
        // P2WPKH script as a second wallet output if a library version omits one.
        return WalletAddressType.P2WPKH;
    }

    /**
     * Ensure that every supported Request format which is not natively owned by the
     * key's deterministic chain is watched by the main wallet. The same wallet-driven
     * path is used for Mainnet and Signet; scripts are derived from wallet parameters.
     */
    public static int ensureRequestScripts(Context context, Wallet wallet) {
        if (wallet == null) return 0;
        int changes = removeRequestNativeWatchedScripts(wallet);
        java.util.Set<Script> expected = deriveAlternativeRequestScripts(wallet);
        java.util.Set<Script> existing = new java.util.HashSet<>(wallet.getWatchedScripts());
        java.util.List<Script> missing = new java.util.ArrayList<>();
        for (Script script : expected) {
            if (!existing.contains(script)) missing.add(script);
        }
        return missing.isEmpty() ? changes : changes + wallet.addWatchedScripts(missing);
    }

    private static java.util.Set<Script> deriveAlternativeRequestScripts(Wallet wallet) {
        java.util.LinkedHashSet<Script> expected = new java.util.LinkedHashSet<>();
        if (wallet == null) return expected;
        for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
            String nativeType = addressTypeFor(chain.getOutputScriptType());
            for (DeterministicKey key : chain.getLeafKeys()) {
                addAlternativeScripts(expected, wallet, key, nativeType);
            }
        }
        for (ECKey key : wallet.getIssuedReceiveKeys()) {
            addAlternativeScripts(expected, wallet, key, nativeAddressTypeForKey(wallet, key));
        }
        return expected;
    }

    private static void addAlternativeScripts(
            java.util.Set<Script> scripts, Wallet wallet, ECKey key, String nativeType) {
        if (scripts == null || wallet == null || key == null) return;
        for (String type : new String[]{
                WalletAddressType.P2PKH,
                WalletAddressType.P2WPKH,
                WalletAddressType.P2SH_P2WPKH}) {
            if (type.equals(nativeType)) continue;
            try {
                scripts.add(WalletAddressType.scriptForKey(wallet.getParams(), key, type));
            } catch (Exception ignored) {
                // A failure deriving one format must not suppress the other formats.
            }
        }
    }

    /**
     * When an output pays one of the restored alternate Request scripts, mark its
     * deterministic key as used. This advances bitcoinj's lookahead and lets the next
     * batch of supported Request formats be registered, preserving normal gap-limit discovery.
     */
    public static void observeRequestNestedTransaction(Wallet wallet, Transaction transaction) {
        observeRequestNestedTransaction(null, wallet, transaction);
    }

    public static void observeRequestNestedTransaction(
            Context context, Wallet wallet, Transaction transaction) {
        if (wallet == null || transaction == null) return;
        java.util.List<DeterministicKeyChain> chains = wallet.getActiveKeyChains();
        java.util.Set<Script> watched = new java.util.HashSet<>(wallet.getWatchedScripts());
        boolean advanced = false;
        for (TransactionOutput output : transaction.getOutputs()) {
            Script outputScript = output.getScriptPubKey();
            if (!watched.contains(outputScript)) continue;
            for (DeterministicKeyChain chain : chains) {
                boolean matched = false;
                for (DeterministicKey key : chain.getLeafKeys()) {
                    if (isMatchingRequestScript(wallet, key, outputScript)) {
                        chain.markKeyAsUsed(key);
                        advanced = true;
                        matched = true;
                        break;
                    }
                }
                if (matched) break;
            }
        }
        if (advanced) ensureRequestScripts(context, wallet);
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

    /** Returns alternate Request scripts backed by keys in the main wallet (Legacy, Native or Nested SegWit). */
    public static java.util.Set<Script> getRequestNestedScripts(Context context, Wallet wallet) {
        java.util.LinkedHashSet<Script> scripts = new java.util.LinkedHashSet<>();
        if (wallet == null) return scripts;
        java.util.Set<Script> expected = new java.util.HashSet<>(deriveAlternativeRequestScripts(wallet));
        if (context != null) {
            for (Script script : wallet.getWatchedScripts()) {
                if (findRequestNestedKeyForScript(context, wallet, script) != null) expected.add(script);
            }
        }
        for (Script script : wallet.getWatchedScripts()) {
            // The exact script-set comparison is the fast path. The key lookup also
            // recognizes issued receive keys retained separately by bitcoinj and keys
            // whose public-key mapping was persisted when the Request was created.
            if (expected.contains(script)
                    || (context != null && findRequestNestedKeyForScript(context, wallet, script) != null)) {
                scripts.add(script);
            }
        }
        return scripts;
    }

    private static Coin requestNestedBalance(Context context, Wallet wallet, boolean confirmedOnly) {
        if (context == null || wallet == null) return Coin.ZERO;
        java.util.Set<Script> scripts = getRequestNestedScripts(context, wallet);
        if (scripts.isEmpty()) return Coin.ZERO;
        long total = 0L;
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (!output.isAvailableForSpending()
                    || !scripts.contains(output.getScriptPubKey())) continue;
            if (confirmedOnly && output.getParentTransactionDepthInBlocks() <= 0) continue;
            total += output.getValue().value;
        }
        return Coin.valueOf(total);
    }

    /**
     * Main-wallet balances include alternate Request outputs even though bitcoinj
     * tracks their scripts as watched outputs for synchronization.
     * Other watched outputs remain outside the main-wallet balance.
     */
    public static Coin mainEstimatedBalance(Context context, Wallet wallet) {
        if (wallet == null) return Coin.ZERO;
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE)
                .add(requestNestedBalance(context, wallet, false));
    }

    public static Coin mainAvailableBalance(Context context, Wallet wallet) {
        if (wallet == null) return Coin.ZERO;
        return wallet.getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE)
                .add(requestNestedBalance(context, wallet, true));
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
     *
     * The main wallet excludes both explicitly watched outputs and every
     * registered imported-wallet output. When an imported wallet is selected,
     * only that wallet's exact script is eligible.
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
                        && !importedScripts.contains(output.getScriptPubKey())) {
                    filtered.add(output);
                }
            }
            return delegate.select(target, filtered);
        };
    }

}
