package wallet.send;

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

import wallet.main.WalletAddressType;
import wallet.tools.CoinControl;

public final class NestedSegwitSpend {
    private static final long DUST_LIMIT = 546L;

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
            String coinControlScope,
            AesKey aesKey) throws Exception {
        validateArguments(wallet, sourceScript, key, destination, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sourceScript, coinControlScope);
        Coin total = sum(inputs);
        long guess = total.value - (long) feeRateSatVb * (170L + 91L * inputs.size());
        if (guess < DUST_LIMIT) {
            throw new IllegalStateException("Insufficient funds after fees");
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            Transaction transaction = buildSignedTransaction(
                    wallet.getParams(), inputs, sourceScript, key, destination,
                    Coin.valueOf(guess), Coin.ZERO, false, aesKey);
            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long next = total.value - requiredFee;
            if (next < DUST_LIMIT) {
                throw new IllegalStateException("Insufficient funds after fees");
            }
            if (next == guess) {
                return Coin.valueOf(next);
            }
            guess = next;
        }
        return Coin.valueOf(guess);
    }

    public static Transaction buildTransaction(
            Wallet wallet,
            Script sourceScript,
            ECKey key,
            Address destination,
            Coin amount,
            int feeRateSatVb,
            String coinControlScope,
            AesKey aesKey) throws Exception {
        validateArguments(wallet, sourceScript, key, destination, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sourceScript, coinControlScope);
        Coin total = sum(inputs);
        if (amount == null || amount.value < DUST_LIMIT || amount.isGreaterThan(total)) {
            throw new IllegalStateException("Invalid amount or insufficient funds");
        }

        long initialFee = (long) feeRateSatVb * (170L + 91L * inputs.size());
        long initialChange = total.value - amount.value - initialFee;
        boolean includeChange = initialChange >= DUST_LIMIT;
        Coin change = includeChange ? Coin.valueOf(initialChange) : Coin.ZERO;

        for (int attempt = 0; attempt < 8; attempt++) {
            Transaction transaction = buildSignedTransaction(
                    wallet.getParams(), inputs, sourceScript, key, destination,
                    amount, change, includeChange, aesKey);
            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long actualFee = total.value - amount.value - (includeChange ? change.value : 0L);

            if (actualFee < requiredFee) {
                long adjustedChange = total.value - amount.value - requiredFee;
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
                long adjustedChange = total.value - amount.value - requiredFee;
                if (adjustedChange >= DUST_LIMIT && adjustedChange != change.value) {
                    change = Coin.valueOf(adjustedChange);
                    continue;
                }
            }

            return transaction;
        }

        Transaction transaction = buildSignedTransaction(
                wallet.getParams(), inputs, sourceScript, key, destination,
                amount, Coin.ZERO, false, aesKey);
        long requiredFee = (long) feeRateSatVb * transaction.getVsize();
        if (total.value - amount.value < requiredFee) {
            throw new IllegalStateException("Insufficient funds after fees");
        }
        return transaction;
    }

    /** A script and key pair for one main-wallet or alternate Request output type. */
    public static final class FundingSource {
        public final Script script;
        public final ECKey key;

        public FundingSource(Script script, ECKey key) {
            if (script == null || key == null) {
                throw new IllegalArgumentException("Missing funding source");
            }
            this.script = script;
            this.key = key;
        }
    }

    /** Calculate MAX across native main-wallet outputs and alternate Request outputs. */
    public static Coin calculateMaxSendAmount(
            Wallet wallet, List<FundingSource> sources, Address destination,
            int feeRateSatVb, String coinControlScope, AesKey aesKey) throws Exception {
        validateSources(wallet, sources, destination, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sources, coinControlScope);
        Coin total = sum(inputs);
        long guess = total.value - (long) feeRateSatVb * (170L + 91L * inputs.size());
        if (guess < DUST_LIMIT) throw new IllegalStateException("Insufficient funds after fees");
        for (int attempt = 0; attempt < 8; attempt++) {
            Transaction transaction = buildSignedMultiTransaction(
                    wallet.getParams(), inputs, sources, destination,
                    Coin.valueOf(guess), Coin.ZERO, false, aesKey);
            long next = total.value - (long) feeRateSatVb * transaction.getVsize();
            if (next < DUST_LIMIT) throw new IllegalStateException("Insufficient funds after fees");
            if (next == guess) return Coin.valueOf(next);
            guess = next;
        }
        return Coin.valueOf(guess);
    }

    /** Build one signed transaction spending confirmed main-wallet and Request outputs. */
    public static Transaction buildTransaction(
            Wallet wallet, List<FundingSource> sources, Address destination,
            Coin amount, int feeRateSatVb, String coinControlScope, AesKey aesKey) throws Exception {
        validateSources(wallet, sources, destination, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sources, coinControlScope);
        Coin total = sum(inputs);
        if (amount == null || amount.value < DUST_LIMIT || amount.isGreaterThan(total)) {
            throw new IllegalStateException("Invalid amount or insufficient funds");
        }

        long initialFee = (long) feeRateSatVb * (170L + 91L * inputs.size());
        long initialChange = total.value - amount.value - initialFee;
        boolean includeChange = initialChange >= DUST_LIMIT;
        Coin change = includeChange ? Coin.valueOf(initialChange) : Coin.ZERO;
        for (int attempt = 0; attempt < 8; attempt++) {
            Transaction transaction = buildSignedMultiTransaction(
                    wallet.getParams(), inputs, sources, destination,
                    amount, change, includeChange, aesKey);
            long requiredFee = (long) feeRateSatVb * transaction.getVsize();
            long actualFee = total.value - amount.value - (includeChange ? change.value : 0L);
            if (actualFee < requiredFee) {
                long adjustedChange = total.value - amount.value - requiredFee;
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
                long adjustedChange = total.value - amount.value - requiredFee;
                if (adjustedChange >= DUST_LIMIT && adjustedChange != change.value) {
                    change = Coin.valueOf(adjustedChange);
                    continue;
                }
            }
            return transaction;
        }

        Transaction transaction = buildSignedMultiTransaction(
                wallet.getParams(), inputs, sources, destination,
                amount, Coin.ZERO, false, aesKey);
        long requiredFee = (long) feeRateSatVb * transaction.getVsize();
        if (total.value - amount.value < requiredFee) {
            throw new IllegalStateException("Insufficient funds after fees");
        }
        return transaction;
    }

    private static void validateSources(
            Wallet wallet, List<FundingSource> sources, Address destination, int feeRateSatVb) {
        if (wallet == null || sources == null || sources.isEmpty() || destination == null) {
            throw new IllegalArgumentException("Missing funding data");
        }
        if (feeRateSatVb < 1 || feeRateSatVb > 10) {
            throw new IllegalArgumentException("Invalid fee rate");
        }
        for (FundingSource source : sources) {
            if (source == null || !matchesSupportedRequestScript(wallet, source.key, source.script)) {
                throw new IllegalArgumentException("Funding key does not match its source address");
            }
        }
    }

    private static List<TransactionOutput> eligibleOutputs(
            Wallet wallet, List<FundingSource> sources, String coinControlScope) {
        java.util.Set<Script> scripts = new java.util.HashSet<>();
        for (FundingSource source : sources) scripts.add(source.script);
        Set<String> selected = CoinControl.getSelected(coinControlScope);
        List<TransactionOutput> candidates = new ArrayList<>();
        // Main-wallet outputs (QR/native receive path) and alternate Request outputs
        // (tracked via watchedScripts) belong to the same spending scope. Combine both
        // collections and de-duplicate by outpoint before selecting the inputs.
        candidates.addAll(wallet.getUnspents());
        candidates.addAll(wallet.getWatchedOutputs(false));
        List<TransactionOutput> outputs = new ArrayList<>();
        Set<String> seenOutpoints = new java.util.HashSet<>();
        for (TransactionOutput output : candidates) {
            String outpoint = CoinControl.key(output);
            if (!seenOutpoints.add(outpoint)
                    || !output.isAvailableForSpending()
                    || output.getParentTransactionDepthInBlocks() <= 0
                    || !scripts.contains(output.getScriptPubKey())) continue;
            if (!selected.isEmpty() && !CoinControl.isSelected(output, coinControlScope)) continue;
            outputs.add(output);
        }
        if (outputs.isEmpty()) {
            throw new IllegalStateException("No confirmed outputs are available to spend");
        }
        return outputs;
    }

    private static Transaction buildSignedMultiTransaction(
            NetworkParameters parameters, List<TransactionOutput> outputs,
            List<FundingSource> sources, Address destination, Coin amount,
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
        transaction.addOutput(amount, destination);
        if (includeChange) transaction.addOutput(change, changeScript);

        List<TransactionInput> signedInputs = new ArrayList<>();
        for (int index = 0; index < outputs.size(); index++) {
            Script sourceScript = outputs.get(index).getScriptPubKey();
            ECKey key = keysByScript.get(sourceScript);
            if (key == null) throw new IllegalStateException("Missing key for a Request input");
            signedInputs.add(signRequestInput(transaction, index, outputs.get(index), sourceScript, key, parameters, aesKey));
        }
        transaction.clearInputs();
        for (TransactionInput signedInput : signedInputs) transaction.addInput(signedInput);
        for (int index = 0; index < signedInputs.size(); index++) {
            signedInputs.get(index).verify(outputs.get(index));
        }
        return transaction;
    }

    private static boolean matchesSupportedRequestScript(Wallet wallet, ECKey key, Script script) {
        try {
            return WalletAddressType.scriptForKey(wallet.getParams(), key, WalletAddressType.P2PKH).equals(script)
                    || WalletAddressType.scriptForKey(wallet.getParams(), key, WalletAddressType.P2WPKH).equals(script)
                    || WalletAddressType.scriptForKey(wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH).equals(script);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static TransactionInput signRequestInput(
            Transaction transaction, int index, TransactionOutput output,
            Script sourceScript, ECKey key, NetworkParameters parameters, AesKey aesKey) throws Exception {
        Script legacy = WalletAddressType.scriptForKey(
                parameters, key, WalletAddressType.P2PKH);
        if (legacy.equals(sourceScript)) {
            TransactionSignature signature = transaction.calculateSignature(
                    index, key, aesKey, sourceScript, Transaction.SigHash.ALL, false);
            Script scriptSig = new ScriptBuilder()
                    .data(signature.encodeToBitcoin()).data(key.getPubKey()).build();
            return transaction.getInput(index).withScriptSig(scriptSig);
        }

        Script nativeWitnessScript = WalletAddressType.scriptForKey(
                parameters, key, WalletAddressType.P2WPKH);
        Script redeemScript = ScriptBuilder.createP2WPKHOutputScript(key);
        Script scriptCode = ScriptBuilder.createP2PKHOutputScript(key);
        TransactionSignature signature = transaction.calculateWitnessSignature(
                index, key, aesKey, scriptCode, output.getValue(),
                Transaction.SigHash.ALL, false);
        if (nativeWitnessScript.equals(sourceScript)) {
            // Native SegWit has the same P2WPKH witness but no redeem-script in scriptSig.
            return transaction.getInput(index)
                    .withWitness(TransactionWitness.redeemP2WPKH(signature, key));
        }
        Script scriptSig = new ScriptBuilder().data(redeemScript.getProgram()).build();
        return transaction.getInput(index).withScriptSig(scriptSig)
                .withWitness(TransactionWitness.redeemP2WPKH(signature, key));
    }

    private static void validateArguments(
            Wallet wallet, Script sourceScript, ECKey key, Address destination, int feeRateSatVb) {
        if (wallet == null || sourceScript == null || key == null || destination == null) {
            throw new IllegalArgumentException("Missing transaction data");
        }
        if (feeRateSatVb < 1 || feeRateSatVb > 10) {
            throw new IllegalArgumentException("Invalid fee rate");
        }
        if (!matchesSupportedRequestScript(wallet, key, sourceScript)) {
            throw new IllegalArgumentException("Key does not match the source address");
        }
    }

    private static List<TransactionOutput> eligibleOutputs(
            Wallet wallet, Script sourceScript, String coinControlScope) {
        if (wallet == null || sourceScript == null) {
            throw new IllegalArgumentException("Wallet or source script is missing");
        }
        Set<String> selected = CoinControl.getSelected(coinControlScope);
        List<TransactionOutput> outputs = new ArrayList<>();
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (!output.isAvailableForSpending()
                    || output.getParentTransactionDepthInBlocks() <= 0
                    || !sourceScript.equals(output.getScriptPubKey())) {
                continue;
            }
            if (!selected.isEmpty() && !CoinControl.isSelected(output, coinControlScope)) {
                continue;
            }
            outputs.add(output);
        }
        if (outputs.isEmpty()) {
            throw new IllegalStateException("No confirmed outputs are available to spend");
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
            NetworkParameters parameters,
            List<TransactionOutput> outputs,
            Script sourceScript,
            ECKey key,
            Address destination,
            Coin amount,
            Coin change,
            boolean includeChange,
            AesKey aesKey) throws Exception {
        Transaction transaction = new Transaction(parameters);
        for (TransactionOutput output : outputs) {
            transaction.addInput(new TransactionInput(
                    transaction,
                    new byte[0],
                    output.getOutPointFor(),
                    output.getValue()).withSequence(TransactionInput.NO_SEQUENCE));
        }
        transaction.addOutput(amount, destination);
        if (includeChange) {
            transaction.addOutput(change, sourceScript);
        }

        List<TransactionInput> signedInputs = new ArrayList<>();
        for (int index = 0; index < outputs.size(); index++) {
            signedInputs.add(signRequestInput(
                    transaction, index, outputs.get(index), sourceScript, key, parameters, aesKey));
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
