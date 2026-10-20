package wallet.send;

import android.content.Context;

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
import org.bitcoinj.base.Address;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import wallet.main.WalletAddressType;
import wallet.main.WalletSelection;
import wallet.tools.CoinControl;

/**
 * Signs a single transaction from the main wallet's eligible Legacy, Native SegWit,
 * and explicitly recognized Nested SegWit outputs. Coin Control selections are
 * treated as an exact outpoint set, regardless of how those outputs were received.
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
        if (inputs.isEmpty()) throw new wallet.main.LocalizedErrors.IllegalStateException(wallet.main.R.string.error_no_confirmed_spendable_outputs_selected);

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
        if (guess < DUST_LIMIT) throw new wallet.main.LocalizedErrors.IllegalStateException(wallet.main.R.string.error_insufficient_funds_after_fees);

        for (int attempt = 0; attempt < 12; attempt++) {
            Transaction tx = buildSignedTransaction(wallet.getParams(), inputs, destination,
                    Coin.valueOf(guess), Coin.ZERO, false, aesKey);
            long next = total - (long) feeRateSatVb * tx.getVsize();
            if (next < DUST_LIMIT) throw new wallet.main.LocalizedErrors.IllegalStateException(wallet.main.R.string.error_insufficient_funds_after_fees);
            if (next == guess) return Coin.valueOf(next);
            guess = next;
        }
        Transaction finalTx = buildSignedTransaction(wallet.getParams(), inputs, destination,
                Coin.valueOf(guess), Coin.ZERO, false, aesKey);
        long exact = total - (long) feeRateSatVb * finalTx.getVsize();
        if (exact < DUST_LIMIT) throw new wallet.main.LocalizedErrors.IllegalStateException(wallet.main.R.string.error_insufficient_funds_after_fees);
        if (exact != guess) {
            finalTx = buildSignedTransaction(wallet.getParams(), inputs, destination,
                    Coin.valueOf(exact), Coin.ZERO, false, aesKey);
            long corrected = total - (long) feeRateSatVb * finalTx.getVsize();
            if (corrected < DUST_LIMIT) throw new wallet.main.LocalizedErrors.IllegalStateException(wallet.main.R.string.error_insufficient_funds_after_fees);
            return Coin.valueOf(corrected);
        }
        return Coin.valueOf(exact);
    }

    public static Transaction buildTransaction(Context context, Wallet wallet, Address destination,
                                              Coin amount, int feeRateSatVb, AesKey aesKey) throws Exception {
        validate(context, wallet, destination, feeRateSatVb);
        List<FundingInput> inputs = eligibleInputs(context, wallet);
        if (inputs.isEmpty()) throw new wallet.main.LocalizedErrors.IllegalStateException(wallet.main.R.string.error_no_confirmed_spendable_outputs_selected);
        long total = totalValue(inputs);
        if (amount == null || amount.value < DUST_LIMIT || amount.value >= total) {
            throw new wallet.main.LocalizedErrors.IllegalStateException(wallet.main.R.string.error_amount_or_funds_invalid);
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
        if (total - amount.value < requiredFee) throw new wallet.main.LocalizedErrors.IllegalStateException(wallet.main.R.string.error_insufficient_funds_after_fees);
        return tx;
    }

    private static List<FundingInput> eligibleInputs(Context context, Wallet wallet) {
        Set<String> selected = CoinControl.getSelected();
        Set<Script> importedScripts = WalletSelection.getImportedScripts(context, wallet);
        Map<Script, ECKey> nestedKeys = nestedKeys(context, wallet, importedScripts);
        Map<String, FundingInput> unique = new LinkedHashMap<>();

        for (TransactionOutput output : wallet.getUnspents()) {
            addOutput(wallet, output, selected, importedScripts, nestedKeys, unique, false);
        }
        // Request Nested SegWit outputs live in bitcoinj's watched-output collection
        // because P2SH-P2WPKH is not part of its regular spendable keychain selector.
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            addOutput(wallet, output, selected, importedScripts, nestedKeys, unique, true);
        }
        return new ArrayList<>(unique.values());
    }

    private static void addOutput(Wallet wallet, TransactionOutput output, Set<String> selected,
                                  Set<Script> importedScripts, Map<Script, ECKey> nestedKeys,
                                  Map<String, FundingInput> unique, boolean fromWatched) {
        if (output == null || !output.isAvailableForSpending()
                || output.getParentTransactionDepthInBlocks() <= 0) return;
        if (!selected.isEmpty() && !selected.contains(CoinControl.key(output))) return;

        Script script = output.getScriptPubKey();
        if (importedScripts.contains(script)) return;
        ECKey key = null;
        SpendType type = null;

        if (ScriptPattern.isP2PKH(script)) {
            if (fromWatched || WalletSelection.isWatchedOutput(wallet, output) || !output.isMine(wallet)) return;
            key = wallet.findKeyFromPubKeyHash(ScriptPattern.extractHashFromP2PKH(script), ScriptType.P2PKH);
            type = SpendType.LEGACY;
        } else if (ScriptPattern.isP2WPKH(script)) {
            if (fromWatched || WalletSelection.isWatchedOutput(wallet, output) || !output.isMine(wallet)) return;
            key = wallet.findKeyFromPubKeyHash(ScriptPattern.extractHashFromP2WH(script), ScriptType.P2WPKH);
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

    private static void validate(Context context, Wallet wallet, Address destination, int feeRateSatVb) {
        if (context == null || wallet == null || destination == null) {
            throw new wallet.main.LocalizedErrors.IllegalArgumentException(wallet.main.R.string.error_funding_data_missing);
        }
        if (feeRateSatVb < 1 || feeRateSatVb > 10) {
            throw new wallet.main.LocalizedErrors.IllegalArgumentException(wallet.main.R.string.error_fee_rate_invalid);
        }
    }
}
