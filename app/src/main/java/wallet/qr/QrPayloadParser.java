package wallet.qr;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.google.zxing.client.result.AddressBookParsedResult;
import com.google.zxing.client.result.CalendarParsedResult;
import com.google.zxing.client.result.EmailAddressParsedResult;
import com.google.zxing.client.result.GeoParsedResult;
import com.google.zxing.client.result.ParsedResult;
import com.google.zxing.client.result.SMSParsedResult;
import com.google.zxing.client.result.TelParsedResult;
import com.google.zxing.client.result.URIParsedResult;
import com.google.zxing.client.result.WifiParsedResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import wallet.Constants;
import wallet.main.R;

/** Converts supported QR payloads into the same named fields used by the generator form. */
final class QrPayloadParser {
    static final class Field {
        final int label;
        final String value;
        final int valueResource;

        Field(int label, String value) {
            this.label = label;
            this.value = value == null ? "" : value;
            this.valueResource = 0;
        }

        Field(int label, int valueResource) {
            this.label = label;
            this.value = "";
            this.valueResource = valueResource;
        }
    }

    static final class ParsedContent {
        final String type;
        final int typeLabel;
        final Map<String, String> values;
        final List<Field> displayFields;

        ParsedContent(String type, int typeLabel, Map<String, String> values,
                      List<Field> displayFields) {
            this.type = type;
            this.typeLabel = typeLabel;
            this.values = values;
            this.displayFields = displayFields;
        }
    }

    private QrPayloadParser() { }

    static ParsedContent parse(Context context, String raw) {
        String content = raw == null ? "" : raw;
        QrContentClassifier.Detection detection = QrContentClassifier.detect(content);
        Map<String, String> values = new LinkedHashMap<>();
        List<Field> display = new ArrayList<>();
        ParsedResult parsed = QrContentClassifier.parse(content.trim());

        switch (detection.type) {
            case QrPayloadBuilder.TYPE_URL:
                put(values, "url", parsed instanceof URIParsedResult
                        ? ((URIParsedResult) parsed).getURI() : content.trim());
                add(display, R.string.qr_tools_field_url, values.get("url"));
                break;
            case QrPayloadBuilder.TYPE_WIFI:
                parseWifi(parsed, values, display);
                break;
            case QrPayloadBuilder.TYPE_PHONE:
                String phone = parsed instanceof TelParsedResult
                        ? ((TelParsedResult) parsed).getNumber() : stripPrefix(content.trim(), "tel:");
                put(values, "phone", phone);
                add(display, R.string.qr_tools_field_phone, phone);
                break;
            case QrPayloadBuilder.TYPE_CONTACT:
                parseContact(parsed, content, values, display);
                break;
            case QrPayloadBuilder.TYPE_LOCATION:
                parseLocation(parsed, content, values, display);
                break;
            case QrPayloadBuilder.TYPE_SMS:
                parseSms(parsed, content, values, display);
                break;
            case QrPayloadBuilder.TYPE_EMAIL:
                parseEmail(parsed, content, values, display);
                break;
            case QrPayloadBuilder.TYPE_EVENT:
                parseEvent(parsed, content, values, display);
                break;
            case QrPayloadBuilder.TYPE_BITCOIN:
                parseBitcoin(content, values, display);
                break;
            case QrPayloadBuilder.TYPE_TEXT:
            default:
                put(values, "text", content);
                break;
        }
        return new ParsedContent(detection.type, detection.typeLabel, values, display);
    }

    private static void parseWifi(ParsedResult parsed, Map<String, String> values, List<Field> display) {
        if (parsed instanceof WifiParsedResult) {
            WifiParsedResult wifi = (WifiParsedResult) parsed;
            put(values, "ssid", wifi.getSsid());
            put(values, "password", wifi.getPassword());
            put(values, "security", normalizeWifiSecurity(wifi.getNetworkEncryption()));
            put(values, "hidden", Boolean.toString(wifi.isHidden()));
        }
        add(display, R.string.qr_tools_field_ssid, values.get("ssid"));
        String security = values.get("security");
        if (!TextUtils.isEmpty(security)) {
            int securityLabel = QrPayloadBuilder.WIFI_SECURITY_WEP.equals(security)
                    ? R.string.qr_tools_wifi_security_name_wep
                    : (QrPayloadBuilder.WIFI_SECURITY_NONE.equalsIgnoreCase(security)
                    ? R.string.qr_tools_wifi_security_name_none
                    : R.string.qr_tools_wifi_security_name_wpa);
            display.add(new Field(R.string.qr_tools_field_security, securityLabel));
        }
        add(display, R.string.qr_tools_field_password, values.get("password"));
        String hidden = values.get("hidden");
        if (!TextUtils.isEmpty(hidden)) {
            display.add(new Field(R.string.qr_tools_field_wifi_hidden,
                    Boolean.parseBoolean(hidden) ? R.string.qr_tools_value_yes : R.string.qr_tools_value_no));
        }
    }

    private static void parseContact(ParsedResult parsed, String raw, Map<String, String> values,
                                     List<Field> display) {
        String phoneDisplay;
        String emailDisplay;
        String addressDisplay;
        if (parsed instanceof AddressBookParsedResult) {
            AddressBookParsedResult contact = (AddressBookParsedResult) parsed;
            String[] phones = contact.getPhoneNumbers();
            String[] emails = contact.getEmails();
            String[] addresses = contact.getAddresses();
            put(values, "name", first(contact.getNames()));
            put(values, "phone", first(phones));
            put(values, "email", first(emails));
            put(values, "company", contact.getOrg());
            put(values, "address", first(addresses));
            phoneDisplay = join(phones);
            emailDisplay = join(emails);
            addressDisplay = join(addresses);
        } else {
            put(values, "name", vCardProperty(raw, "FN"));
            put(values, "phone", vCardProperty(raw, "TEL"));
            put(values, "email", vCardProperty(raw, "EMAIL"));
            put(values, "company", vCardProperty(raw, "ORG"));
            put(values, "address", vCardProperty(raw, "ADR"));
            phoneDisplay = values.get("phone");
            emailDisplay = values.get("email");
            addressDisplay = values.get("address");
        }
        putIfEmpty(values, "name", vCardProperty(raw, "FN"));
        putIfEmpty(values, "phone", vCardProperty(raw, "TEL"));
        putIfEmpty(values, "email", vCardProperty(raw, "EMAIL"));
        putIfEmpty(values, "company", vCardProperty(raw, "ORG"));
        putIfEmpty(values, "address", vCardProperty(raw, "ADR"));
        if (TextUtils.isEmpty(phoneDisplay)) phoneDisplay = values.get("phone");
        if (TextUtils.isEmpty(emailDisplay)) emailDisplay = values.get("email");
        if (TextUtils.isEmpty(addressDisplay)) addressDisplay = values.get("address");
        add(display, R.string.qr_tools_field_name, values.get("name"));
        add(display, R.string.qr_tools_field_contact_phone, phoneDisplay);
        add(display, R.string.qr_tools_field_contact_email, emailDisplay);
        add(display, R.string.qr_tools_field_company, values.get("company"));
        add(display, R.string.qr_tools_field_address, addressDisplay);
    }

    private static void parseLocation(ParsedResult parsed, String raw, Map<String, String> values,
                                      List<Field> display) {
        if (parsed instanceof GeoParsedResult) {
            GeoParsedResult geo = (GeoParsedResult) parsed;
            put(values, "latitude", Double.toString(geo.getLatitude()));
            put(values, "longitude", Double.toString(geo.getLongitude()));
            put(values, "location_label", locationLabel(queryParameter(raw, "q")));
        } else {
            Uri uri = Uri.parse(raw);
            String coordinates = uri.getSchemeSpecificPart();
            int queryIndex = coordinates == null ? -1 : coordinates.indexOf('?');
            if (queryIndex >= 0) coordinates = coordinates.substring(0, queryIndex);
            if (coordinates != null) {
                String[] parts = coordinates.split(",", 3);
                if (parts.length >= 2) {
                    put(values, "latitude", parts[0]);
                    put(values, "longitude", parts[1]);
                }
            }
            put(values, "location_label", locationLabel(queryParameter(raw, "q")));
        }
        add(display, R.string.qr_tools_field_latitude, values.get("latitude"));
        add(display, R.string.qr_tools_field_longitude, values.get("longitude"));
        add(display, R.string.qr_tools_field_location_label, values.get("location_label"));
    }

    private static void parseSms(ParsedResult parsed, String raw, Map<String, String> values,
                                 List<Field> display) {
        if (parsed instanceof SMSParsedResult) {
            SMSParsedResult sms = (SMSParsedResult) parsed;
            put(values, "sms_number", first(sms.getNumbers()));
            put(values, "sms_body", sms.getBody());
        } else {
            String value = raw;
            int colon = value.indexOf(':');
            if (colon >= 0) value = value.substring(colon + 1);
            int separator = value.indexOf(':');
            put(values, "sms_number", separator >= 0 ? value.substring(0, separator) : value);
            put(values, "sms_body", separator >= 0 ? value.substring(separator + 1) : "");
        }
        add(display, R.string.qr_tools_field_sms_number, values.get("sms_number"));
        add(display, R.string.qr_tools_field_sms_body, values.get("sms_body"));
    }

    private static void parseEmail(ParsedResult parsed, String raw, Map<String, String> values,
                                   List<Field> display) {
        if (parsed instanceof EmailAddressParsedResult) {
            EmailAddressParsedResult email = (EmailAddressParsedResult) parsed;
            put(values, "email_to", first(email.getTos()));
            put(values, "email_subject", email.getSubject());
            put(values, "email_body", email.getBody());
        } else {
            String value = raw.trim();
            if (value.regionMatches(true, 0, "mailto:", 0, 7)) {
                Uri uri = Uri.parse(value);
                put(values, "email_to", uri.getSchemeSpecificPart().split("\\?", 2)[0]);
                put(values, "email_subject", queryParameter(value, "subject"));
                put(values, "email_body", queryParameter(value, "body"));
            } else {
                put(values, "email_to", value);
            }
        }
        add(display, R.string.qr_tools_field_email_to, values.get("email_to"));
        add(display, R.string.qr_tools_field_email_subject, values.get("email_subject"));
        add(display, R.string.qr_tools_field_email_body, values.get("email_body"));
    }

    private static void parseEvent(ParsedResult parsed, String raw, Map<String, String> values,
                                   List<Field> display) {
        if (parsed instanceof CalendarParsedResult) {
            CalendarParsedResult event = (CalendarParsedResult) parsed;
            put(values, "event_title", event.getSummary());
            put(values, "event_location", event.getLocation());
        }
        String rawTitle = icsProperty(raw, "SUMMARY");
        String rawLocation = icsProperty(raw, "LOCATION");
        String rawStart = icsProperty(raw, "DTSTART");
        String rawEnd = icsProperty(raw, "DTEND");
        if (!TextUtils.isEmpty(rawTitle)) put(values, "event_title", rawTitle);
        if (!TextUtils.isEmpty(rawLocation)) put(values, "event_location", rawLocation);
        if (!TextUtils.isEmpty(rawStart)) put(values, "event_start", rawStart);
        if (!TextUtils.isEmpty(rawEnd)) put(values, "event_end", rawEnd);
        add(display, R.string.qr_tools_field_event_title, values.get("event_title"));
        add(display, R.string.qr_tools_field_event_location, values.get("event_location"));
        add(display, R.string.qr_tools_field_event_start, values.get("event_start"));
        add(display, R.string.qr_tools_field_event_end, values.get("event_end"));
    }

    private static void parseBitcoin(String raw, Map<String, String> values, List<Field> display) {
        String value = raw.trim();
        String address = value;
        if (value.regionMatches(true, 0, Constants.BITCOIN_URI_SCHEME, 0,
                Constants.BITCOIN_URI_SCHEME.length())) {
            address = value.substring(Constants.BITCOIN_URI_SCHEME.length());
            int query = address.indexOf('?');
            if (query >= 0) address = address.substring(0, query);
            put(values, "btc_amount", queryParameter(value, "amount"));
            put(values, "btc_label", queryParameter(value, "label"));
            put(values, "btc_message", queryParameter(value, "message"));
        }
        put(values, "btc_address", address);
        add(display, R.string.qr_tools_field_btc_address, values.get("btc_address"));
        add(display, R.string.qr_tools_field_btc_amount, values.get("btc_amount"));
        add(display, R.string.qr_tools_field_btc_label, values.get("btc_label"));
        add(display, R.string.qr_tools_field_btc_message, values.get("btc_message"));
    }

    private static String normalizeWifiSecurity(String security) {
        if (TextUtils.isEmpty(security)) return "";
        if ("nopass".equalsIgnoreCase(security)) return QrPayloadBuilder.WIFI_SECURITY_NONE;
        return security.toUpperCase(Locale.ROOT);
    }

    private static String queryParameter(String raw, String key) {
        if (TextUtils.isEmpty(raw)) return "";
        int queryStart = raw.indexOf('?');
        if (queryStart < 0 || queryStart >= raw.length() - 1) return "";
        String query = raw.substring(queryStart + 1);
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator >= 0 ? pair.substring(0, separator) : pair;
            if (key.equalsIgnoreCase(Uri.decode(name))) {
                return separator >= 0 ? Uri.decode(pair.substring(separator + 1)) : "";
            }
        }
        return "";
    }

    private static String locationLabel(String query) {
        if (TextUtils.isEmpty(query)) return "";
        int start = query.lastIndexOf('(');
        int end = query.lastIndexOf(')');
        return start >= 0 && end > start ? query.substring(start + 1, end) : "";
    }

    private static String vCardProperty(String raw, String key) {
        if (raw == null) return "";
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String property = line.substring(0, colon).toUpperCase(Locale.ROOT);
            int semicolon = property.indexOf(';');
            if (semicolon >= 0) property = property.substring(0, semicolon);
            int dot = property.lastIndexOf('.');
            if (dot >= 0) property = property.substring(dot + 1);
            if (key.equals(property)) return unescapeVCard(line.substring(colon + 1));
        }
        return "";
    }

    private static String icsProperty(String raw, String key) {
        if (raw == null) return "";
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String property = line.substring(0, colon).toUpperCase(Locale.ROOT);
            int semicolon = property.indexOf(';');
            if (semicolon >= 0) property = property.substring(0, semicolon);
            if (key.equals(property)) return unescapeIcs(line.substring(colon + 1));
        }
        return "";
    }

    private static String unescapeVCard(String value) {
        return value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    private static String unescapeIcs(String value) {
        return value.replace("\\n", "\n").replace("\\N", "\n")
                .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\");
    }

    private static String stripPrefix(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length())
                ? value.substring(prefix.length()) : value;
    }

    private static String first(String[] values) {
        if (values == null) return "";
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    private static String join(String[] values) {
        if (values == null) return "";
        List<String> nonEmpty = new ArrayList<>();
        for (String value : values) if (!TextUtils.isEmpty(value)) nonEmpty.add(value);
        return TextUtils.join("\n", nonEmpty);
    }

    private static void put(Map<String, String> values, String key, String value) {
        values.put(key, value == null ? "" : value);
    }

    private static void putIfEmpty(Map<String, String> values, String key, String value) {
        if (TextUtils.isEmpty(values.get(key))) put(values, key, value);
    }

    private static void add(List<Field> fields, int label, String value) {
        if (!TextUtils.isEmpty(value)) fields.add(new Field(label, value));
    }

}
