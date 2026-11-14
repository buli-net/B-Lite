package wallet.send;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptPattern;

/**
 * Small policy adapter around bitcoinj transaction-output policy.
 *
 * bitcoinj 0.17.1 calculates script-specific dust thresholds for the output types it models.
 * Its dust helper returns Coin.ZERO for Taproot, so Taproot uses the same generic witness-input
 * size assumption used by Bitcoin Core's dust policy with bitcoinj's reference relay fee. For
 * otherwise unsupported scripts, use the conservative legacy threshold instead of zero.
 * Output serialization sizes are always measured by bitcoinj.
 */
public final class TransactionPolicy {
    private static final long CONSERVATIVE_FALLBACK_DUST_SAT = 546L;

    private TransactionPolicy() { }

    public static long minimumNonDustValue(Address destination) {
        if (destination == null) return CONSERVATIVE_FALLBACK_DUST_SAT;
        return minimumNonDustValue(ScriptBuilder.createOutputScript(destination));
    }

    /** Serialized size of a destination output, calculated by bitcoinj. */
    public static int serializedOutputSize(Address destination) {
        if (destination == null) throw new IllegalArgumentException();
        TransactionOutput output = new TransactionOutput(null, Coin.ZERO, destination);
        return output.messageSize();
    }

    /** Serialized size of an output to a script, calculated by bitcoinj. */
    public static int serializedOutputSize(Script script) {
        if (script == null) throw new IllegalArgumentException();
        TransactionOutput output = new TransactionOutput(null, Coin.ZERO, script.program());
        return output.messageSize();
    }

    public static long minimumNonDustValue(Script script) {
        if (script == null) return CONSERVATIVE_FALLBACK_DUST_SAT;
        try {
            TransactionOutput probe = new TransactionOutput(null, Coin.ZERO, script.program());
            if (ScriptPattern.isP2TR(script)) {
                // Bitcoin Core treats Taproot as a witness program for dust sizing. bitcoinj 0.17.1
                // omits P2TR from getMinNonDustValue(), so use its 67-byte witness-input estimate,
                // bitcoinj's serialized output size and the same default relay fee as bitcoinj.
                long estimatedSpendInputSize = 32L + 4L + 1L + (107L / 4L) + 4L;
                long relayFeePerKb = Transaction.REFERENCE_DEFAULT_MIN_TX_FEE.multiply(3).value;
                return relayFeePerKb * (probe.messageSize() + estimatedSpendInputSize) / 1000L;
            }
            long value = probe.getMinNonDustValue().value;
            return value > 0L ? value : CONSERVATIVE_FALLBACK_DUST_SAT;
        } catch (RuntimeException unsupportedScript) {
            // Do not treat bitcoinj's unsupported-script sentinel as a zero dust threshold.
            return CONSERVATIVE_FALLBACK_DUST_SAT;
        }
    }
}
