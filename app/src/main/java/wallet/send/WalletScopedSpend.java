package wallet.send;

import wallet.ui.LocalizedErrors;
import wallet.main.R;

import android.content.Context;
import android.util.Base64;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.TransactionWitness;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptPattern;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.base.Address;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import wallet.core.WalletAddressType;
import wallet.core.WalletSelection;

/**
 * Signs transactions across the wallet's eligible Legacy, Native SegWit, and explicitly
 * recognized Nested SegWit outputs. Ordinary Send operations honor Coin Control and the selected
 * wallet scope; the forwarding sweep path deliberately enumerates all signable outputs tracked by
 * the active wallet so watched Request outputs are not silently omitted.
 */
public final class WalletScopedSpend {
    private static final long DUST_LIMIT = 546L;

    private enum SpendType { LEGACY, NATIVE_SEGWIT, NESTED_SEGWIT }

    private static final class FundingInput {
        final TransactionOutput output;
        final Script script;
        final ECKey key;
        final SpendType type;

        FundingInput(TransactionOutput output, Script script, ECKey key, SpendType type) {
            this.output = output;
            this.script = script;
            this.key = key;
            this.type = type;
        }
    }

    private WalletScopedSpend() { }

    /**
     * Builds an exact, signed sweep from every signable output tracked by the active wallet,
     * including registered Request Nested-SegWit and imported-key outputs which bitcoinj can keep
     * outside its ordinary spend-candidate list. Watch-only outputs without signing material are
     * excluded. Coin Control is intentionally ignored so a whole-wallet sweep cannot inherit a
     * manual selection left over from the Send screen. A non-null incomingTxId scopes the sweep to
     * that receipt; null means all currently available signable outputs in this wallet.
     */
    public static Transaction buildForwardingSweepTransaction(
            Context context, Wallet wallet, Address destination, int feeRateSatVb,
            AesKey aesKey, String incomingTxId) throws Exception {
        validate(context, wallet, destination, feeRateSatVb);
        List<FundingInput> inputs = eligibleInputs(context, wallet, false, incomingTxId, true);
        if (inputs.isEmpty()) {
            throw new LocalizedErrors.IllegalStateException(
                    R.string.error_no_confirmed_spendable_outputs_selected);
        }

        long total = totalValue(inputs);
        long amount = total - (long) feeRateSatVb * estimatedVsize(inputs, destination);
        if (amount < DUST_LIMIT) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
        }

        // DER signature lengths can change the final vsize by a byte. Iterate against the signed
        // size and use a conservative final pass if signatures prevent an exact fixed point.
        for (int attempt = 0; attempt < 16; attempt++) {
            Transaction transaction = buildSignedTransaction(wallet.getParams(), inputs, destination,
                    Coin.valueOf(amount), Coin.ZERO, false, aesKey);
            if (transaction.messageSize() > Transaction.MAX_STANDARD_TX_SIZE) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long nextAmount = total - requiredFee;
            if (nextAmount < DUST_LIMIT) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            long actualFee = total - transaction.getOutput(0).getValue().value;
            if (actualFee >= requiredFee
                    && nextAmount == amount
                    && transaction.getInputs().size() == inputs.size()
                    && transaction.getOutputs().size() == 1) return transaction;
            amount = nextAmount;
        }

        // Conservative last pass if signature size changes prevent an exact fixed point.
        for (int attempt = 0; attempt < 4; attempt++) {
            if (amount < DUST_LIMIT) break;
            Transaction transaction = buildSignedTransaction(wallet.getParams(), inputs, destination,
                    Coin.valueOf(amount), Coin.ZERO, false, aesKey);
            if (transaction.messageSize() > Transaction.MAX_STANDARD_TX_SIZE) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long actualFee = total - transaction.getOutput(0).getValue().value;
            if (actualFee >= requiredFee
                    && transaction.getInputs().size() == inputs.size()
                    && transaction.getOutputs().size() == 1) return transaction;
            amount -= Math.max(1L, requiredFee - actualFee);
        }
        throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
    }

    /**
     * Builds a fixed-amount automatic forward using only signable outputs from the triggering
     * receipt. Unlike Wallet.completeTx(), this can include registered Request Nested-SegWit
     * outputs held in bitcoinj's watched-output collection. It deliberately ignores Coin Control
     * only for the automatic-forward path; ordinary Send/MAX methods keep their existing scope.
     * Any spendable remainder is returned to the script of the first selected input, unless the
     * remainder after the exact required fee would be dust, in which case it becomes fee.
     */
    public static Transaction buildForwardingFixedAmountTransaction(
            Context context, Wallet wallet, Address destination, Coin amount, int feeRateSatVb,
            AesKey aesKey, String incomingTxId) throws Exception {
        validate(context, wallet, destination, feeRateSatVb);
        if (amount == null || amount.value < DUST_LIMIT || incomingTxId == null || incomingTxId.isEmpty()) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_amount_or_funds_invalid);
        }

        List<FundingInput> inputs = eligibleInputs(
                context, wallet, false, incomingTxId, true);
        if (inputs.isEmpty()) {
            throw new LocalizedErrors.IllegalStateException(
                    R.string.error_no_confirmed_spendable_outputs_selected);
        }

        long total = totalValue(inputs);
        if (amount.value >= total) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
        }
        Script changeScript = inputs.get(0).script;
        long estimatedFee = (long) feeRateSatVb
                * (estimatedVsize(inputs, destination) + outputVsize(changeScript));
        long initialChange = total - amount.value - estimatedFee;
        boolean includeChange = initialChange >= DUST_LIMIT;
        // Set only after an actual signed change-output transaction shows a sub-dust remainder.
        // That prevents oscillation around the dust boundary while allowing the exact signed-size
        // calculation to recover change when the initial estimate was conservative.
        boolean noChangeDueToDust = false;
        Coin change = includeChange ? Coin.valueOf(initialChange) : Coin.ZERO;

        // Recompute the signed virtual size because DER signature lengths vary by input.
        for (int attempt = 0; attempt < 24; attempt++) {
            Transaction transaction = buildSignedTransaction(wallet.getParams(), inputs, destination,
                    amount, change, includeChange, aesKey);
            if (transaction.messageSize() > Transaction.MAX_STANDARD_TX_SIZE) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }

            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long availableAfterRequiredFee = total - amount.value - requiredFee;
            if (availableAfterRequiredFee < 0L) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }

            if (includeChange) {
                if (availableAfterRequiredFee < DUST_LIMIT) {
                    // Returning dust as change is non-standard; fold only this sub-dust remainder
                    // plus the change-output cost into the fee, then verify the no-change TX.
                    noChangeDueToDust = true;
                    includeChange = false;
                    change = Coin.ZERO;
                    continue;
                }
                if (change.value == availableAfterRequiredFee) {
                    long actualFee = total - amount.value - change.value;
                    if (actualFee >= requiredFee) return transaction;
                }
                change = Coin.valueOf(availableAfterRequiredFee);
                continue;
            }

            long actualFee = total - amount.value;
            if (actualFee < requiredFee) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            // If we did not already decide change would be dust, return any spendable remainder
            // to the wallet instead of silently donating it as fee.
            if (!noChangeDueToDust && availableAfterRequiredFee >= DUST_LIMIT) {
                includeChange = true;
                change = Coin.valueOf(availableAfterRequiredFee);
                continue;
            }
            return transaction;
        }

        throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
    }

    /**
     * Finds private/encrypted key material for a pubkey hash across all keychains, even when a
     * Request address uses a script type different from the keychain's default output type.
     * bitcoinj's typed lookup intentionally filters deterministic chains by ScriptType; Request
     * can derive Legacy and Native-SegWit scripts from the same known key, so forwarding needs an
     * exact pubkey-hash match across those keys as a safe fallback.
     */
    public static ECKey findSignableKeyForPubKeyHash(Wallet wallet, byte[] pubKeyHash, ScriptType scriptType) {
        if (wallet == null || pubKeyHash == null) return null;
        try {
            ECKey typed = wallet.findKeyFromPubKeyHash(pubKeyHash, scriptType);
            if (canSign(typed)) return typed;
        } catch (RuntimeException ignored) { }

        return signableKeyIndex(wallet).get(keyForHash(pubKeyHash));
    }

    private static Map<String, ECKey> signableKeyIndex(Wallet wallet) {
        Map<String, ECKey> keysByHash = new LinkedHashMap<>();
        if (wallet == null) return keysByHash;
        List<ECKey> keys = new ArrayList<>();
        try {
            keys.addAll(wallet.getImportedKeys());
            keys.addAll(wallet.getIssuedReceiveKeys());
            for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
                keys.addAll(chain.getLeafKeys());
            }
        } catch (RuntimeException ignored) {
            // Keep any keys collected before an incomplete chain view was encountered.
        }
        for (ECKey key : keys) {
            if (canSign(key)) keysByHash.putIfAbsent(keyForHash(key.getPubKeyHash()), key);
        }
        return keysByHash;
    }

    private static String keyForHash(byte[] pubKeyHash) {
        return Base64.encodeToString(pubKeyHash, Base64.NO_WRAP);
    }

    public static boolean requiresUnifiedSpendPath(Context context, Wallet wallet) {
        if (context == null || wallet == null) return false;
        try {
            List<FundingInput> inputs = eligibleInputs(context, wallet);
            if (inputs.size() > 3) return true;
            for (FundingInput input : inputs) {
                if (input.type == SpendType.NESTED_SEGWIT) return true;
            }
        } catch (Exception ignored) {
            // A false result only selects the established bitcoinj path when there
            // is no safely identifiable Nested SegWit input in the current scope.
        }
        return false;
    }

    public static Coin calculateMaxAmount(Context context, Wallet wallet, Address destination,
                                          int feeRateSatVb, AesKey aesKey) throws Exception {
        validate(context, wallet, destination, feeRateSatVb);
        List<FundingInput> inputs = eligibleInputs(context, wallet);
        if (inputs.isEmpty()) throw new LocalizedErrors.IllegalStateException(R.string.error_no_confirmed_spendable_outputs_selected);

        long total = totalValue(inputs);
        long estimatedVsize = 10L + outputVsize(destination);
        for (FundingInput input : inputs) {
            switch (input.type) {
                case LEGACY: estimatedVsize += 148L; break;
                case NATIVE_SEGWIT: estimatedVsize += 68L; break;
                case NESTED_SEGWIT: estimatedVsize += 91L; break;
            }
        }
        long guess = total - (long) feeRateSatVb * estimatedVsize;
        if (guess < DUST_LIMIT) throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);

        for (int attempt = 0; attempt < 12; attempt++) {
            Transaction tx = buildSignedTransaction(wallet.getParams(), inputs, destination,
                    Coin.valueOf(guess), Coin.ZERO, false, aesKey);
            long next = total - (long) feeRateSatVb * tx.getVsize();
            if (next < DUST_LIMIT) throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            if (next == guess) return Coin.valueOf(next);
            guess = next;
        }
        Transaction finalTx = buildSignedTransaction(wallet.getParams(), inputs, destination,
                Coin.valueOf(guess), Coin.ZERO, false, aesKey);
        long exact = total - (long) feeRateSatVb * finalTx.getVsize();
        if (exact < DUST_LIMIT) throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
        if (exact != guess) {
            finalTx = buildSignedTransaction(wallet.getParams(), inputs, destination,
                    Coin.valueOf(exact), Coin.ZERO, false, aesKey);
            long corrected = total - (long) feeRateSatVb * finalTx.getVsize();
            if (corrected < DUST_LIMIT) throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            return Coin.valueOf(corrected);
        }
        return Coin.valueOf(exact);
    }

    public static Transaction buildTransaction(Context context, Wallet wallet, Address destination,
                                              Coin amount, int feeRateSatVb, AesKey aesKey) throws Exception {
        validate(context, wallet, destination, feeRateSatVb);
        List<FundingInput> inputs = eligibleInputs(context, wallet);
        if (inputs.isEmpty()) throw new LocalizedErrors.IllegalStateException(R.string.error_no_confirmed_spendable_outputs_selected);
        long total = totalValue(inputs);
        if (amount == null || amount.value < DUST_LIMIT || amount.value >= total) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_amount_or_funds_invalid);
        }

        long initialFee = (long) feeRateSatVb * estimatedVsize(inputs, destination);
        long initialChange = total - amount.value - initialFee;
        boolean includeChange = initialChange >= DUST_LIMIT;
        Coin change = includeChange ? Coin.valueOf(initialChange) : Coin.ZERO;
        for (int attempt = 0; attempt < 12; attempt++) {
            Transaction tx = buildSignedTransaction(wallet.getParams(), inputs, destination,
                    amount, change, includeChange, aesKey);
            long requiredFee = (long) feeRateSatVb * tx.getVsize();
            long actualFee = total - amount.value - (includeChange ? change.value : 0L);
            if (actualFee < requiredFee) {
                long adjustedChange = total - amount.value - requiredFee;
                if (adjustedChange >= DUST_LIMIT) {
                    change = Coin.valueOf(adjustedChange);
                    includeChange = true;
                    continue;
                }
                includeChange = false;
                change = Coin.ZERO;
                continue;
            }
            if (includeChange) {
                long adjustedChange = total - amount.value - requiredFee;
                if (adjustedChange >= DUST_LIMIT && adjustedChange != change.value) {
                    change = Coin.valueOf(adjustedChange);
                    continue;
                }
            }
            return tx;
        }

        Transaction tx = buildSignedTransaction(wallet.getParams(), inputs, destination,
                amount, Coin.ZERO, false, aesKey);
        long requiredFee = (long) feeRateSatVb * tx.getVsize();
        if (total - amount.value < requiredFee) throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
        return tx;
    }

    private static List<FundingInput> eligibleInputs(Context context, Wallet wallet) {
        return eligibleInputs(context, wallet, true, null, false);
    }

    private static List<FundingInput> eligibleInputs(Context context, Wallet wallet,
                                                     boolean respectCoinControl, String incomingTxId,
                                                     boolean allowOtherSignableKeyOutputs) {
        Set<String> selected = respectCoinControl ? CoinControl.getSelected()
                : java.util.Collections.emptySet();
        Set<Script> importedScripts = WalletSelection.getImportedScripts(context, wallet);
        Map<Script, ECKey> nestedKeys = nestedKeys(context, wallet,
                allowOtherSignableKeyOutputs ? java.util.Collections.emptySet() : importedScripts);
        Map<String, ECKey> signableKeyIndex = allowOtherSignableKeyOutputs
                ? signableKeyIndex(wallet) : java.util.Collections.emptyMap();
        Map<String, FundingInput> unique = new LinkedHashMap<>();

        for (TransactionOutput output : wallet.getUnspents()) {
            addOutput(wallet, output, selected, importedScripts, nestedKeys, signableKeyIndex,
                    unique, false, incomingTxId, allowOtherSignableKeyOutputs);
        }
        // Request Nested SegWit outputs live in bitcoinj's watched-output collection
        // because P2SH-P2WPKH is not part of its regular spendable keychain selector.
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            addOutput(wallet, output, selected, importedScripts, nestedKeys, signableKeyIndex,
                    unique, true, incomingTxId, allowOtherSignableKeyOutputs);
        }
        return new ArrayList<>(unique.values());
    }

    private static void addOutput(Wallet wallet, TransactionOutput output, Set<String> selected,
                                  Set<Script> importedScripts, Map<Script, ECKey> nestedKeys,
                                  Map<String, ECKey> signableKeyIndex, Map<String, FundingInput> unique,
                                  boolean fromWatched, String incomingTxId,
                                  boolean allowOtherSignableKeyOutputs) {
        if (output == null || !output.isAvailableForSpending()
                || output.getParentTransactionDepthInBlocks() <= 0) return;
        if (!selected.isEmpty() && !selected.contains(CoinControl.key(output))) return;
        if (incomingTxId != null && (output.getParentTransactionHash() == null
                || !incomingTxId.equals(output.getParentTransactionHash().toString()))) return;

        Script script = output.getScriptPubKey();
        if (!allowOtherSignableKeyOutputs && importedScripts.contains(script)) return;
        ECKey key = null;
        SpendType type = null;

        if (ScriptPattern.isP2PKH(script)) {
            if (!allowOtherSignableKeyOutputs && (fromWatched
                    || WalletSelection.isWatchedOutput(wallet, output) || !output.isMine(wallet))) return;
            byte[] pubKeyHash = ScriptPattern.extractHashFromP2PKH(script);
            key = allowOtherSignableKeyOutputs
                    ? signableKeyIndex.get(keyForHash(pubKeyHash))
                    : wallet.findKeyFromPubKeyHash(pubKeyHash, ScriptType.P2PKH);
            type = SpendType.LEGACY;
        } else if (ScriptPattern.isP2WPKH(script)) {
            if (!allowOtherSignableKeyOutputs && (fromWatched
                    || WalletSelection.isWatchedOutput(wallet, output) || !output.isMine(wallet))) return;
            byte[] pubKeyHash = ScriptPattern.extractHashFromP2WH(script);
            key = allowOtherSignableKeyOutputs
                    ? signableKeyIndex.get(keyForHash(pubKeyHash))
                    : wallet.findKeyFromPubKeyHash(pubKeyHash, ScriptType.P2WPKH);
            type = SpendType.NATIVE_SEGWIT;
        } else if (ScriptPattern.isP2SH(script)) {
            if (!fromWatched) return;
            key = nestedKeys.get(script);
            if (key == null) return;
            type = SpendType.NESTED_SEGWIT;
        } else {
            return;
        }
        if (!canSign(key)) return;
        unique.putIfAbsent(CoinControl.key(output), new FundingInput(output, script, key, type));
    }

    private static Map<Script, ECKey> nestedKeys(Context context, Wallet wallet, Set<Script> importedScripts) {
        Map<Script, ECKey> keys = new LinkedHashMap<>();
        for (Script script : WalletSelection.getRequestNestedScripts(context, wallet)) {
            if (importedScripts.contains(script)) continue;
            ECKey key = WalletSelection.findRequestNestedKeyForScript(context, wallet, script);
            if (canSign(key)) keys.put(script, key);
        }
        List<ECKey> walletKeys = new ArrayList<>();
        walletKeys.addAll(wallet.getIssuedReceiveKeys());
        walletKeys.addAll(wallet.getImportedKeys());
        for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
            walletKeys.addAll(chain.getLeafKeys());
        }
        for (ECKey key : walletKeys) {
            if (!canSign(key)) continue;
            try {
                Script script = WalletAddressType.scriptForKey(
                        wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH);
                if (!importedScripts.contains(script)) keys.putIfAbsent(script, key);
            } catch (Exception ignored) { }
        }
        return keys;
    }

    private static Transaction buildSignedTransaction(NetworkParameters parameters,
                                                      List<FundingInput> inputs, Address destination,
                                                      Coin amount, Coin change, boolean includeChange,
                                                      AesKey aesKey) throws Exception {
        Transaction tx = new Transaction(parameters);
        for (FundingInput input : inputs) {
            tx.addInput(new TransactionInput(tx, new byte[0], input.output.getOutPointFor(), input.output.getValue())
                    .withSequence(TransactionInput.NO_SEQUENCE));
        }
        tx.addOutput(amount, destination);
        if (includeChange) tx.addOutput(change, inputs.get(0).script);

        List<TransactionInput> signedInputs = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            FundingInput funding = inputs.get(index);
            TransactionInput unsigned = tx.getInput(index);
            if (funding.type == SpendType.LEGACY) {
                TransactionSignature signature = tx.calculateSignature(index, funding.key, aesKey,
                        funding.script, Transaction.SigHash.ALL, false);
                signedInputs.add(unsigned.withScriptSig(ScriptBuilder.createInputScript(signature, funding.key))
                        .withoutWitness());
            } else if (funding.type == SpendType.NATIVE_SEGWIT) {
                Script scriptCode = ScriptBuilder.createP2PKHOutputScript(funding.key);
                TransactionSignature signature = tx.calculateWitnessSignature(index, funding.key, aesKey,
                        scriptCode, funding.output.getValue(), Transaction.SigHash.ALL, false);
                signedInputs.add(unsigned.withScriptSig(ScriptBuilder.createEmpty())
                        .withWitness(TransactionWitness.redeemP2WPKH(signature, funding.key)));
            } else {
                Script redeemScript = ScriptBuilder.createP2WPKHOutputScript(funding.key);
                Script scriptCode = ScriptBuilder.createP2PKHOutputScript(funding.key);
                TransactionSignature signature = tx.calculateWitnessSignature(index, funding.key, aesKey,
                        scriptCode, funding.output.getValue(), Transaction.SigHash.ALL, false);
                Script scriptSig = new ScriptBuilder().data(redeemScript.getProgram()).build();
                signedInputs.add(unsigned.withScriptSig(scriptSig)
                        .withWitness(TransactionWitness.redeemP2WPKH(signature, funding.key)));
            }
        }
        tx.clearInputs();
        for (TransactionInput input : signedInputs) tx.addInput(input);
        for (int index = 0; index < signedInputs.size(); index++) {
            signedInputs.get(index).verify(inputs.get(index).output);
        }
        return tx;
    }

    private static boolean canSign(ECKey key) {
        // Encrypted bitcoinj keys report hasPrivKey() == false until decrypted,
        // but remain signable through calculateSignature(..., aesKey, ...).
        return key != null && (key.hasPrivKey() || key.isEncrypted());
    }

    private static long totalValue(List<FundingInput> inputs) {
        long total = 0L;
        for (FundingInput input : inputs) total += input.output.getValue().value;
        return total;
    }

    private static long estimatedVsize(List<FundingInput> inputs, Address destination) {
        long estimate = 10L + outputVsize(destination);
        for (FundingInput input : inputs) {
            switch (input.type) {
                case LEGACY: estimate += 148L; break;
                case NATIVE_SEGWIT: estimate += 68L; break;
                case NESTED_SEGWIT: estimate += 91L; break;
            }
        }
        return estimate;
    }

    private static long outputVsize(Address destination) {
        int scriptLength = ScriptBuilder.createOutputScript(destination).program().length;
        return 8L + (scriptLength < 253 ? 1L : 3L) + scriptLength;
    }

    private static long outputVsize(Script script) {
        int scriptLength = script.program().length;
        return 8L + (scriptLength < 253 ? 1L : 3L) + scriptLength;
    }

    private static void validate(Context context, Wallet wallet, Address destination, int feeRateSatVb) {
        if (context == null || wallet == null || destination == null) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_funding_data_missing);
        }
        if (feeRateSatVb < 1 || feeRateSatVb > 10) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_fee_rate_invalid);
        }
    }
}
