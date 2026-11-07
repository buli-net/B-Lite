package wallet.transaction;

import org.bitcoinj.base.Coin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import wallet.model.BalanceHistoryPoint;
import wallet.model.TransactionItem;

/** Reconstructs exact historical balances from the selected wallet scope's transaction history. */
public final class BalanceHistoryBuilder {

    public static final class Result {
        public final List<BalanceHistoryPoint> points;
        public final long historyStartTimeMillis;
        public final boolean available;

        private Result(List<BalanceHistoryPoint> points,
                       long historyStartTimeMillis,
                       boolean available) {
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.historyStartTimeMillis = historyStartTimeMillis;
            this.available = available;
        }
    }

    private BalanceHistoryBuilder() {
    }

    /**
     * Builds a balance history from only the transactions and current balance of the
     * currently selected scope (Main, one Watch-only script, or one imported address).
     *
     * Deliberately does not accept the Wallet object: Wallet contains keychains and
     * imported keys from multiple scopes, so its global birthday cannot identify the
     * creation time of the selected scope. The All range is anchored at the genesis
     * block of the currently selected Bitcoin network instead of any wallet birthday.
     */
    public static Result build(List<TransactionItem> transactions,
                               Coin currentBalance,
                               long genesisTimeMillis) {
        if (transactions == null || currentBalance == null || currentBalance.value < 0L) {
            return unavailable(genesisTimeMillis);
        }

        long now = System.currentTimeMillis();
        if (genesisTimeMillis <= 0L || genesisTimeMillis > now) {
            return unavailable(0L);
        }
        Map<Long, Long> changesByTime = new TreeMap<>();
        long totalChange = 0L;
        try {
            for (TransactionItem item : transactions) {
                if (item == null || item.dead || item.rawAmount == null
                        || item.rawAmount.isZero()) {
                    continue;
                }
                if (item.timestampMillis < genesisTimeMillis || item.timestampMillis > now) {
                    return unavailable(genesisTimeMillis);
                }

                long change = item.rawAmount.value;
                totalChange = Math.addExact(totalChange, change);
                long previous = changesByTime.containsKey(item.timestampMillis)
                        ? changesByTime.get(item.timestampMillis) : 0L;
                changesByTime.put(item.timestampMillis, Math.addExact(previous, change));
            }
        } catch (ArithmeticException invalidAmount) {
            return unavailable(genesisTimeMillis);
        }

        // The selected scope's known event total must reconcile exactly with its
        // current balance, in satoshis. A mismatch means this snapshot is incomplete
        // or inconsistent and must not be drawn as a valid balance history.
        if (totalChange != currentBalance.value) {
            return unavailable(genesisTimeMillis);
        }

        List<BalanceHistoryPoint> points = new ArrayList<>();
        long runningBalance = 0L;
        points.add(new BalanceHistoryPoint(genesisTimeMillis, runningBalance));
        try {
            for (Map.Entry<Long, Long> entry : changesByTime.entrySet()) {
                runningBalance = Math.addExact(runningBalance, entry.getValue());
                if (runningBalance < 0L) {
                    return unavailable(genesisTimeMillis);
                }
                points.add(new BalanceHistoryPoint(entry.getKey(), runningBalance));
            }
        } catch (ArithmeticException invalidAmount) {
            return unavailable(genesisTimeMillis);
        }

        if (runningBalance != currentBalance.value) {
            return unavailable(genesisTimeMillis);
        }
        return new Result(points, genesisTimeMillis, true);
    }

    private static Result unavailable(long genesisTimeMillis) {
        return new Result(Collections.emptyList(), genesisTimeMillis, false);
    }
}
