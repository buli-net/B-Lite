package wallet.send;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;

/** Validated output requested by the sender or a Payment Protocol request. */
public final class SendOutput {
    public final Address address;
    public final Coin amount;

    public SendOutput(Address address, Coin amount) {
        if (address == null || amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("A positive amount and destination are required");
        }
        this.address = address;
        this.amount = amount;
    }
}
