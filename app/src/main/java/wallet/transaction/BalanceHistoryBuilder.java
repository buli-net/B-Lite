package wallet.transaction;

import org.bitcoinj.base.Coin;
import org.bitcoinj.wallet.Wallet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import wallet.model.BalanceHistoryPoint;
import wallet.model.TransactionItem;

/** Reconstructs exact historical balances from the wallet's own signed transaction history. */
public final class BalanceHistoryBuilder {

    public static final class Result {
        public final List<BalanceHistoryPoint> points;
        public final long walletCreationTimeMillis;
        public final boolean available;

        private Result(List<BalanceHistoryPoint> points,
                       long walletCreationTimeMillis,
                       boolean available) {
            this.points = Collections.unmodifiableList(new ArrayList<>(points));
            this.walletCreationTimeMillis = walletCreationTimeMillis;
            this.available = available;
        }
    }

    private BalanceHistoryBuilder() {
    }

    public static Result build(Wallet wallet,
                               List<TransactionItem> transactions,
                               Coin currentBalance) {
        if (wallet == null || transactions == null || currentBalance == null
                || currentBalance.value < 0L) {
            return unavailable();
        }

        long createdAt = readWalletCreationTime(wallet);
        long now = System.currentTimeMillis();
        if (createdAt <= 0L || createdAt > now) {
            return unavailable();
        }

        Map<Long, Long> changesByTime = new TreeMap<>();
        long totalChange = 0L;
        try {
            for (TransactionItem item : transactions) {
                if (item == null || item.dead || item.rawAmount == null
                        || item.rawAmount.isZero()) {
                    continue;
                }
                if (item.timestampMillis <= 0L || item.timestampMillis < createdAt
                        || item.timestampMillis > now) {
                    return unavailable();
                }
                long change = item.rawAmount.value;
                totalChange = Math.addExact(totalChange, change);
                long previous = changesByTime.containsKey(item.timestampMillis)
                        ? changesByTime.get(item.timestampMillis) : 0L;
                changesByTime.put(item.timestampMillis, Math.addExact(previous, change));
            }
        } catch (ArithmeticException invalidAmount) {
            return unavailable();
        }

        // The history is complete only when replaying all known, live wallet events
        // from an empty wallet reproduces the exact current balance in satoshis.
        if (totalChange != currentBalance.value) {
            return unavailable();
        }

        List<BalanceHistoryPoint> points = new ArrayList<>();
        long runningBalance = 0L;
        points.add(new BalanceHistoryPoint(createdAt, runningBalance));
        try {
            for (Map.Entry<Long, Long> entry : changesByTime.entrySet()) {
                runningBalance = Math.addExact(runningBalance, entry.getValue());
                if (runningBalance < 0L) {
                    return unavailable();
                }
                points.add(new BalanceHistoryPoint(entry.getKey(), runningBalance));
            }
        } catch (ArithmeticException invalidAmount) {
            return unavailable();
        }

        if (runningBalance != currentBalance.value) {
            return unavailable();
        }
        return new Result(points, createdAt, true);
    }

    private static long readWalletCreationTime(Wallet wallet) {
        try {
            Instant earliest = wallet.earliestKeyCreationTime();
            if (earliest == null || Instant.EPOCH.equals(earliest)
                    || Instant.MAX.equals(earliest)) {
                return 0L;
            }
            return earliest.toEpochMilli();
        } catch (Exception outOfRangeOrUnavailable) {
            return 0L;
        }
    }

    private static Result unavailable() {
        return new Result(Collections.emptyList(), 0L, false);
    }
}
