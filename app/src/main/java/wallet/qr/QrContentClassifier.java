package wallet.qr;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.TextUtils;

import wallet.Constants;
import wallet.main.R;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.Result;
import com.google.zxing.client.result.AddressBookParsedResult;
import com.google.zxing.client.result.CalendarParsedResult;
import com.google.zxing.client.result.EmailAddressParsedResult;
import com.google.zxing.client.result.GeoParsedResult;
import com.google.zxing.client.result.ParsedResult;
import com.google.zxing.client.result.ParsedResultType;
import com.google.zxing.client.result.ResultParser;
import com.google.zxing.client.result.SMSParsedResult;
import com.google.zxing.client.result.TelParsedResult;
import com.google.zxing.client.result.URIParsedResult;


/** Uses ZXing's standard payload parsers and keeps app-specific action routing explicit. */
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
        if (value.regionMatches(true, 0, Constants.BITCOIN_URI_SCHEME, 0,
                Constants.BITCOIN_URI_SCHEME.length())) {
            return detection(QrPayloadBuilder.TYPE_BITCOIN, R.string.qr_tools_type_bitcoin,
                    R.string.qr_tools_open_bitcoin);
        }

        ParsedResult parsed = parse(value);
        if (parsed != null) {
            ParsedResultType type = parsed.getType();
            if (type == ParsedResultType.WIFI) {
                return detection(QrPayloadBuilder.TYPE_WIFI, R.string.qr_tools_type_wifi,
                        R.string.qr_tools_open_wifi);
            }
            if (type == ParsedResultType.ADDRESSBOOK) {
                return detection(QrPayloadBuilder.TYPE_CONTACT, R.string.qr_tools_type_contact,
                        R.string.qr_tools_open_contact);
            }
            if (type == ParsedResultType.CALENDAR) {
                return detection(QrPayloadBuilder.TYPE_EVENT, R.string.qr_tools_type_event,
                        R.string.qr_tools_open_event);
            }
            if (type == ParsedResultType.EMAIL_ADDRESS) {
                return detection(QrPayloadBuilder.TYPE_EMAIL, R.string.qr_tools_type_email,
                        R.string.qr_tools_open_email);
            }
            if (type == ParsedResultType.SMS) {
                return detection(QrPayloadBuilder.TYPE_SMS, R.string.qr_tools_type_sms,
                        R.string.qr_tools_open_sms);
            }
            if (type == ParsedResultType.TEL) {
                return detection(QrPayloadBuilder.TYPE_PHONE, R.string.qr_tools_type_phone,
                        R.string.qr_tools_open_phone);
            }
            if (type == ParsedResultType.GEO) {
                return detection(QrPayloadBuilder.TYPE_LOCATION, R.string.qr_tools_type_location,
                        R.string.qr_tools_open_location);
            }
            if (type == ParsedResultType.URI && isWebAddress(((URIParsedResult) parsed).getURI())) {
                return detection(QrPayloadBuilder.TYPE_URL, R.string.qr_tools_type_url,
                        R.string.qr_tools_open_url);
            }
        }

        // Retain the app's simple bare-address and www. conveniences when a payload
        // is not one of ZXing's structured result types.
        if (android.util.Patterns.EMAIL_ADDRESS.matcher(value).matches()) {
            return detection(QrPayloadBuilder.TYPE_EMAIL, R.string.qr_tools_type_email,
                    R.string.qr_tools_open_email);
        }
        if (isWebAddress(value)) {
            return detection(QrPayloadBuilder.TYPE_URL, R.string.qr_tools_type_url,
                    R.string.qr_tools_open_url);
        }
        return new Detection(QrPayloadBuilder.TYPE_TEXT, R.string.qr_tools_type_text, 0, false);
    }

    private static Detection detection(String type, int typeLabel, int actionLabel) {
        return new Detection(type, typeLabel, actionLabel, true);
    }

    static ParsedResult parse(String value) {
        if (TextUtils.isEmpty(value)) return null;
        try {
            return ResultParser.parseResult(new Result(normalizeStandardPrefix(value),
                    null, null, BarcodeFormat.QR_CODE));
        } catch (RuntimeException ignored) {
            return null;
        }
    }


    private static String normalizeStandardPrefix(String value) {
        String[] prefixes = {"WIFI:", "MECARD:", "BEGIN:VCARD", "BEGIN:VCALENDAR",
                "BEGIN:VEVENT", "MATMSG:", "mailto:", "SMSTO:", "MMSTO:",
                "sms:", "mms:", "tel:", "geo:"};
        for (String prefix : prefixes) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length())) {
                return prefix + value.substring(prefix.length());
            }
        }
        return value;
    }

    private static boolean isWebAddress(String value) {
        return value != null && value.matches("(?i)^(https?://|www\\.).+");
    }

    static Intent createIntent(Context context, String raw) {
        String value = raw == null ? "" : raw.trim();
        Detection detection = detect(value);
        ParsedResult parsed = parse(value);
        switch (detection.type) {
            case QrPayloadBuilder.TYPE_URL: {
                String url = parsed instanceof URIParsedResult
                        ? ((URIParsedResult) parsed).getURI() : value;
                return new Intent(Intent.ACTION_VIEW, Uri.parse(hasHttpScheme(url)
                        ? url : "https://" + url));
            }
            case QrPayloadBuilder.TYPE_PHONE: {
                String telUri = parsed instanceof TelParsedResult
                        ? ((TelParsedResult) parsed).getTelURI() : value;
                return new Intent(Intent.ACTION_DIAL, Uri.parse(telUri));
            }
            case QrPayloadBuilder.TYPE_SMS:
                return createSmsIntent(parsed, value);
            case QrPayloadBuilder.TYPE_EMAIL:
                return createEmailIntent(parsed, value);
            case QrPayloadBuilder.TYPE_CONTACT:
                return createContactIntent(parsed);
            case QrPayloadBuilder.TYPE_LOCATION: {
                String geoUri = parsed instanceof GeoParsedResult
                        ? ((GeoParsedResult) parsed).getGeoURI() : value;
                return new Intent(Intent.ACTION_VIEW, Uri.parse(geoUri));
            }
            case QrPayloadBuilder.TYPE_EVENT:
                return createEventIntent(parsed);
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

    private static String hasFirst(String[] values) {
        if (values == null) return "";
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
    }

    private static Intent createSmsIntent(ParsedResult parsed, String raw) {
        if (parsed instanceof SMSParsedResult) {
            SMSParsedResult sms = (SMSParsedResult) parsed;
            String number = hasFirst(sms.getNumbers());
            Intent intent = new Intent(Intent.ACTION_SENDTO,
                    Uri.parse("smsto:" + Uri.encode(number, "+0123456789*#-().")));
            if (!TextUtils.isEmpty(sms.getBody())) intent.putExtra("sms_body", sms.getBody());
            return intent;
        }
        // ZXing rejects malformed structured payloads; keep them as a safe dial-to-SMS
        // action rather than attempting to interpret their body or query parameters.
        String number = raw;
        int colon = number.indexOf(':');
        if (colon >= 0) number = number.substring(colon + 1);
        int separator = number.indexOf(':');
        if (separator >= 0) number = number.substring(0, separator);
        int query = number.indexOf('?');
        if (query >= 0) number = number.substring(0, query);
        return new Intent(Intent.ACTION_SENDTO,
                Uri.parse("smsto:" + Uri.encode(number, "+0123456789*#-().")));
    }

    private static Intent createEmailIntent(ParsedResult parsed, String raw) {
        String to = "";
        String subject = "";
        String body = "";
        if (parsed instanceof EmailAddressParsedResult) {
            EmailAddressParsedResult email = (EmailAddressParsedResult) parsed;
            to = hasFirst(email.getTos());
            subject = email.getSubject() == null ? "" : email.getSubject();
            body = email.getBody() == null ? "" : email.getBody();
        } else if (android.util.Patterns.EMAIL_ADDRESS.matcher(raw).matches()) {
            to = raw;
        } else if (raw.regionMatches(true, 0, "mailto:", 0, 7)) {
            Uri source = Uri.parse(raw);
            to = source.getSchemeSpecificPart();
            int query = to.indexOf('?');
            if (query >= 0) to = to.substring(0, query);
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

    private static Intent createContactIntent(ParsedResult parsed) {
        Intent intent = new Intent(Intent.ACTION_INSERT);
        intent.setType(ContactsContract.Contacts.CONTENT_TYPE);
        if (!(parsed instanceof AddressBookParsedResult)) return intent;
        AddressBookParsedResult contact = (AddressBookParsedResult) parsed;
        String name = hasFirst(contact.getNames());
        String phone = hasFirst(contact.getPhoneNumbers());
        String email = hasFirst(contact.getEmails());
        String company = contact.getOrg() == null ? "" : contact.getOrg();
        String address = hasFirst(contact.getAddresses());
        if (!TextUtils.isEmpty(name)) intent.putExtra(ContactsContract.Intents.Insert.NAME, name);
        if (!TextUtils.isEmpty(phone)) intent.putExtra(ContactsContract.Intents.Insert.PHONE, phone);
        if (!TextUtils.isEmpty(email)) intent.putExtra(ContactsContract.Intents.Insert.EMAIL, email);
        if (!TextUtils.isEmpty(company)) intent.putExtra(ContactsContract.Intents.Insert.COMPANY, company);
        if (!TextUtils.isEmpty(address)) intent.putExtra(ContactsContract.Intents.Insert.POSTAL, address);
        return intent;
    }

    private static Intent createEventIntent(ParsedResult parsed) {
        Intent intent = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI);
        if (!(parsed instanceof CalendarParsedResult)) return intent;
        CalendarParsedResult event = (CalendarParsedResult) parsed;
        long start = event.getStartTimestamp();
        long end = event.getEndTimestamp();
        if (start >= 0L) intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start);
        if (end >= 0L) intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end);
        if (event.isStartAllDay()) intent.putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true);
        if (!TextUtils.isEmpty(event.getSummary())) {
            intent.putExtra(CalendarContract.Events.TITLE, event.getSummary());
        }
        if (!TextUtils.isEmpty(event.getLocation())) {
            intent.putExtra(CalendarContract.Events.EVENT_LOCATION, event.getLocation());
        }
        return intent;
    }

    private static boolean hasHttpScheme(String value) {
        return value != null && (value.regionMatches(true, 0, "http://", 0, 7)
                || value.regionMatches(true, 0, "https://", 0, 8));
    }

    static String displayType(Context context, String raw) {
        Detection detection = detect(raw);
        return context.getString(detection.typeLabel);
    }
}
