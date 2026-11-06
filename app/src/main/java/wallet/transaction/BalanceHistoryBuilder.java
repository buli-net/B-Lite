package wallet.transaction;

import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.Wallet;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        Map<Long, Long> changesByTime = new TreeMap<>();
        long totalChange = 0L;
        long earliestTransactionTime = Long.MAX_VALUE;
        try {
            for (TransactionItem item : transactions) {
                if (item == null || item.dead || item.rawAmount == null
                        || item.rawAmount.isZero()) {
                    continue;
                }
                if (item.timestampMillis <= 0L || item.timestampMillis > now) {
                    return unavailable();
                }
                earliestTransactionTime = Math.min(
                        earliestTransactionTime, item.timestampMillis);
                long change = item.rawAmount.value;
                totalChange = Math.addExact(totalChange, change);
                long previous = changesByTime.containsKey(item.timestampMillis)
                        ? changesByTime.get(item.timestampMillis) : 0L;
                changesByTime.put(item.timestampMillis, Math.addExact(previous, change));
            }
        } catch (ArithmeticException invalidAmount) {
            return unavailable();
        }

        // An imported WIF may have no recorded creation time. Bitcoinj then reports
        // Instant.EPOCH for Wallet.earliestKeyCreationTime(), even when the HD seed
        // and every other wallet scope have a valid birthday. Use the oldest known
        // deterministic/imported-key timestamp, and extend it only when this exact
        // transaction scope contains an earlier real event.
        if (earliestTransactionTime != Long.MAX_VALUE) {
            if (createdAt <= 0L || createdAt > now) {
                createdAt = earliestTransactionTime;
            } else {
                createdAt = Math.min(createdAt, earliestTransactionTime);
            }
        }
        if (createdAt <= 0L || createdAt > now) {
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
            Instant earliest = null;
            List<DeterministicKeyChain> deterministicChains = wallet.getActiveKeyChains();

            // Deterministic chain birthdays are independent of imported WIF keys.
            // An unknown deterministic birthday remains unknown; do not silently
            // replace it with a newer imported-key timestamp.
            if (deterministicChains != null) {
                for (DeterministicKeyChain chain : deterministicChains) {
                    if (chain == null) {
                        continue;
                    }
                    Instant chainTime = chain.earliestKeyCreationTime();
                    if (!isUsableCreationTime(chainTime)) {
                        return 0L;
                    }
                    if (earliest == null || chainTime.isBefore(earliest)) {
                        earliest = chainTime;
                    }
                }
            }

            // Keep valid dates for manually imported watch-only keys, but ignore
            // unknown timestamps on imported private keys. Those keys still cause
            // bitcoinj to use EPOCH for sync catch-up; this method only determines
            // the chart's display start and does not alter key metadata or sync.
            for (ECKey key : wallet.getImportedKeys()) {
                if (key == null) {
                    continue;
                }
                Optional<Instant> keyTime = key.getCreationTime();
                if (!keyTime.isPresent() || !isUsableCreationTime(keyTime.get())) {
                    continue;
                }
                Instant value = keyTime.get();
                if (earliest == null || value.isBefore(earliest)) {
                    earliest = value;
                }
            }

            if (earliest != null) {
                return earliest.toEpochMilli();
            }

            // Preserve the previous behavior for basic/watch-only wallets whose
            // known birthday is exposed by Wallet but not through an active HD chain.
            Instant walletTime = wallet.earliestKeyCreationTime();
            if (isUsableCreationTime(walletTime)) {
                return walletTime.toEpochMilli();
            }
        } catch (Exception outOfRangeOrUnavailable) {
            return 0L;
        }
        return 0L;
    }

    private static boolean isUsableCreationTime(Instant value) {
        return value != null && !Instant.EPOCH.equals(value) && !Instant.MAX.equals(value);
    }

    private static Result unavailable() {
        return new Result(Collections.emptyList(), 0L, false);
    }
}
