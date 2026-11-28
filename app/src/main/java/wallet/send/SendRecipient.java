package wallet.send;

/** Raw address/amount pair collected from one recipient row on the Send screen. */
public final class SendRecipient {
    public final String address;
    public final String amount;

    public SendRecipient(String address, String amount) {
        this.address = address == null ? "" : address.trim();
        this.amount = amount == null ? "" : amount.trim();
    }
}
