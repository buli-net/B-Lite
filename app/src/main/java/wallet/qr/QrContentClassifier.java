package wallet.qr;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.TextUtils;

import wallet.main.R;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Recognizes common QR payload conventions and prepares explicit user-triggered actions. */
final class QrContentClassifier {
    static final class Detection {
        final String type;
        final int typeLabel;
        final int actionLabel;
        final boolean actionable;

        Detection(String type, int typeLabel, int actionLabel, boolean actionable) {
            this.type = type;
            this.typeLabel = typeLabel;
            this.actionLabel = actionLabel;
            this.actionable = actionable;
        }
    }

    private QrContentClassifier() { }

    static Detection detect(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.regionMatches(true, 0, "WIFI:", 0, 5))
            return new Detection(QrPayloadBuilder.TYPE_WIFI, R.string.qr_tools_type_wifi,
                    R.string.qr_tools_open_wifi, true);
        if (value.regionMatches(true, 0, "BEGIN:VCARD", 0, 11)
                || value.regionMatches(true, 0, "MECARD:", 0, 7))
            return new Detection(QrPayloadBuilder.TYPE_CONTACT, R.string.qr_tools_type_contact,
                    R.string.qr_tools_open_contact, true);
        if (value.regionMatches(true, 0, "BEGIN:VCALENDAR", 0, 15)
                || value.regionMatches(true, 0, "BEGIN:VEVENT", 0, 12))
            return new Detection(QrPayloadBuilder.TYPE_EVENT, R.string.qr_tools_type_event,
                    R.string.qr_tools_open_event, true);
        if (value.regionMatches(true, 0, "MATMSG:", 0, 7)
                || value.regionMatches(true, 0, "mailto:", 0, 7)
                || android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches())
            return new Detection(QrPayloadBuilder.TYPE_EMAIL, R.string.qr_tools_type_email,
                    R.string.qr_tools_open_email, true);
        if (value.regionMatches(true, 0, "SMSTO:", 0, 6)
                || value.regionMatches(true, 0, "sms:", 0, 4)
                || value.regionMatches(true, 0, "mmsto:", 0, 6))
            return new Detection(QrPayloadBuilder.TYPE_SMS, R.string.qr_tools_type_sms,
                    R.string.qr_tools_open_sms, true);
        if (value.regionMatches(true, 0, "tel:", 0, 4))
            return new Detection(QrPayloadBuilder.TYPE_PHONE, R.string.qr_tools_type_phone,
                    R.string.qr_tools_open_phone, true);
        if (value.regionMatches(true, 0, "geo:", 0, 4))
            return new Detection(QrPayloadBuilder.TYPE_LOCATION, R.string.qr_tools_type_location,
                    R.string.qr_tools_open_location, true);
        if (value.regionMatches(true, 0, "bitcoin:", 0, 8))
            return new Detection(QrPayloadBuilder.TYPE_BITCOIN, R.string.qr_tools_type_bitcoin,
                    R.string.qr_tools_open_bitcoin, true);
        if (value.matches("(?i)^(https?://|www\\.).+"))
            return new Detection(QrPayloadBuilder.TYPE_URL, R.string.qr_tools_type_url,
                    R.string.qr_tools_open_url, true);
        return new Detection(QrPayloadBuilder.TYPE_TEXT, R.string.qr_tools_type_text, 0, false);
    }

    static Intent createIntent(Context context, String raw) {
        String value = raw == null ? "" : raw.trim();
        Detection detection = detect(value);
        switch (detection.type) {
            case QrPayloadBuilder.TYPE_URL:
                return new Intent(Intent.ACTION_VIEW, Uri.parse(hasHttpScheme(value)
                        ? value : "https://" + value));
            case QrPayloadBuilder.TYPE_PHONE:
                return new Intent(Intent.ACTION_DIAL, Uri.parse(value));
            case QrPayloadBuilder.TYPE_SMS:
                return createSmsIntent(value);
            case QrPayloadBuilder.TYPE_EMAIL:
                return createEmailIntent(value);
            case QrPayloadBuilder.TYPE_CONTACT:
                return createContactIntent(value);
            case QrPayloadBuilder.TYPE_LOCATION:
                return new Intent(Intent.ACTION_VIEW, Uri.parse(value));
            case QrPayloadBuilder.TYPE_EVENT:
                return createEventIntent(value);
            case QrPayloadBuilder.TYPE_WIFI:
                return new Intent(Settings.ACTION_WIFI_SETTINGS);
            case QrPayloadBuilder.TYPE_BITCOIN:
                Intent send = new Intent(context, wallet.send.SendActivity.class);
                send.setAction(Intent.ACTION_VIEW);
                send.setData(Uri.parse(value));
                return send;
            default:
                return null;
        }
    }

    private static boolean looksLikeWebAddress(String value) {
        if (value.matches("(?i)^(https?://|www\\.).+")) return true;
        if (TextUtils.isEmpty(value) || value.matches(".*\\s+.*")) return false;
        return android.util.Patterns.WEB_URL.matcher(value).matches();
    }

    private static boolean hasHttpScheme(String value) {
        return value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8);
    }

    static String displayType(Context context, String raw) {
        Detection detection = detect(raw);
        return context.getString(detection.typeLabel);
    }

    private static Intent createSmsIntent(String value) {
        String number;
        String body = "";
        if (value.regionMatches(true, 0, "SMSTO:", 0, 6)) {
            String tail = value.substring(6);
            int separator = tail.indexOf(':');
            number = separator < 0 ? tail : tail.substring(0, separator);
            if (separator >= 0) body = tail.substring(separator + 1);
        } else {
            int colon = value.indexOf(':');
            String tail = colon < 0 ? value : value.substring(colon + 1);
            int query = tail.indexOf('?');
            number = query < 0 ? tail : tail.substring(0, query);
            if (query >= 0) {
                String queryPart = tail.substring(query + 1);
                for (String item : queryPart.split("&")) {
                    int equals = item.indexOf('=');
                    if (equals > 0 && "body".equalsIgnoreCase(Uri.decode(item.substring(0, equals)))) {
                        body = Uri.decode(item.substring(equals + 1));
                    }
                }
            }
        }
        Intent intent = new Intent(Intent.ACTION_SENDTO,
                Uri.parse("smsto:" + Uri.encode(number, "+0123456789*#-().")));
        if (!TextUtils.isEmpty(body)) intent.putExtra("sms_body", body);
        return intent;
    }

    private static Intent createEmailIntent(String value) {
        String to = "";
        String subject = "";
        String body = "";
        if (value.regionMatches(true, 0, "mailto:", 0, 7)) {
            String tail = value.substring(7);
            int query = tail.indexOf('?');
            to = query < 0 ? tail : tail.substring(0, query);
            if (query >= 0) {
                for (String item : tail.substring(query + 1).split("&")) {
                    int equals = item.indexOf('=');
                    if (equals <= 0) continue;
                    String key = Uri.decode(item.substring(0, equals));
                    String data = Uri.decode(item.substring(equals + 1));
                    if ("subject".equalsIgnoreCase(key)) subject = data;
                    else if ("body".equalsIgnoreCase(key)) body = data;
                }
            }
            to = Uri.decode(to);
        } else if (value.regionMatches(true, 0, "MATMSG:", 0, 7)) {
            for (String token : value.substring(7).split(";")) {
                int colon = token.indexOf(':');
                if (colon < 0) continue;
                String key = token.substring(0, colon).trim().toUpperCase(Locale.ROOT);
                String data = token.substring(colon + 1).trim();
                if ("TO".equals(key)) to = data;
                else if ("SUB".equals(key)) subject = data;
                else if ("BODY".equals(key)) body = data;
            }
        } else {
            to = value;
        }
        String mailUri = "mailto:" + Uri.encode(to, "@.+-_");
        StringBuilder query = new StringBuilder();
        if (!TextUtils.isEmpty(subject)) query.append("subject=").append(Uri.encode(subject));
        if (!TextUtils.isEmpty(body)) {
            if (query.length() > 0) query.append('&');
            query.append("body=").append(Uri.encode(body));
        }
        if (query.length() > 0) mailUri += "?" + query;
        return new Intent(Intent.ACTION_SENDTO, Uri.parse(mailUri));
    }

    private static Intent createContactIntent(String value) {
        String name = "";
        String phone = "";
        String email = "";
        String company = "";
        String address = "";
        if (value.regionMatches(true, 0, "MECARD:", 0, 7)) {
            for (String token : value.substring(7).split(";")) {
                int colon = token.indexOf(':');
                if (colon < 0) continue;
                String key = token.substring(0, colon).trim().toUpperCase(Locale.ROOT);
                String data = token.substring(colon + 1).trim();
                if ("N".equals(key)) name = data.replace(',', ' ');
                else if ("TEL".equals(key) && phone.isEmpty()) phone = data;
                else if ("EMAIL".equals(key) && email.isEmpty()) email = data;
                else if ("ORG".equals(key)) company = data;
                else if ("ADR".equals(key)) address = data;
            }
        } else {
            for (String line : value.replace("\r", "").split("\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).toUpperCase(Locale.ROOT);
                String data = unescapeVcard(line.substring(colon + 1));
                if (key.startsWith("FN")) name = data;
                else if (key.startsWith("TEL") && phone.isEmpty()) phone = data;
                else if (key.startsWith("EMAIL") && email.isEmpty()) email = data;
                else if (key.startsWith("ORG")) company = data;
                else if (key.startsWith("ADR") && address.isEmpty()) address = data.replace(";", " ").trim();
            }
        }
        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
        if (!name.isEmpty()) intent.putExtra(ContactsContract.Intents.Insert.NAME, name);
        if (!phone.isEmpty()) intent.putExtra(ContactsContract.Intents.Insert.PHONE, phone);
        if (!email.isEmpty()) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, email);
        if (!company.isEmpty()) intent.putExtra(ContactsContract.Intents.Insert.COMPANY, company);
        if (!address.isEmpty()) intent.putExtra(ContactsContract.Intents.Insert.POSTAL, address);
        return intent;
    }

    private static String unescapeVcard(String value) {
        return value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    private static Intent createEventIntent(String value) {
        String title = "";
        String location = "";
        String start = "";
        String end = "";
        for (String line : value.replace("\r", "").split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).toUpperCase(Locale.ROOT);
            String data = line.substring(colon + 1).trim();
            if (key.startsWith("SUMMARY")) title = data;
            else if (key.startsWith("LOCATION")) location = data;
            else if (key.startsWith("DTSTART")) start = data;
            else if (key.startsWith("DTEND")) end = data;
        }
        long begin = parseCalendarDate(start);
        long finish = parseCalendarDate(end);
        Intent intent = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI);
        if (begin > 0) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin);
        if (finish > 0) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, finish);
        if (start.matches("^[0-9]{8}$") && end.matches("^[0-9]{8}$")) {
            intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true);
        }
        if (!title.isEmpty()) intent.putExtra(CalendarContract.Events.TITLE, title);
        if (!location.isEmpty()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, location);
        return intent;
    }

    private static long parseCalendarDate(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        String[] patterns = {"yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss", "yyyyMMdd"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
                format.setLenient(false);
                if (pattern.endsWith("'Z'")) format.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date date = format.parse(value);
                if (date != null) return date.getTime();
            } catch (ParseException ignored) { }
        }
        return 0;
    }
}
