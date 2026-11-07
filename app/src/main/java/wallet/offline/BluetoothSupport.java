package wallet.offline;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.text.TextUtils;

import java.util.Locale;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/** Shared Bluetooth payment protocol constants and merchant configuration. */
public final class BluetoothSupport {
    private BluetoothSupport() { }
    public static final UUID PAYMENT_REQUESTS_UUID = UUID.fromString("3357A7BB-762D-464A-8D9A-DCA592D57D59");
    /** Private RFCOMM channel for direct BIP21 request handoff. */
    public static final UUID DIRECT_REQUEST_UUID = UUID.fromString("3357A7BB-762D-464A-8D9A-DCA592D57D5C");
    /** Direct BIP21 URI message identifier. */
    public static final String DIRECT_REQUEST_MAGIC = "WALLET-REQUEST-1";
    /** Direct transfer carrying the exact protobuf PaymentRequest payload used by NFC NDEF/HCE. */
    public static final String DIRECT_PAYMENT_REQUEST_MAGIC = "WALLET-PAYMENTREQUEST-1";
    public static final String EXTRA_DIRECT_PAYMENT_REQUEST = "wallet.extra.DIRECT_PAYMENT_REQUEST";
    public static final UUID BIP70_PAYMENT_PROTOCOL_UUID = UUID.fromString("3357A7BB-762D-464A-8D9A-DCA592D57D5A");
    public static final UUID CLASSIC_PAYMENT_PROTOCOL_UUID = UUID.fromString("3357A7BB-762D-464A-8D9A-DCA592D57D5B");
    public static final String MAC_URI_PARAM = "bt";
    public static final String PREFS = "merchant_bluetooth_settings";
    public static final String KEY_OWN_NAME = "own_name";
    public static final String KEY_BLUETOOTH_ADDRESS = "bluetooth_address";
    public static final String KEY_ACCEPT_PAYMENTS = "accept_payments";
    public static final String ACTION_RECEIVER_STOPPED = "wallet.action.BLUETOOTH_RECEIVER_STOPPED";

    public static boolean isAcceptingPayments(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ACCEPT_PAYMENTS, false);
    }

    public static void setAcceptingPayments(Context context, boolean accepting) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ACCEPT_PAYMENTS, accepting).apply();
    }

    public static String getOwnName(Context context) {
        String value = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OWN_NAME, "");
        value = value == null ? "" : value.trim();
        return value.isEmpty() ? null : value;
    }

    public static String getConfiguredAddress(Context context) {
        String value = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_BLUETOOTH_ADDRESS, "");
        return normalizeMac(value);
    }

    public static String getLocalAddress(Context context) {
        BluetoothManager manager = null;
        BluetoothAdapter adapter = null;
        try {
            manager = context.getSystemService(BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
            if (adapter != null) {
                String address = normalizeMac(adapter.getAddress());
                if (address != null && !"020000000000".equals(address)) return address;
            }
        } catch (SecurityException ignored) {
            // Android 12+ restricts the public API; attempt the same legacy fallback
            // used by the reference wallet, then use the manually configured address.
        } catch (RuntimeException ignored) { }

        if (adapter != null) {
            try {
                Field serviceField = BluetoothAdapter.class.getDeclaredField("mService");
                serviceField.setAccessible(true);
                Object service = serviceField.get(adapter);
                if (service != null) {
                    Method getAddress = service.getClass().getMethod("getAddress");
                    String address = normalizeMac((String) getAddress.invoke(service));
                    if (address != null && !"020000000000".equals(address)) return address;
                }
            } catch (Exception ignored) {
                // Hidden Android APIs can be blocked; manual configuration remains available.
            }
        }
        return getConfiguredAddress(context);
    }

    public static String normalizeMac(String input) {
        if (TextUtils.isEmpty(input)) return null;
        String compact = input.trim().replace(":", "").replace("-", "").replace(" ", "");
        return compact.matches("(?i)[0-9a-f]{12}") ? compact.toUpperCase(Locale.US) : null;
    }

    public static String formatMac(String input) {
        String mac = normalizeMac(input);
        if (mac == null) return null;
        StringBuilder out = new StringBuilder(17);
        for (int i = 0; i < mac.length(); i += 2) {
            if (out.length() > 0) out.append(':');
            out.append(mac, i, i + 2);
        }
        return out.toString();
    }

    public static String getBluetoothMacFromUrl(String url) {
        if (url == null || !url.regionMatches(true, 0, "bt:", 0, 3)) return null;
        String remainder = url.substring(3);
        int slash = remainder.indexOf('/');
        if (slash >= 0) remainder = remainder.substring(0, slash);
        return normalizeMac(remainder);
    }
}
