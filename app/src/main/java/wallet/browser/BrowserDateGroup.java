package wallet.browser;

import android.content.Context;

import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

import wallet.main.R;

/** Formats local-calendar day headings shared by browsing history and downloads. */
final class BrowserDateGroup {
    private BrowserDateGroup() { }

    static String key(long timestamp) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(timestamp);
        return day.get(Calendar.ERA) + "-" + day.get(Calendar.YEAR) + "-" + day.get(Calendar.DAY_OF_YEAR);
    }

    static String label(Context context, long timestamp) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(timestamp);
        Calendar today = Calendar.getInstance();
        Calendar yesterday = (Calendar) today.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        String date = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
                .format(new Date(timestamp));
        if (sameDay(target, today)) return context.getString(R.string.browser_date_today, date);
        if (sameDay(target, yesterday)) return context.getString(R.string.browser_date_yesterday, date);
        return date;
    }

    private static boolean sameDay(Calendar left, Calendar right) {
        return left.get(Calendar.ERA) == right.get(Calendar.ERA)
                && left.get(Calendar.YEAR) == right.get(Calendar.YEAR)
                && left.get(Calendar.DAY_OF_YEAR) == right.get(Calendar.DAY_OF_YEAR);
    }
}
