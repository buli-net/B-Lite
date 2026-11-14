package wallet.qr;

import android.net.Uri;
import android.text.TextUtils;

import java.util.Map;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/** Builds standard payloads used by common QR reader applications. */
final class QrPayloadBuilder {
    static final String TYPE_TEXT = "TEXT";
    static final String TYPE_URL = "URL";
    static final String TYPE_WIFI = "WIFI";
    static final String TYPE_PHONE = "PHONE";
    static final String TYPE_CONTACT = "CONTACT";
    static final String TYPE_LOCATION = "LOCATION";
    static final String TYPE_SMS = "SMS";
    static final String TYPE_EMAIL = "EMAIL";
    static final String TYPE_EVENT = "EVENT";
    static final String TYPE_BITCOIN = "BITCOIN";

    private static final Pattern EVENT_DATE = Pattern.compile("^[0-9]{8}T[0-9]{6}Z?$|^[0-9]{8}$");
    private static final Pattern BTC_AMOUNT = Pattern.compile("^[0-9]+(?:\\.[0-9]{1,8})?$");

    private QrPayloadBuilder() { }

    static String build(String type, Map<String, String> values) {
        switch (type) {
            case TYPE_TEXT:
                return required(values, "text");
            case TYPE_URL:
                return buildUrl(required(values, "url"));
            case TYPE_WIFI:
                return buildWifi(required(values, "ssid"), value(values, "password"),
                        value(values, "security"), Boolean.parseBoolean(value(values, "hidden")));
            case TYPE_PHONE:
                return "tel:" + Uri.encode(required(values, "phone"), "+0123456789*#-().");
            case TYPE_CONTACT:
                return buildContact(values);
            case TYPE_LOCATION:
                return buildLocation(values);
            case TYPE_SMS:
                return buildSms(values);
            case TYPE_EMAIL:
                return buildEmail(values);
            case TYPE_EVENT:
                return buildEvent(values);
            case TYPE_BITCOIN:
                return buildBitcoin(values);
            default:
                throw new IllegalArgumentException("Unsupported content type");
        }
    }

    private static String buildUrl(String raw) {
        String url = raw.trim();
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*$")) url = "https://" + url;
        Uri parsed = Uri.parse(url);
        String scheme = parsed.getScheme();
        if (TextUtils.isEmpty(parsed.getHost()) || scheme == null
                || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Invalid URL");
        }
        return url;
    }

    private static String buildWifi(String ssid, String password, String security, boolean hidden) {
        String mode = TextUtils.isEmpty(security) ? "WPA" : security.toUpperCase(Locale.ROOT);
        if (!("WPA".equals(mode) || "WEP".equals(mode) || "nopass".equalsIgnoreCase(mode))) {
            throw new IllegalArgumentException("Invalid Wi-Fi security type");
        }
        if (!"nopass".equalsIgnoreCase(mode) && TextUtils.isEmpty(password)) {
            throw new IllegalArgumentException("Wi-Fi password is required");
        }
        return "WIFI:T:" + mode + ";S:" + wifiEscape(ssid) + ";P:"
                + wifiEscape(password) + ";H:" + (hidden ? "true" : "false") + ";;";
    }

    private static String wifiEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace(";", "\\;")
                .replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"");
    }

    private static String buildContact(Map<String, String> values) {
        String name = value(values, "name").trim();
        String phone = value(values, "phone").trim();
        String email = value(values, "email").trim();
        String company = value(values, "company").trim();
        String address = value(values, "address").trim();
        if (name.isEmpty() && phone.isEmpty() && email.isEmpty()) {
            throw new IllegalArgumentException("Enter at least a name, phone, or email");
        }
        if (name.isEmpty()) name = !email.isEmpty() ? email : phone;
        String newline = "\r\n";
        StringBuilder out = new StringBuilder("BEGIN:VCARD").append(newline)
                .append("VERSION:3.0").append(newline)
                .append("FN:").append(vcardEscape(name)).append(newline);
        appendVcard(out, "TEL;TYPE=CELL:", phone, newline);
        appendVcard(out, "EMAIL:", email, newline);
        appendVcard(out, "ORG:", company, newline);
        if (!TextUtils.isEmpty(address)) {
            out.append("ADR;TYPE=HOME:;;").append(vcardEscape(address)).append(";;;;").append(newline);
        }
        return out.append("END:VCARD").toString();
    }

    private static void appendVcard(StringBuilder out, String prefix, String value, String newline) {
        if (!TextUtils.isEmpty(value)) out.append(prefix).append(vcardEscape(value)).append(newline);
    }

    private static String vcardEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    private static String buildLocation(Map<String, String> values) {
        String latitude = required(values, "latitude");
        String longitude = required(values, "longitude");
        double lat = Double.parseDouble(latitude);
        double lon = Double.parseDouble(longitude);
        if (Double.isNaN(lat) || Double.isInfinite(lat) || lat < -90 || lat > 90
                || Double.isNaN(lon) || Double.isInfinite(lon) || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Invalid coordinates");
        }
        String coordinates = latitude.trim() + "," + longitude.trim();
        String label = value(values, "location_label");
        return TextUtils.isEmpty(label) ? "geo:" + coordinates
                : "geo:" + coordinates + "?q=" + Uri.encode(coordinates + "(" + label + ")");
    }

    private static String buildSms(Map<String, String> values) {
        String number = required(values, "sms_number").trim();
        String body = value(values, "sms_body");
        return "SMSTO:" + number + (TextUtils.isEmpty(body) ? "" : ":" + body);
    }

    private static String buildEmail(Map<String, String> values) {
        String to = required(values, "email_to").trim();
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(to).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
        StringBuilder uri = new StringBuilder("mailto:").append(Uri.encode(to, "@.+-_"));
        String subject = value(values, "email_subject");
        String body = value(values, "email_body");
        StringBuilder query = new StringBuilder();
        appendQuery(query, "subject", subject);
        appendQuery(query, "body", body);
        if (query.length() > 0) uri.append('?').append(query);
        return uri.toString();
    }

    private static String buildEvent(Map<String, String> values) {
        String title = required(values, "event_title");
        String start = required(values, "event_start").trim();
        String end = required(values, "event_end").trim();
        long startTime = parseEventDate(start);
        long endTime = parseEventDate(end);
        if (!EVENT_DATE.matcher(start).matches() || !EVENT_DATE.matcher(end).matches()
                || startTime == Long.MIN_VALUE || endTime == Long.MIN_VALUE || endTime <= startTime) {
            throw new IllegalArgumentException("Use valid dates and an end later than the start");
        }
        java.text.SimpleDateFormat timestampFormat =
                new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        timestampFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String timestamp = timestampFormat.format(new java.util.Date());
        String uid = java.util.UUID.randomUUID().toString() + "@generated.local";
        String newline = "\r\n";
        StringBuilder out = new StringBuilder("BEGIN:VCALENDAR").append(newline)
                .append("VERSION:2.0").append(newline)
                .append("PRODID:-//Generated Calendar Entry//EN").append(newline)
                .append("CALSCALE:GREGORIAN").append(newline)
                .append("BEGIN:VEVENT").append(newline)
                .append("UID:").append(uid).append(newline)
                .append("DTSTAMP:").append(timestamp).append(newline)
                .append("SUMMARY:").append(icsEscape(title)).append(newline);
        String location = value(values, "event_location");
        if (!TextUtils.isEmpty(location)) out.append("LOCATION:").append(icsEscape(location)).append(newline);
        out.append("DTSTART:").append(start).append(newline)
                .append("DTEND:").append(end).append(newline)
                .append("END:VEVENT").append(newline).append("END:VCALENDAR");
        return out.toString();
    }

    private static String icsEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\")
                .replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    private static String buildBitcoin(Map<String, String> values) {
        String address = required(values, "btc_address").trim();
        if (!address.matches("(?i)^(bc1|tb1|bcrt1)[a-z0-9]{20,90}$|^[13mn2][a-km-zA-HJ-NP-Z1-9]{25,34}$")) {
            throw new IllegalArgumentException("Invalid Bitcoin address");
        }
        String amount = value(values, "btc_amount").trim();
        if (!TextUtils.isEmpty(amount)) {
            BigDecimal numericAmount;
            try { numericAmount = new BigDecimal(amount); }
            catch (NumberFormatException exception) { throw new IllegalArgumentException("Invalid Bitcoin amount"); }
            if (!BTC_AMOUNT.matcher(amount).matches() || numericAmount.signum() <= 0
                    || numericAmount.compareTo(new BigDecimal("21000000")) > 0) {
                throw new IllegalArgumentException("Invalid Bitcoin amount");
            }
        }
        String label = value(values, "btc_label");
        String message = value(values, "btc_message");
        if (!TextUtils.isEmpty(amount) || !TextUtils.isEmpty(label) || !TextUtils.isEmpty(message)) {
            StringBuilder query = new StringBuilder();
            appendQuery(query, "amount", amount);
            appendQuery(query, "label", label);
            appendQuery(query, "message", message);
            return "bitcoin:" + address + "?" + query;
        }
        return "bitcoin:" + address;
    }


    private static long parseEventDate(String value) {
        String pattern = value.endsWith("Z") ? "yyyyMMdd'T'HHmmss'Z'"
                : (value.contains("T") ? "yyyyMMdd'T'HHmmss" : "yyyyMMdd");
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(pattern, Locale.ROOT);
        format.setLenient(false);
        if (value.endsWith("Z")) format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        try {
            java.util.Date parsed = format.parse(value);
            return parsed == null ? Long.MIN_VALUE : parsed.getTime();
        } catch (java.text.ParseException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static void appendQuery(StringBuilder out, String key, String value) {
        if (TextUtils.isEmpty(value)) return;
        if (out.length() > 0) out.append('&');
        out.append(Uri.encode(key)).append('=').append(Uri.encode(value));
    }

    private static String required(Map<String, String> values, String key) {
        String value = value(values, key).trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Required field is empty");
        return value;
    }

    private static String value(Map<String, String> values, String key) {
        String value = values == null ? null : values.get(key);
        return value == null ? "" : value;
    }
}
