package wallet.send;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.TransactionSignature;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.Wallet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import wallet.main.WalletAddressType;
import wallet.tools.CoinControl;

/**
 * Spends Request-created P2PKH outputs whose key belongs to a different HD
 * output-script keychain. They are watched by bitcoinj for discovery, so they
 * need the same explicit signing path as other watched Request outputs.
 */
public final class LegacyRequestSpend {
    private static final long DUST_LIMIT = 546L;

    private LegacyRequestSpend() { }

    public static final class FundingSource {
        public final Script script;
        public final ECKey key;

        public FundingSource(Script script, ECKey key) {
            if (script == null || key == null) throw new IllegalArgumentException("Missing funding source");
            this.script = script;
            this.key = key;
        }
    }

    public static Coin availableBalance(Wallet wallet, Script script) {
        if (wallet == null || script == null) return Coin.ZERO;
        long total = 0L;
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (output.isAvailableForSpending() && output.getParentTransactionDepthInBlocks() > 0
                    && script.equals(output.getScriptPubKey())) total += output.getValue().value;
        }
        return Coin.valueOf(total);
    }

    public static Coin calculateMaxSendAmount(Wallet wallet, Script script, ECKey key,
                                               Address destination, int feeRateSatVb,
                                               AesKey aesKey) throws Exception {
        return calculateMaxSendAmount(wallet,
                java.util.Collections.singletonList(new FundingSource(script, key)),
                destination, feeRateSatVb, aesKey);
    }

    public static Coin calculateMaxSendAmount(Wallet wallet, List<FundingSource> sources,
                                               Address destination, int feeRateSatVb,
                                               AesKey aesKey) throws Exception {
        validate(wallet, sources, destination, feeRateSatVb);
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sources);
        long total = sum(inputs);
        long guess = total - (long) feeRateSatVb * (10L + 148L * inputs.size() + 34L);
        if (guess < DUST_LIMIT) throw new IllegalStateException("Insufficient funds after fees");
        for (int attempt = 0; attempt < 8; attempt++) {
            Transaction tx = buildSigned(wallet.getParams(), inputs, sources, destination,
                    Coin.valueOf(guess), Coin.ZERO, false, aesKey);
            long next = total - (long) feeRateSatVb * tx.getVsize();
            if (next < DUST_LIMIT) throw new IllegalStateException("Insufficient funds after fees");
            if (next == guess) return Coin.valueOf(next);
            guess = next;
        }
        return Coin.valueOf(guess);
    }

    public static Transaction buildTransaction(Wallet wallet, Script script, ECKey key,
                                               Address destination, Coin amount,
                                               int feeRateSatVb, AesKey aesKey) throws Exception {
        return buildTransaction(wallet,
                java.util.Collections.singletonList(new FundingSource(script, key)),
                destination, amount, feeRateSatVb, aesKey);
    }

    public static Transaction buildTransaction(Wallet wallet, List<FundingSource> sources,
                                               Address destination, Coin amount,
                                               int feeRateSatVb, AesKey aesKey) throws Exception {
        validate(wallet, sources, destination, feeRateSatVb);
        if (amount == null || amount.value < DUST_LIMIT) throw new IllegalStateException("Invalid amount");
        List<TransactionOutput> inputs = eligibleOutputs(wallet, sources);
        long total = sum(inputs);
        if (amount.value > total) throw new IllegalStateException("Insufficient funds");
        long initialFee = (long) feeRateSatVb * (10L + 148L * inputs.size() + 34L * 2L);
        long changeValue = total - amount.value - initialFee;
        boolean includeChange = changeValue >= DUST_LIMIT;
        Coin change = includeChange ? Coin.valueOf(changeValue) : Coin.ZERO;

        for (int attempt = 0; attempt < 8; attempt++) {
            Transaction tx = buildSigned(wallet.getParams(), inputs, sources, destination,
                    amount, change, includeChange, aesKey);
            long requiredFee = (long) feeRateSatVb * tx.getVsize();
            long actualFee = total - amount.value - (includeChange ? change.value : 0L);
            if (actualFee < requiredFee) {
                long adjustedChange = total - amount.value - requiredFee;
                if (adjustedChange >= DUST_LIMIT) {
                    change = Coin.valueOf(adjustedChange);
                    includeChange = true;
                } else {
                    change = Coin.ZERO;
                    includeChange = false;
                }
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

        Transaction tx = buildSigned(wallet.getParams(), inputs, sources, destination,
                amount, Coin.ZERO, false, aesKey);
        long requiredFee = (long) feeRateSatVb * tx.getVsize();
        if (total - amount.value < requiredFee) throw new IllegalStateException("Insufficient funds after fees");
        return tx;
    }

    private static void validate(Wallet wallet, List<FundingSource> sources,
                                 Address destination, int feeRateSatVb) {
        if (wallet == null || sources == null || sources.isEmpty() || destination == null)
            throw new IllegalArgumentException("Missing funding data");
        if (feeRateSatVb < 1 || feeRateSatVb > 10) throw new IllegalArgumentException("Invalid fee rate");
        for (FundingSource source : sources) {
            if (source == null || source.script == null || source.key == null
                    || !WalletAddressType.scriptForKey(wallet.getParams(), source.key,
                    WalletAddressType.P2PKH).equals(source.script)) {
                throw new IllegalArgumentException("Funding key does not match the Legacy Request address");
            }
        }
    }

    private static List<TransactionOutput> eligibleOutputs(Wallet wallet, List<FundingSource> sources) {
        Set<Script> scripts = new HashSet<>();
        for (FundingSource source : sources) scripts.add(source.script);
        Set<String> selected = CoinControl.getSelected();
        List<TransactionOutput> outputs = new ArrayList<>();
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (!output.isAvailableForSpending() || output.getParentTransactionDepthInBlocks() <= 0
                    || !scripts.contains(output.getScriptPubKey())) continue;
            if (!selected.isEmpty() && !CoinControl.isSelected(output)) continue;
            outputs.add(output);
        }
        if (outputs.isEmpty()) throw new IllegalStateException("No confirmed Legacy Request outputs are available to spend");
        return outputs;
    }

    private static Transaction buildSigned(NetworkParameters parameters, List<TransactionOutput> inputs,
                                           List<FundingSource> sources, Address destination, Coin amount,
                                           Coin change, boolean includeChange, AesKey aesKey) throws Exception {
        Map<Script, ECKey> keys = new HashMap<>();
        for (FundingSource source : sources) keys.put(source.script, source.key);
        Script changeScript = sources.get(0).script;
        Transaction tx = new Transaction(parameters);
        for (TransactionOutput output : inputs) {
            tx.addInput(new TransactionInput(tx, new byte[0], output.getOutPointFor(), output.getValue())
                    .withSequence(TransactionInput.NO_SEQUENCE));
        }
        tx.addOutput(amount, destination);
        if (includeChange && change != null && change.isPositive()) tx.addOutput(change, changeScript);

        List<TransactionInput> signed = new ArrayList<>();
        for (int index = 0; index < inputs.size(); index++) {
            TransactionOutput prevOutput = inputs.get(index);
            Script sourceScript = prevOutput.getScriptPubKey();
            ECKey key = keys.get(sourceScript);
            if (key == null) throw new IllegalStateException("Missing key for a Legacy Request input");
            TransactionSignature signature = tx.calculateSignature(index, key, aesKey,
                    sourceScript, Transaction.SigHash.ALL, false);
            Script scriptSig = new ScriptBuilder().data(signature.encodeToBitcoin()).data(key.getPubKey()).build();
            signed.add(tx.getInput(index).withScriptSig(scriptSig));
        }
        tx.clearInputs();
        for (TransactionInput input : signed) tx.addInput(input);
        for (int index = 0; index < signed.size(); index++) signed.get(index).verify(inputs.get(index));
        return tx;
    }

    private static long sum(List<TransactionOutput> outputs) {
        long total = 0L;
        for (TransactionOutput output : outputs) total += output.getValue().value;
        return total;
    }
}
