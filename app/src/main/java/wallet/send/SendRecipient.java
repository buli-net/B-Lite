package wallet.send;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;

/** One explicit output in a multi-recipient send or Payment Protocol request. */
public final class SendRecipient {
    private final Address address;
    private final Coin amount;

    public SendRecipient(Address address, Coin amount) {
        if (address == null || amount == null || !amount.isPositive()) {
            throw new IllegalArgumentException("Recipient address and positive amount are required");
        }
        this.address = address;
        this.amount = amount;
    }

    public Address address() {
        return address;
    }

    public Coin amount() {
        return amount;
    }
}
