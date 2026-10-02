package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent names and address registry for imported WIF wallets. */
public final class ImportedWalletStore {
    private static final String PREFS = "imported_wif_wallets";
    private static final String PREFIX = "name.";
    private static final String KEY_ADDRESSES = "addresses";
    private static final String KEY_ADDRESS_LIST = "address_list_v2";

    private ImportedWalletStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getName(Context context, String address, int fallbackIndex) {
        if (address == null || address.trim().isEmpty()) {
            return context.getString(R.string.imported_wallet_default_name);
        }
        String cleanAddress = address.trim();
        String value = prefs(context).getString(PREFIX + cleanAddress, null);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return context.getString(R.string.imported_wallet_default_name_numbered, Math.max(1, fallbackIndex));
    }

    /** Registers an imported address so the management screen can render it immediately. */
    public static synchronized void register(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return;
        String cleanAddress = address.trim();
        SharedPreferences p = prefs(context);
        java.util.LinkedHashSet<String> updated = new java.util.LinkedHashSet<>();
        String encoded = p.getString(KEY_ADDRESS_LIST, null);
        if (encoded != null && !encoded.trim().isEmpty()) {
            for (String item : encoded.split("\\|")) {
                if (!item.trim().isEmpty()) updated.add(item.trim());
            }
        }
        // Migrate addresses written by older builds using StringSet.
        try {
            Set<String> legacy = p.getStringSet(KEY_ADDRESSES, null);
            if (legacy != null) {
                for (String item : legacy) {
                    if (item != null && !item.trim().isEmpty()) updated.add(item.trim());
                }
            }
        } catch (ClassCastException ignored) {
            // KEY_ADDRESSES may already have been changed by a future/older format.
        }
        updated.add(cleanAddress);
        p.edit().putString(KEY_ADDRESS_LIST, join(updated)).apply();
    }

    private static String join(java.util.Collection<String> addresses) {
        StringBuilder out = new StringBuilder();
        for (String address : addresses) {
            if (out.length() > 0) out.append('|');
            out.append(address);
        }
        return out.toString();
    }

    /** Returns the persisted imported-address registry in stable insertion order. */
    public static synchronized List<String> getAddresses(Context context) {
        SharedPreferences p = prefs(context);
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        String encoded = p.getString(KEY_ADDRESS_LIST, null);
        if (encoded != null && !encoded.trim().isEmpty()) {
            for (String item : encoded.split("\\|")) {
                if (!item.trim().isEmpty()) result.add(item.trim());
            }
        }
        try {
            Set<String> legacy = p.getStringSet(KEY_ADDRESSES, null);
            if (legacy != null) {
                for (String item : legacy) {
                    if (item != null && !item.trim().isEmpty()) result.add(item.trim());
                }
            }
        } catch (ClassCastException ignored) {
            // Older installations may store this preference under a different type.
        }
        return new ArrayList<>(result);
    }

    public static void setName(Context context, String address, String name) {
        if (address == null || address.trim().isEmpty() || name == null) return;
        String cleanAddress = address.trim();
        String clean = name.trim();
        if (clean.isEmpty()) return;
        register(context, cleanAddress);
        prefs(context).edit().putString(PREFIX + cleanAddress, clean).apply();
    }

    public static void remove(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return;
        String cleanAddress = address.trim();
        List<String> current = getAddresses(context);
        current.remove(cleanAddress);
        prefs(context).edit()
                .remove(PREFIX + cleanAddress)
                .putString(KEY_ADDRESS_LIST, join(current))
                .apply();
    }
}
