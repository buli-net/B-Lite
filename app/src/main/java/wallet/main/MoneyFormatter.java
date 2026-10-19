package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.utils.MonetaryFormat;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Centralized display/input conversion for bitcoin amounts. Formatting never mutates Coin values. */
public final class MoneyFormatter {
    private static final String PREFS = "money_display_preferences";
    private static final String KEY_UNIT = "unit";
    private static final String KEY_PRECISION = "precision";
    private static final String KEY_PRECISION_PREFIX = "precision_";
    private static final String KEY_PRECISION_MIGRATED = "precision_migrated";
    public static final String BTC = "BTC";
    public static final String MBTC = "mBTC";
    public static final String UBTC = "µBTC";
    public static final String SAT = "sat";

    private MoneyFormatter() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getUnit(Context context) {
        String unit = prefs(context).getString(KEY_UNIT, BTC);
        return BTC.equals(unit) || MBTC.equals(unit) || UBTC.equals(unit) || SAT.equals(unit) ? unit : BTC;
    }

    public static int maxPrecisionForUnit(String unit) {
        if (MBTC.equals(unit)) return 5; // 1 mBTC = 100,000 sat
        if (UBTC.equals(unit)) return 2; // 1 µBTC = 100 sat
        if (SAT.equals(unit)) return 0;
        return 8; // BTC
    }

    private static String precisionKey(String unit) {
        return KEY_PRECISION_PREFIX + unit;
    }

    public static int getPrecision(Context context) {
        String unit = getUnit(context);
        int max = maxPrecisionForUnit(unit);
        SharedPreferences preferences = prefs(context);
        String unitKey = precisionKey(unit);
        if (preferences.contains(unitKey)) {
            return Math.max(0, Math.min(max, preferences.getInt(unitKey, max)));
        }
        // Migrate the old single precision exactly once to the unit that was selected before this screen.
        if (!preferences.getBoolean(KEY_PRECISION_MIGRATED, false)) {
            int legacyValue = Math.max(0, Math.min(max, preferences.getInt(KEY_PRECISION, max)));
            preferences.edit().putInt(unitKey, legacyValue)
                    .putBoolean(KEY_PRECISION_MIGRATED, true).apply();
            return legacyValue;
        }
        return max;
    }

    public static void setUnit(Context context, String unit) {
        if (!BTC.equals(unit) && !MBTC.equals(unit) && !UBTC.equals(unit) && !SAT.equals(unit)) unit = BTC;
        String previous = getUnit(context);
        if (!previous.equals(unit)) getPrecision(context); // Preserve/migrate the previous unit before switching.
        prefs(context).edit().putString(KEY_UNIT, unit).apply();
    }

    public static void setPrecision(Context context, int precision) {
        String unit = getUnit(context);
        precision = Math.max(0, Math.min(maxPrecisionForUnit(unit), precision));
        prefs(context).edit()
                .putInt(precisionKey(unit), precision)
                .putInt(KEY_PRECISION, precision)
                .putBoolean(KEY_PRECISION_MIGRATED, true)
                .apply();
    }

    /** Called by settings UI or tests; values are validated and persisted. */
    public static void setDisplay(Context context, String unit, int precision) {
        if (!BTC.equals(unit) && !MBTC.equals(unit) && !UBTC.equals(unit) && !SAT.equals(unit)) unit = BTC;
        setUnit(context, unit);
        setPrecision(context, precision);
    }

    public static String format(Context context, Coin coin) {
        if (coin == null) return "—";
        String unit = getUnit(context);
        int precision = getPrecision(context);
        int shift = BTC.equals(unit) ? 0 : MBTC.equals(unit) ? 3 : UBTC.equals(unit) ? 6 : 8;
        MonetaryFormat format = new MonetaryFormat().shift(shift).minDecimals(0)
                .optionalDecimals(precision).postfixCode()
                .roundingMode(RoundingMode.HALF_UP);
        // MonetaryFormat's optional-decimal count is display-only; the original Coin remains exact.
        return format.format(coin).toString();
    }

    /** Parse user-entered display units into exact satoshis; never parse a rounded display string back. */
    public static Coin parse(Context context, String text) {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Empty amount");
        String value = text.trim().replace(',', '.');
        BigDecimal entered = new BigDecimal(value);
        int shift = BTC.equals(getUnit(context)) ? 0 : MBTC.equals(getUnit(context)) ? 3
                : UBTC.equals(getUnit(context)) ? 6 : 8;
        BigDecimal satoshis = entered.movePointRight(8 - shift).setScale(0, RoundingMode.UNNECESSARY);
        return Coin.valueOf(satoshis.longValueExact());
    }
}
