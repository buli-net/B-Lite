package wallet.model;

/** Exact wallet balance at a transaction event time, stored in satoshis. */
public final class BalanceHistoryPoint {
    public final long timestampMillis;
    public final long balanceSatoshis;

    public BalanceHistoryPoint(long timestampMillis, long balanceSatoshis) {
        this.timestampMillis = timestampMillis;
        this.balanceSatoshis = balanceSatoshis;
    }
}
