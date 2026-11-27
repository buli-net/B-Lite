package wallet.send;

import wallet.ui.LocalizedErrors;
import wallet.main.R;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
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
import org.bitcoinj.wallet.Wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import wallet.core.WalletAddressType;

public final class NestedSegwitSpend {
    private NestedSegwitSpend() {
    }

    public static Coin availableBalance(Wallet wallet, Script script) {
        if (wallet == null || script == null) return Coin.ZERO;
        long total = 0L;
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (output.isAvailableForSpending()
                    && output.getParentTransactionDepthInBlocks() > 0
                    && script.equals(output.getScriptPubKey())) {
                total += output.getValue().value;
            }
        }
        return Coin.valueOf(total);
    }

    public static Coin calculateMaxSendAmount(
            Wallet wallet,
            Script sourceScript,
            ECKey key,
            Address destination,
            int feeRateSatVb,
            AesKey aesKey) throws Exception {
        validateArguments(wallet, sourceScript, key, destination, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sourceScript);
        Coin total = sum(inputs);
        long destinationDust = TransactionPolicy.minimumNonDustValue(destination);
        long guess = total.value - (long) feeRateSatVb * (170L + 91L * inputs.size());
        if (guess < destinationDust) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            Transaction transaction = buildSignedTransaction(
                    wallet.getParams(), inputs, sourceScript, key, destination,
                    Coin.valueOf(guess), Coin.ZERO, false, aesKey);
            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long next = total.value - requiredFee;
            if (next < destinationDust) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            if (next == guess) {
                return Coin.valueOf(next);
            }
            guess = next;
        }
        return Coin.valueOf(guess);
    }

    public static Transaction buildTransaction(
            Wallet wallet, Script sourceScript, ECKey key, Address destination,
            Coin amount, int feeRateSatVb, AesKey aesKey) throws Exception {
        return buildTransaction(wallet, sourceScript, key,
                java.util.Collections.singletonList(new SendRecipient(destination, amount)),
                feeRateSatVb, aesKey);
    }

    /** Build a single-source Nested SegWit transaction with multiple recipient outputs. */
    public static Transaction buildTransaction(
            Wallet wallet, Script sourceScript, ECKey key, List<SendRecipient> recipients,
            int feeRateSatVb, AesKey aesKey) throws Exception {
        validateSourceAndRecipients(wallet, sourceScript, key, recipients, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sourceScript);
        Coin total = sum(inputs);
        long sendValue = totalRecipientValue(recipients);
        long changeDust = TransactionPolicy.minimumNonDustValue(sourceScript);
        if (sendValue <= 0L || sendValue >= total.value) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_amount_or_funds_invalid);
        }

        long initialFee = (long) feeRateSatVb * estimatedVsize(inputs.size(), recipients);
        long initialChange = total.value - sendValue - initialFee;
        boolean includeChange = initialChange >= changeDust;
        Coin change = includeChange ? Coin.valueOf(initialChange) : Coin.ZERO;
        for (int attempt = 0; attempt < 16; attempt++) {
            Transaction transaction = buildSignedTransaction(
                    wallet.getParams(), inputs, sourceScript, key, recipients,
                    change, includeChange, aesKey);
            if (transaction.messageSize() > Transaction.MAX_STANDARD_TX_SIZE) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long availableAfterFee = total.value - sendValue - requiredFee;
            if (availableAfterFee < 0L) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            if (includeChange) {
                if (availableAfterFee < changeDust) {
                    includeChange = false;
                    change = Coin.ZERO;
                    continue;
                }
                if (change.value == availableAfterFee) return transaction;
                change = Coin.valueOf(availableAfterFee);
                continue;
            }
            return transaction;
        }
        Transaction transaction = buildSignedTransaction(
                wallet.getParams(), inputs, sourceScript, key, recipients,
                Coin.ZERO, false, aesKey);
        if (total.value - sendValue < (long) feeRateSatVb * transaction.getVsize()) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
        }
        return transaction;
    }

    /** A script and key pair for one request-created Nested SegWit receive address. */
    public static final class FundingSource {
        public final Script script;
        public final ECKey key;

        public FundingSource(Script script, ECKey key) {
            if (script == null || key == null) {
                throw new LocalizedErrors.IllegalArgumentException(R.string.error_funding_source_missing);
            }
            this.script = script;
            this.key = key;
        }
    }

    /** Calculate MAX across all request-created Nested SegWit addresses in the main wallet. */
    public static Coin calculateMaxSendAmount(
            Wallet wallet, List<FundingSource> sources, Address destination,
            int feeRateSatVb, AesKey aesKey) throws Exception {
        validateSources(wallet, sources, destination, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sources);
        Coin total = sum(inputs);
        long destinationDust = TransactionPolicy.minimumNonDustValue(destination);
        long guess = total.value - (long) feeRateSatVb * (170L + 91L * inputs.size());
        if (guess < destinationDust) throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
        for (int attempt = 0; attempt < 8; attempt++) {
            Transaction transaction = buildSignedMultiTransaction(
                    wallet.getParams(), inputs, sources, destination,
                    Coin.valueOf(guess), Coin.ZERO, false, aesKey);
            long next = total.value - (long) feeRateSatVb * transaction.getVsize();
            if (next < destinationDust) throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            if (next == guess) return Coin.valueOf(next);
            guess = next;
        }
        return Coin.valueOf(guess);
    }

    /** Build one signed transaction spending confirmed outputs from multiple request addresses. */
    public static Transaction buildTransaction(
            Wallet wallet, List<FundingSource> sources, Address destination,
            Coin amount, int feeRateSatVb, AesKey aesKey) throws Exception {
        return buildTransaction(wallet, sources,
                java.util.Collections.singletonList(new SendRecipient(destination, amount)),
                feeRateSatVb, aesKey);
    }

    /** Build one signed transaction across request Nested SegWit sources with many outputs. */
    public static Transaction buildTransaction(
            Wallet wallet, List<FundingSource> sources, List<SendRecipient> recipients,
            int feeRateSatVb, AesKey aesKey) throws Exception {
        validateSourcesAndRecipients(wallet, sources, recipients, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sources);
        Coin total = sum(inputs);
        long sendValue = totalRecipientValue(recipients);
        long changeDust = TransactionPolicy.minimumNonDustValue(sources.get(0).script);
        if (sendValue <= 0L || sendValue >= total.value) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_amount_or_funds_invalid);
        }

        long initialFee = (long) feeRateSatVb * estimatedVsize(inputs.size(), recipients);
        long initialChange = total.value - sendValue - initialFee;
        boolean includeChange = initialChange >= changeDust;
        Coin change = includeChange ? Coin.valueOf(initialChange) : Coin.ZERO;
        for (int attempt = 0; attempt < 16; attempt++) {
            Transaction transaction = buildSignedMultiTransaction(
                    wallet.getParams(), inputs, sources, recipients,
                    change, includeChange, aesKey);
            if (transaction.messageSize() > Transaction.MAX_STANDARD_TX_SIZE) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long availableAfterFee = total.value - sendValue - requiredFee;
            if (availableAfterFee < 0L) {
                throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
            }
            if (includeChange) {
                if (availableAfterFee < changeDust) {
                    includeChange = false;
                    change = Coin.ZERO;
                    continue;
                }
                if (change.value == availableAfterFee) return transaction;
                change = Coin.valueOf(availableAfterFee);
                continue;
            }
            return transaction;
        }
        Transaction transaction = buildSignedMultiTransaction(
                wallet.getParams(), inputs, sources, recipients,
                Coin.ZERO, false, aesKey);
        if (total.value - sendValue < (long) feeRateSatVb * transaction.getVsize()) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_insufficient_funds_after_fees);
        }
        return transaction;
    }

    private static void validateSourceAndRecipients(Wallet wallet, Script sourceScript,
                                                    ECKey key, List<SendRecipient> recipients,
                                                    int feeRateSatVb) {
        if (wallet == null || sourceScript == null || key == null
                || recipients == null || recipients.isEmpty()) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_transaction_data_missing);
        }
        if (feeRateSatVb < 1 || feeRateSatVb > 10
                || !WalletAddressType.scriptForKey(wallet.getParams(), key,
                WalletAddressType.P2SH_P2WPKH).equals(sourceScript)) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_source_key_mismatch);
        }
        validateRecipientOutputs(recipients);
    }

    private static void validateSourcesAndRecipients(Wallet wallet, List<FundingSource> sources,
                                                     List<SendRecipient> recipients, int feeRateSatVb) {
        if (wallet == null || sources == null || sources.isEmpty()
                || recipients == null || recipients.isEmpty()) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_funding_data_missing);
        }
        if (feeRateSatVb < 1 || feeRateSatVb > 10) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_fee_rate_invalid);
        }
        for (FundingSource source : sources) {
            if (source == null || !WalletAddressType.scriptForKey(
                    wallet.getParams(), source.key, WalletAddressType.P2SH_P2WPKH).equals(source.script)) {
                throw new LocalizedErrors.IllegalArgumentException(R.string.error_funding_key_mismatch);
            }
        }
        validateRecipientOutputs(recipients);
    }

    private static void validateRecipientOutputs(List<SendRecipient> recipients) {
        for (SendRecipient recipient : recipients) {
            if (recipient == null || recipient.address() == null || recipient.amount() == null
                    || recipient.amount().value < TransactionPolicy.minimumNonDustValue(recipient.address())) {
                throw new LocalizedErrors.IllegalArgumentException(R.string.error_amount_or_funds_invalid);
            }
        }
    }

    private static long totalRecipientValue(List<SendRecipient> recipients) {
        long total = 0L;
        for (SendRecipient recipient : recipients) total = Math.addExact(total, recipient.amount().value);
        return total;
    }

    private static long estimatedVsize(int inputCount, List<SendRecipient> recipients) {
        long estimate = 10L;
        for (SendRecipient recipient : recipients) {
            estimate += TransactionPolicy.serializedOutputSize(recipient.address());
        }
        return estimate + 91L * inputCount;
    }

    private static void validateSources(
            Wallet wallet, List<FundingSource> sources, Address destination, int feeRateSatVb) {
        if (wallet == null || sources == null || sources.isEmpty() || destination == null) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_funding_data_missing);
        }
        if (feeRateSatVb < 1 || feeRateSatVb > 10) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_fee_rate_invalid);
        }
        for (FundingSource source : sources) {
            if (source == null || !WalletAddressType.scriptForKey(
                    wallet.getParams(), source.key, WalletAddressType.P2SH_P2WPKH).equals(source.script)) {
                throw new LocalizedErrors.IllegalArgumentException(R.string.error_funding_key_mismatch);
            }
        }
    }

    private static List<TransactionOutput> eligibleOutputs(
            Wallet wallet, List<FundingSource> sources) {
        java.util.Set<Script> scripts = new java.util.HashSet<>();
        for (FundingSource source : sources) scripts.add(source.script);
        Set<String> selected = CoinControl.getSelected();
        List<TransactionOutput> outputs = new ArrayList<>();
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (!output.isAvailableForSpending()
                    || output.getParentTransactionDepthInBlocks() <= 0
                    || !scripts.contains(output.getScriptPubKey())) continue;
            if (!selected.isEmpty() && !CoinControl.isSelected(output)) continue;
            outputs.add(output);
        }
        if (outputs.isEmpty()) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_no_confirmed_outputs);
        }
        return outputs;
    }

    private static Transaction buildSignedMultiTransaction(
            NetworkParameters parameters, List<TransactionOutput> outputs,
            List<FundingSource> sources, Address destination, Coin amount,
            Coin change, boolean includeChange, AesKey aesKey) throws Exception {
        return buildSignedMultiTransaction(parameters, outputs, sources,
                java.util.Collections.singletonList(new SendRecipient(destination, amount)),
                change, includeChange, aesKey);
    }

    private static Transaction buildSignedMultiTransaction(
            NetworkParameters parameters, List<TransactionOutput> outputs,
            List<FundingSource> sources, List<SendRecipient> recipients,
            Coin change, boolean includeChange, AesKey aesKey) throws Exception {
        java.util.Map<Script, ECKey> keysByScript = new java.util.HashMap<>();
        for (FundingSource source : sources) keysByScript.put(source.script, source.key);
        Script changeScript = outputs.get(0).getScriptPubKey();

        Transaction transaction = new Transaction(parameters);
        for (TransactionOutput output : outputs) {
            transaction.addInput(new TransactionInput(
                    transaction, new byte[0], output.getOutPointFor(), output.getValue())
                    .withSequence(TransactionInput.NO_SEQUENCE));
        }
        for (SendRecipient recipient : recipients) {
            transaction.addOutput(recipient.amount(), recipient.address());
        }
        if (includeChange) transaction.addOutput(change, changeScript);

        List<TransactionInput> signedInputs = new ArrayList<>();
        for (int index = 0; index < outputs.size(); index++) {
            Script sourceScript = outputs.get(index).getScriptPubKey();
            ECKey key = keysByScript.get(sourceScript);
            if (key == null) throw new LocalizedErrors.IllegalStateException(R.string.error_nested_segwit_key_missing);
            Script redeemScript = ScriptBuilder.createP2WPKHOutputScript(key);
            Script scriptCode = ScriptBuilder.createP2PKHOutputScript(key);
            Script scriptSig = new ScriptBuilder().data(redeemScript.getProgram()).build();
            TransactionSignature signature = transaction.calculateWitnessSignature(
                    index, key, aesKey, scriptCode, outputs.get(index).getValue(),
                    Transaction.SigHash.ALL, false);
            signedInputs.add(transaction.getInput(index)
                    .withScriptSig(scriptSig)
                    .withWitness(TransactionWitness.redeemP2WPKH(signature, key)));
        }
        transaction.clearInputs();
        for (TransactionInput signedInput : signedInputs) transaction.addInput(signedInput);
        for (int index = 0; index < signedInputs.size(); index++) {
            signedInputs.get(index).verify(outputs.get(index));
        }
        return transaction;
    }

    private static void validateArguments(
            Wallet wallet, Script sourceScript, ECKey key, Address destination, int feeRateSatVb) {
        if (wallet == null || sourceScript == null || key == null || destination == null) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_transaction_data_missing);
        }
        if (feeRateSatVb < 1 || feeRateSatVb > 10) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_fee_rate_invalid);
        }
        if (!WalletAddressType.scriptForKey(
                wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH).equals(sourceScript)) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_source_key_mismatch);
        }
    }

    private static List<TransactionOutput> eligibleOutputs(Wallet wallet, Script sourceScript) {
        if (wallet == null || sourceScript == null) {
            throw new LocalizedErrors.IllegalArgumentException(R.string.error_wallet_or_script_missing);
        }
        Set<String> selected = CoinControl.getSelected();
        List<TransactionOutput> outputs = new ArrayList<>();
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (!output.isAvailableForSpending()
                    || output.getParentTransactionDepthInBlocks() <= 0
                    || !sourceScript.equals(output.getScriptPubKey())) {
                continue;
            }
            if (!selected.isEmpty() && !CoinControl.isSelected(output)) {
                continue;
            }
            outputs.add(output);
        }
        if (outputs.isEmpty()) {
            throw new LocalizedErrors.IllegalStateException(R.string.error_no_confirmed_outputs);
        }
        return outputs;
    }

    private static Coin sum(List<TransactionOutput> outputs) {
        long total = 0L;
        for (TransactionOutput output : outputs) {
            total += output.getValue().value;
        }
        return Coin.valueOf(total);
    }

    private static Transaction buildSignedTransaction(
            NetworkParameters parameters, List<TransactionOutput> outputs,
            Script sourceScript, ECKey key, Address destination, Coin amount,
            Coin change, boolean includeChange, AesKey aesKey) throws Exception {
        return buildSignedTransaction(parameters, outputs, sourceScript, key,
                java.util.Collections.singletonList(new SendRecipient(destination, amount)),
                change, includeChange, aesKey);
    }

    private static Transaction buildSignedTransaction(
            NetworkParameters parameters, List<TransactionOutput> outputs,
            Script sourceScript, ECKey key, List<SendRecipient> recipients,
            Coin change, boolean includeChange, AesKey aesKey) throws Exception {
        Script redeemScript = ScriptBuilder.createP2WPKHOutputScript(key);
        Script scriptCode = ScriptBuilder.createP2PKHOutputScript(key);
        Script scriptSig = new ScriptBuilder().data(redeemScript.getProgram()).build();

        Transaction transaction = new Transaction(parameters);
        for (TransactionOutput output : outputs) {
            transaction.addInput(new TransactionInput(
                    transaction,
                    new byte[0],
                    output.getOutPointFor(),
                    output.getValue()).withSequence(TransactionInput.NO_SEQUENCE));
        }
        for (SendRecipient recipient : recipients) {
            transaction.addOutput(recipient.amount(), recipient.address());
        }
        if (includeChange) {
            transaction.addOutput(change, sourceScript);
        }

        List<TransactionInput> signedInputs = new ArrayList<>();
        for (int index = 0; index < outputs.size(); index++) {
            TransactionSignature signature = transaction.calculateWitnessSignature(
                    index, key, aesKey, scriptCode, outputs.get(index).getValue(),
                    Transaction.SigHash.ALL, false);
            TransactionInput signedInput = transaction.getInput(index)
                    .withScriptSig(scriptSig)
                    .withWitness(TransactionWitness.redeemP2WPKH(signature, key));
            signedInputs.add(signedInput);
        }

        transaction.clearInputs();
        for (TransactionInput signedInput : signedInputs) {
            transaction.addInput(signedInput);
        }

        for (int index = 0; index < signedInputs.size(); index++) {
            signedInputs.get(index).verify(outputs.get(index));
        }
        return transaction;
    }
}
