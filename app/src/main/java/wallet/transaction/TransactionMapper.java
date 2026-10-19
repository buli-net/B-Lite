package wallet.transaction;

import android.content.Context;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.wallet.Wallet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.time.Instant;

import wallet.main.R;
import wallet.main.MoneyFormatter;
import wallet.model.TransactionItem;
import wallet.main.WalletSelection;

/** Converts bitcoinj transactions into UI data. */
public final class TransactionMapper {

    private TransactionMapper() {
    }

    /**
     * Maps only transactions that actually involve the selected watched script.
     * Both incoming outputs and inputs spending previous outputs belonging to
     * this exact watched script are included.
     */
    public static List<TransactionItem> mapForWatchedScript(
            Context context, Wallet wallet, org.bitcoinj.script.Script watchedScript) {
        return mapForWatchedScript(context, wallet, watchedScript,
                getTransactionsInDisplayOrder(wallet));
    }

    public static List<TransactionItem> mapForWatchedScript(
            Context context, Wallet wallet, org.bitcoinj.script.Script watchedScript,
            List<Transaction> orderedTransactions) {
        List<TransactionItem> items = new ArrayList<>();
        if (wallet == null || watchedScript == null) {
            return items;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US);

        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);

        // Use the current full wallet snapshot; the presenter reuses this exact
        // snapshot for its cache key, avoiding another full sort during rendering.
        for (Transaction transaction : orderedTransactions) {
            Coin received = Coin.ZERO;
            for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
                if (watchedScript.equals(output.getScriptPubKey())) {
                    received = received.add(output.getValue());
                }
            }

            Coin sent = valueSentFromWatchedScript(walletTransactions, transaction, watchedScript);
            Coin net = received.subtract(sent);

            if (net.isZero()) {
                continue;
            }

            addItem(items, context, wallet, walletTransactions, transaction, net, dateFormat);
        }

        return items;
    }

    /**
     * Maps main-wallet transactions from the distinct outputs owned by that scope
     * and the connected inputs that spend those outputs. Exact Request Nested
     * SegWit scripts are included; inferred P2SH alternatives and imported scopes
     * are excluded so history, balance and Coin Control share one ownership rule.
     */
    public static List<TransactionItem> mapForMainWallet(Context context, Wallet wallet) {
        return mapForMainWallet(context, wallet, getTransactionsInDisplayOrder(wallet));
    }

    public static List<TransactionItem> mapForMainWallet(
            Context context, Wallet wallet, List<Transaction> orderedTransactions) {
        List<TransactionItem> items = new ArrayList<>();
        if (wallet == null) {
            return items;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US);

        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);

        // Resolve ownership sets once per history snapshot. Doing this for every
        // input/output repeatedly walks preferences and wallet watched scripts,
        // which can stall history rendering while the wallet is catching up.
        Set<org.bitcoinj.script.Script> importedScripts =
                WalletSelection.getImportedScripts(context, wallet);
        Set<org.bitcoinj.script.Script> requestScripts =
                WalletSelection.getRequestNestedScripts(context, wallet);
        Set<org.bitcoinj.script.Script> watchedScripts =
                new java.util.HashSet<>(wallet.getWatchedScripts());

        // Read every transaction currently held by the live wallet, just as the
        // reference WalletTransactionsViewModel does. This includes transactions
        // already learned from synced blocks before the next live event arrives.
        for (Transaction transaction : orderedTransactions) {
            Coin net = valueForMainWallet(context, wallet, transaction, walletTransactions,
                    requestScripts, importedScripts, watchedScripts);

            if (net.isZero()) {
                continue;
            }

            addItem(items, context, wallet, walletTransactions, transaction, net, dateFormat);
        }
        return items;
    }

    /** Returns the net value for the main-wallet scope using distinct owned outputs and inputs. */
    public static Coin valueForMainWallet(
            Context context, Wallet wallet, Transaction transaction) {
        return valueForMainWallet(context, wallet, transaction, indexWalletTransactions(wallet));
    }

    private static Coin valueForMainWallet(
            Context context, Wallet wallet, Transaction transaction,
            Map<Sha256Hash, Transaction> walletTransactions) {
        if (wallet == null || transaction == null) return Coin.ZERO;
        if (context == null) return safeTransactionValue(transaction, wallet);
        Set<org.bitcoinj.script.Script> importedScripts =
                WalletSelection.getImportedScripts(context, wallet);
        Set<org.bitcoinj.script.Script> requestScripts =
                WalletSelection.getRequestNestedScripts(context, wallet);
        Set<org.bitcoinj.script.Script> watchedScripts =
                new java.util.HashSet<>(wallet.getWatchedScripts());
        return valueForMainWallet(context, wallet, transaction, walletTransactions,
                requestScripts, importedScripts, watchedScripts);
    }

    private static Coin valueForMainWallet(
            Context context, Wallet wallet, Transaction transaction,
            Map<Sha256Hash, Transaction> walletTransactions,
            Set<org.bitcoinj.script.Script> requestScripts,
            Set<org.bitcoinj.script.Script> importedScripts,
            Set<org.bitcoinj.script.Script> watchedScripts) {
        if (wallet == null || transaction == null) return Coin.ZERO;

        // Keep the main wallet, Request Nested SegWit and separate imported
        // scopes distinct while evaluating ownership against one stable snapshot.
        Coin received = Coin.ZERO;
        for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
            if (belongsToMainWalletScope(context, wallet, output,
                    requestScripts, importedScripts, watchedScripts)) {
                received = received.add(output.getValue());
            }
        }
        Coin sent = Coin.ZERO;
        for (org.bitcoinj.core.TransactionInput input : transaction.getInputs()) {
            org.bitcoinj.core.TransactionOutput connected = findConnectedOutput(walletTransactions, input);
            if (connected != null && belongsToMainWalletScope(context, wallet, connected,
                    requestScripts, importedScripts, watchedScripts)) {
                sent = sent.add(connected.getValue());
            }
        }
        return received.subtract(sent);
    }

    /** Gross value sent from the main-wallet scope, excluding separated scopes. */
    public static Coin valueSentFromMainWallet(
            Context context, Wallet wallet, Transaction transaction) {
        if (wallet == null || transaction == null) return Coin.ZERO;
        if (context == null) return safeValueSentFromMe(transaction, wallet);
        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);
        Coin sent = Coin.ZERO;
        for (org.bitcoinj.core.TransactionInput input : transaction.getInputs()) {
            org.bitcoinj.core.TransactionOutput connected = findConnectedOutput(walletTransactions, input);
            if (connected != null && belongsToMainWalletScope(context, wallet, connected)) {
                sent = sent.add(connected.getValue());
            }
        }
        return sent;
    }

    /** Gross value received by the main-wallet scope, excluding separate scopes. */
    public static Coin valueSentToMainWallet(
            Context context, Wallet wallet, Transaction transaction) {
        if (wallet == null || transaction == null) return Coin.ZERO;
        if (context == null) return safeValueSentToMe(transaction, wallet);
        Coin received = Coin.ZERO;
        for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
            if (belongsToMainWalletScope(context, wallet, output)) {
                received = received.add(output.getValue());
            }
        }
        return received;
    }

    /** An output belongs to main scope exactly once: wallet-owned or Request Nested P2SH. */
    public static boolean belongsToMainWalletScope(
            Context context, Wallet wallet, org.bitcoinj.core.TransactionOutput output) {
        if (output == null || wallet == null) return false;
        Set<org.bitcoinj.script.Script> importedScripts =
                WalletSelection.getImportedScripts(context, wallet);
        Set<org.bitcoinj.script.Script> requestScripts =
                WalletSelection.getRequestNestedScripts(context, wallet);
        Set<org.bitcoinj.script.Script> watchedScripts =
                new java.util.HashSet<>(wallet.getWatchedScripts());
        return belongsToMainWalletScope(context, wallet, output,
                requestScripts, importedScripts, watchedScripts);
    }

    private static boolean belongsToMainWalletScope(
            Context context, Wallet wallet, org.bitcoinj.core.TransactionOutput output,
            Set<org.bitcoinj.script.Script> requestScripts,
            Set<org.bitcoinj.script.Script> importedScripts,
            Set<org.bitcoinj.script.Script> watchedScripts) {
        if (output == null || wallet == null) return false;
        org.bitcoinj.script.Script script = output.getScriptPubKey();
        if (importedScripts.contains(script)) return false;
        if (requestScripts.contains(script)) return true;
        // Only explicitly registered Request Nested SegWit P2SH scripts belong to
        // the main wallet scope; do not count other P2SH outputs from HD lookahead.
        if (org.bitcoinj.script.ScriptPattern.isP2SH(script)) return false;
        return output.isMine(wallet) && !watchedScripts.contains(script);
    }

    /**
     * Shared output ownership rule used by transaction details and notifications.
     * A selected watched/imported script is an exact scope; otherwise use the
     * main-wallet ownership rule, including explicitly registered Request Nested P2SH.
     */
    public static boolean belongsToSelectedWalletScope(
            Context context, Wallet wallet, org.bitcoinj.core.TransactionOutput output,
            org.bitcoinj.script.Script selectedScopeScript) {
        if (output == null || wallet == null) return false;
        if (selectedScopeScript != null) {
            return selectedScopeScript.equals(output.getScriptPubKey());
        }
        return belongsToMainWalletScope(context, wallet, output);
    }

    /** Gross value received by an exact script scope. */
    public static Coin valueReceivedForScript(
            Transaction transaction, org.bitcoinj.script.Script script) {
        if (transaction == null || script == null) {
            return Coin.ZERO;
        }
        Coin received = Coin.ZERO;
        for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
            if (script.equals(output.getScriptPubKey())) {
                received = received.add(output.getValue());
            }
        }
        return received;
    }

    /** Gross value spent from an exact script scope. */
    public static Coin valueSentFromScript(
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction, org.bitcoinj.script.Script script) {
        return valueSentFromWatchedScript(walletTransactions, transaction, script);
    }

    /** Signed value for an exact script scope. */
    public static Coin valueForScriptScope(
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction, org.bitcoinj.script.Script script) {
        return valueReceivedForScript(transaction, script)
                .subtract(valueSentFromScript(walletTransactions, transaction, script));
    }

    private static Coin safeValueSentFromMe(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValueSentFromMe(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    private static Coin safeValueSentToMe(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValueSentToMe(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    /** Sum inputs that spend outputs belonging to the exact watched script. */
    private static Coin valueSentFromWatchedScript(
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction, org.bitcoinj.script.Script watchedScript) {
        Coin sent = Coin.ZERO;
        for (org.bitcoinj.core.TransactionInput input : transaction.getInputs()) {
            org.bitcoinj.core.TransactionOutput connected = findConnectedOutput(walletTransactions, input);
            if (connected != null && watchedScript.equals(connected.getScriptPubKey())) {
                sent = sent.add(connected.getValue());
            }
        }
        return sent;
    }

    /**
     * bitcoinj 0.17.1's canonical signed wallet value:
     * getValue(wallet) = valueSentToMe(wallet) - valueSentFromMe(wallet).
     */
    private static Coin safeTransactionValue(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValue(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    /**
     * Resolve an input against transactions stored by this wallet.
     *
     * bitcoinj 0.17.1 exposes TransactionInput#getConnectedOutput() without
     * arguments. For historical wallet transactions the safest way to resolve
     * an input is by its outpoint against the wallet's complete transaction set.
     */
    private static org.bitcoinj.core.TransactionOutput findConnectedOutput(
            Map<Sha256Hash, Transaction> walletTransactions,
            org.bitcoinj.core.TransactionInput input) {
        if (walletTransactions == null || input == null) {
            return null;
        }

        try {
            org.bitcoinj.core.TransactionOutput connected = input.getConnectedOutput();
            if (connected != null) {
                return connected;
            }
        } catch (Exception ignored) {
            // Fall through to the wallet transaction index.
        }

        org.bitcoinj.core.TransactionOutPoint outpoint = input.getOutpoint();
        if (outpoint == null || outpoint.hash() == null) {
            return null;
        }

        Transaction parent = walletTransactions.get(outpoint.hash());
        if (parent == null) {
            return null;
        }

        long index = outpoint.index();
        if (index < 0 || index >= parent.getOutputs().size()) {
            return null;
        }

        return parent.getOutput((int) index);
    }

    /**
     * Snapshot the complete transaction set known to bitcoinj in the same
     * order used by the reference wallet: pending transactions first, then
     * newest update time first. getTransactions(true) includes dead/replaced
     * entries too, so a block-sync refresh doesn't silently omit known history.
     */
    public static List<Transaction> getTransactionsInDisplayOrder(Wallet wallet) {
        if (wallet == null) return Collections.emptyList();
        List<Transaction> transactions = new ArrayList<>(wallet.getTransactions(true));
        transactions.sort((left, right) -> {
            TransactionConfidence leftConfidence = left.getConfidence();
            TransactionConfidence rightConfidence = right.getConfidence();
            boolean leftPending = leftConfidence != null
                    && leftConfidence.getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING;
            boolean rightPending = rightConfidence != null
                    && rightConfidence.getConfidenceType() == TransactionConfidence.ConfidenceType.PENDING;
            if (leftPending != rightPending) return leftPending ? -1 : 1;
            Instant leftTime = left.updateTime().orElse(Instant.EPOCH);
            Instant rightTime = right.updateTime().orElse(Instant.EPOCH);
            int timeOrder = rightTime.compareTo(leftTime);
            return timeOrder != 0 ? timeOrder : left.getTxId().compareTo(right.getTxId());
        });
        return transactions;
    }

    private static Map<Sha256Hash, Transaction> indexWalletTransactions(Wallet wallet) {
        Map<Sha256Hash, Transaction> result = new HashMap<>();
        for (Transaction transaction : wallet.getTransactions(true)) {
            result.put(transaction.getTxId(), transaction);
        }
        return result;
    }

    private static void addItem(
            List<TransactionItem> items,
            Context context,
            Wallet wallet,
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction,
            Coin net,
            SimpleDateFormat dateFormat) {
        String type = net.isPositive()
                ? context.getString(R.string.transaction_received)
                : context.getString(R.string.transaction_sent);
        String amount = net.isPositive()
                ? "+" + MoneyFormatter.format(context, net)
                : MoneyFormatter.format(context, net);

        String txId = transaction.getTxId().toString();
        String time = transaction.updateTime().isPresent()
                ? dateFormat.format(Date.from(transaction.updateTime().get()))
                : context.getString(R.string.unknown_time);

        TransactionConfidence confidence = transaction.getConfidence();
        int depth = confidence == null ? 0 : confidence.getDepthInBlocks();
        int peers = confidence == null ? 0 : confidence.numBroadcastPeers();

        String confirmations = depth > 0
                ? context.getString(depth == 1
                ? R.string.confirmed_one
                : R.string.confirmed_many, depth)
                : context.getString(R.string.unconfirmed);

        String state;
        if (depth > 0) {
            state = context.getString(R.string.transaction_state_confirmed);
        } else if (confidence != null
                && confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.DEAD) {
            state = context.getString(R.string.transaction_state_dead);
        } else if (peers > 0) {
            state = context.getString(R.string.transaction_state_broadcast, peers);
        } else {
            state = context.getString(R.string.transaction_state_pending);
        }

        String counterparty = counterpartyAddress(wallet, walletTransactions, transaction, net);
        items.add(new TransactionItem(
                type,
                amount,
                net,
                time,
                confirmations,
                txId,
                counterparty,
                context.getString(R.string.transaction_peers, peers),
                state));
    }

    private static String counterpartyAddress(Wallet wallet, Map<Sha256Hash, Transaction> walletTransactions, Transaction transaction, Coin net) {
        if (wallet == null || transaction == null) return "";
        try {
            if (net.isPositive()) {
                for (org.bitcoinj.core.TransactionInput input : transaction.getInputs()) {
                    org.bitcoinj.core.TransactionOutput output = findConnectedOutput(
                            walletTransactions, input);
                    if (output != null) {
                        return output.getScriptPubKey().getToAddress(wallet.getParams()).toString();
                    }
                }
            } else {
                for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
                    if (!output.isMine(wallet)) {
                        return output.getScriptPubKey().getToAddress(wallet.getParams()).toString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

}
