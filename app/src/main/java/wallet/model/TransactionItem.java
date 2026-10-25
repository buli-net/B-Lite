package wallet.model;

import org.bitcoinj.base.Coin;

/** Immutable transaction data used by wallet lists. */
public final class TransactionItem {
    public final String type;
    public final String amount;
    /** Exact wallet amount retained so display units can change without reloading history. */
    public final Coin rawAmount;
    public final String time;
    public final String confirmations;
    public final String txid;
    public final String counterparty;
    public final String peers;
    public final String state;

    public TransactionItem(String type, String amount, Coin rawAmount, String time,
                           String confirmations, String txid, String counterparty,
                           String peers, String state) {
        this.type = type;
        this.amount = amount;
        this.rawAmount = rawAmount;
        this.time = time;
        this.confirmations = confirmations;
        this.txid = txid;
        this.counterparty = counterparty;
        this.peers = peers;
        this.state = state;
    }
}
