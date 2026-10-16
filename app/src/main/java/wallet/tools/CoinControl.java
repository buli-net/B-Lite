package wallet.tools;

import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.wallet.CoinSelector;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Stores the UTXOs selected for the next send, tied to one selected-wallet scope. */
public final class CoinControl {

    private static final Object LOCK = new Object();
    private static final Set<String> selected = new HashSet<>();
    private static String selectedScope;

    private CoinControl() {
    }

    public static void setSelected(String scope, Set<String> outpoints) {
        synchronized (LOCK) {
            selected.clear();
            if (outpoints != null) selected.addAll(outpoints);
            selectedScope = scope;
        }
    }

    /** Returns selections only when they were made for the exact active wallet/network scope. */
    public static Set<String> getSelected(String scope) {
        synchronized (LOCK) {
            if (scope == null || !scope.equals(selectedScope)) {
                return Collections.emptySet();
            }
            return Collections.unmodifiableSet(new HashSet<>(selected));
        }
    }

    public static boolean isSelected(TransactionOutput output, String scope) {
        if (output == null || scope == null) return false;
        String outpoint = key(output);
        synchronized (LOCK) {
            return scope.equals(selectedScope) && selected.contains(outpoint);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            selected.clear();
            selectedScope = null;
        }
    }

    public static CoinSelector selector(String scope, CoinSelector delegate) {
        final Set<String> selectedNow = getSelected(scope);
        if (selectedNow.isEmpty()) {
            return delegate;
        }
        return (target, candidates) -> {
            java.util.ArrayList<TransactionOutput> filtered = new java.util.ArrayList<>();
            for (TransactionOutput output : candidates) {
                if (selectedNow.contains(key(output))) {
                    filtered.add(output);
                }
            }
            return delegate.select(target, filtered);
        };
    }

    public static String key(TransactionOutput output) {
        TransactionOutPoint outPoint = output.getOutPointFor();
        Sha256Hash hash = outPoint.hash();
        return hash + ":" + outPoint.index();
    }
}
