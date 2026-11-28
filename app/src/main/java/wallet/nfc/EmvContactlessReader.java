package wallet.nfc;

import android.content.Context;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import wallet.main.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.SecureRandom;

/**
 * Read-only EMV contactless diagnostics.
 *
 * GPO starts application processing on the card. Call this class only after an explicit
 * user confirmation. The default report exposes only an allow-list. A separate, explicit
 * sensitive-data confirmation can expose raw TLV data returned by SELECT, GPO and READ RECORD.
 * This reader never sends VERIFY, INTERNAL AUTHENTICATE, GENERATE AC, or issuer-script commands.
 */
final class EmvContactlessReader {
    private static final byte[] PPSE_SELECT = new byte[] {
            0x00, (byte) 0xA4, 0x04, 0x00, 0x0E,
            0x32, 0x50, 0x41, 0x59, 0x2E, 0x53, 0x59, 0x53, 0x2E,
            0x44, 0x44, 0x46, 0x30, 0x31, 0x00
    };
    private static final int MAX_APPLICATIONS = 6;
    private static final int MAX_RECORDS = 32;
    private static final int MAX_RESPONSE_BYTES = 16384;
    // Keep an explicit full-data diagnostic bounded even on cards returning unusually large records.
    private static final int MAX_FULL_DATA_CHARS = 256 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    // Deliberately small allow-list for the default, privacy-preserving report.
    private static final int TAG_AIP = 0x82;
    private static final int TAG_AFL = 0x94;
    private static final int TAG_APP_LABEL = 0x50;
    private static final int TAG_APP_PREFERRED_NAME = 0x9F12;
    private static final int TAG_APP_PRIORITY = 0x87;
    private static final int TAG_LANGUAGE_PREFERENCE = 0x5F2D;
    private static final int TAG_APP_CURRENCY = 0x9F42;
    private static final int TAG_APP_CURRENCY_EXPONENT = 0x9F44;
    private static final int TAG_ISSUER_COUNTRY = 0x5F28;
    private static final int TAG_APP_VERSION = 0x9F08;

    private EmvContactlessReader() { }

    static Report read(Context context, Tag tag, boolean includeSensitiveData) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) return Report.failure(context.getString(R.string.nfc_reader_emv_error_no_isodep));
        boolean connected = false;
        byte[] ppseResponse = null;
        FullDataCapture fullData = includeSensitiveData ? new FullDataCapture() : null;
        try {
            isoDep.setTimeout(5000);
            isoDep.connect();
            connected = true;

            ppseResponse = exchange(isoDep, PPSE_SELECT);
            if (!isSuccess(ppseResponse)) {
                return Report.failure(context.getString(R.string.nfc_reader_emv_error_ppse_failed, sw(ppseResponse)));
            }

            List<Application> applications = parseApplications(ppseResponse);
            if (applications.isEmpty()) {
                return Report.failure(context.getString(R.string.nfc_reader_emv_error_no_aid));
            }

            if (fullData != null) {
                byte[] ppseBody = body(ppseResponse);
                List<Tlv> ppseTlvs = parseTlvs(ppseBody);
                captureFullResponse(fullData, "PPSE SELECT", ppseBody, ppseTlvs);
                wipeTlvs(ppseTlvs);
                wipe(ppseBody);
            }

            List<ApplicationResult> results = new ArrayList<>();
            int count = Math.min(MAX_APPLICATIONS, applications.size());
            for (int i = 0; i < count; i++) {
                Application app = applications.get(i);
                ApplicationResult result = inspectApplication(context, isoDep, app, fullData);
                results.add(result);
            }
            return new Report(results, applications.size() > MAX_APPLICATIONS,
                    fullData == null ? null : fullData.lines,
                    fullData != null && fullData.truncated,
                    includeSensitiveData);
        } catch (IOException | RuntimeException e) {
            return Report.failure(context.getString(R.string.nfc_reader_emv_error_session));
        } finally {
            wipe(ppseResponse);
            if (connected) {
                try { isoDep.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static ApplicationResult inspectApplication(Context context, IsoDep isoDep,
                                                         Application app, FullDataCapture fullData) {
        ApplicationResult result = new ApplicationResult(app.aid, app.label, app.priority);
        byte[] selectResponse = null;
        byte[] gpoResponse = null;
        byte[] selectBody = null;
        byte[] selectCommand = null;
        List<Tlv> selectTlvs = null;
        GpoData gpo = null;
        try {
            selectCommand = selectAid(app.aid);
            selectResponse = exchange(isoDep, selectCommand);
            wipe(selectCommand);
            selectCommand = null;
            result.selectStatus = sw(selectResponse);
            if (!isSuccess(selectResponse)) {
                result.message = context.getString(R.string.nfc_reader_emv_error_select_app);
                return result;
            }

            selectBody = body(selectResponse);
            selectTlvs = parseTlvs(selectBody);
            if (fullData != null) {
                captureFullResponse(fullData, "AID " + app.aid + " / SELECT FCI", selectBody, selectTlvs);
            }
            byte[] pdolList = findTagValue(selectTlvs, 0x9F38, 0);
            byte[] terminalData = pdolList == null ? new byte[0] : buildDdolData(pdolList);
            wipe(pdolList);
            wipe(selectBody);
            byte[] gpoCommand = buildGpoCommand(terminalData);
            try {
                gpoResponse = exchange(isoDep, gpoCommand);
            } finally {
                Arrays.fill(terminalData, (byte) 0);
                Arrays.fill(gpoCommand, (byte) 0);
            }
            result.gpoStatus = sw(gpoResponse);
            if (!isSuccess(gpoResponse)) {
                result.message = context.getString(R.string.nfc_reader_emv_error_gpo, result.gpoStatus);
                return result;
            }

            if (fullData != null) {
                byte[] gpoBody = body(gpoResponse);
                List<Tlv> gpoTlvs = parseTlvs(gpoBody);
                captureFullResponse(fullData, "AID " + app.aid + " / GPO", gpoBody, gpoTlvs);
                wipeTlvs(gpoTlvs);
                wipe(gpoBody);
            }

            gpo = parseGpo(gpoResponse);
            if (gpo.aip != null) result.fields.put(context.getString(R.string.nfc_reader_emv_tag_aip), Nfc.toHex(gpo.aip));
            if (gpo.afl != null) result.fields.put(context.getString(R.string.nfc_reader_emv_tag_afl), Nfc.toHex(gpo.afl));
            if (!app.priority.isEmpty()) result.fields.put(
                    context.getString(R.string.nfc_reader_emv_tag_app_priority), app.priority);
            addSafeFields(context, result.fields, selectTlvs);
            wipeTlvs(selectTlvs);
            selectTlvs = null;
            if (gpo.recordsAllowed && gpo.afl != null) {
                readRecords(context, isoDep, gpo.afl, result, fullData);
            } else {
                result.message = context.getString(R.string.nfc_reader_emv_error_afl);
            }
            return result;
        } catch (IOException | RuntimeException e) {
            result.message = context.getString(R.string.nfc_reader_emv_error_apdu);
            return result;
        } finally {
            wipe(selectCommand);
            wipe(selectBody);
            wipeTlvs(selectTlvs);
            if (gpo != null) {
                wipe(gpo.aip);
                wipe(gpo.afl);
            }
            wipe(selectResponse);
            wipe(gpoResponse);
        }
    }

    private static void readRecords(Context context, IsoDep isoDep, byte[] afl,
                                    ApplicationResult result, FullDataCapture fullData) throws IOException {
        if (afl.length == 0 || (afl.length % 4) != 0) {
            result.message = context.getString(R.string.nfc_reader_emv_error_afl_format);
            return;
        }
        int processed = 0;
        for (int offset = 0; offset < afl.length && processed < MAX_RECORDS; offset += 4) {
            int sfiByte = afl[offset] & 0xFF;
            int sfi = sfiByte >>> 3;
            int firstRecord = afl[offset + 1] & 0xFF;
            int lastRecord = afl[offset + 2] & 0xFF;
            int offlineAuthenticationRecords = afl[offset + 3] & 0xFF;
            // AFL low three bits are reserved. Offline-auth record count cannot exceed the range.
            if ((sfiByte & 0x07) != 0 || sfi < 1 || sfi > 30
                    || firstRecord < 1 || lastRecord < firstRecord || lastRecord > 0xFE
                    || offlineAuthenticationRecords > (lastRecord - firstRecord + 1)) {
                result.recordErrors++;
                continue;
            }
            for (int record = firstRecord; record <= lastRecord && processed < MAX_RECORDS; record++) {
                processed++;
                byte[] command = new byte[] {
                        0x00, (byte) 0xB2, (byte) record, (byte) ((sfi << 3) | 0x04), 0x00
                };
                byte[] response = exchange(isoDep, command);
                Arrays.fill(command, (byte) 0);
                result.recordStatusLines.add(context.getString(R.string.nfc_reader_emv_record_status,
                        sfi, record, sw(response)));
                if (isSuccess(response)) {
                    result.recordsRead++;
                    byte[] recordBody = body(response);
                    List<Tlv> tlvs = parseTlvs(recordBody);
                    addSafeFields(context, result.fields, tlvs);
                    if (fullData != null) {
                        captureFullResponse(fullData,
                                "AID " + result.aid + " / READ RECORD SFI " + sfi + " RECORD " + record,
                                recordBody, tlvs);
                    }
                    wipeTlvs(tlvs);
                    wipe(recordBody);
                } else {
                    result.recordErrors++;
                }
                wipe(response);
            }
        }
        if (processed >= MAX_RECORDS) result.limitReached = true;
        if (result.recordsRead == 0 && result.message == null) {
            result.message = context.getString(R.string.nfc_reader_emv_error_no_records);
        }
    }

    private static byte[] selectAid(String aidHex) {
        byte[] aid = fromHex(aidHex);
        byte[] command = new byte[6 + aid.length];
        command[0] = 0x00;
        command[1] = (byte) 0xA4;
        command[2] = 0x04;
        command[3] = 0x00;
        command[4] = (byte) aid.length;
        System.arraycopy(aid, 0, command, 5, aid.length);
        command[command.length - 1] = 0x00;
        wipe(aid);
        return command;
    }

    private static byte[] buildGpoCommand(byte[] pdolData) {
        byte[] payload = new byte[pdolData.length + 2];
        payload[0] = (byte) 0x83;
        payload[1] = (byte) pdolData.length;
        System.arraycopy(pdolData, 0, payload, 2, pdolData.length);
        byte[] command = new byte[payload.length + 6];
        command[0] = (byte) 0x80;
        command[1] = (byte) 0xA8;
        command[2] = 0x00;
        command[3] = 0x00;
        command[4] = (byte) payload.length;
        System.arraycopy(payload, 0, command, 5, payload.length);
        command[command.length - 1] = 0x00;
        wipe(payload);
        return command;
    }

    /** Builds PDOL data without a purchase amount or claimed terminal capabilities. */
    private static byte[] buildDdolData(byte[] dol) {
        if (dol == null || dol.length == 0) return new byte[0];
        int total = 0;
        int pos = 0;
        List<int[]> entries = new ArrayList<>();
        while (pos < dol.length) {
            int first = dol[pos++] & 0xFF;
            int tag = first;
            if ((first & 0x1F) == 0x1F) {
                int count = 0;
                int b;
                do {
                    if (pos >= dol.length || count++ >= 3) throw new IllegalArgumentException("Invalid PDOL tag");
                    b = dol[pos++] & 0xFF;
                    tag = (tag << 8) | b;
                } while ((b & 0x80) != 0);
            }
            if (pos >= dol.length) throw new IllegalArgumentException("Invalid PDOL length");
            int length = dol[pos++] & 0xFF;
            total += length;
            if (total > 240) throw new IllegalArgumentException("PDOL too large");
            entries.add(new int[] {tag, length});
        }
        byte[] data = new byte[total];
        int offset = 0;
        for (int[] entry : entries) {
            // 9F37 is an unpredictable number. Other terminal values stay neutral (zero),
            // including amount and TTQ, so the tool does not claim payment-terminal features.
            if (entry[0] == 0x9F37 && entry[1] > 0) {
                byte[] random = new byte[entry[1]];
                RANDOM.nextBytes(random);
                System.arraycopy(random, 0, data, offset, random.length);
                wipe(random);
            }
            offset += entry[1];
        }
        return data;
    }

    private static GpoData parseGpo(byte[] response) {
        GpoData data = new GpoData();
        byte[] gpoBody = body(response);
        List<Tlv> tlvs = parseTlvs(gpoBody);
        byte[] format1 = findTagValue(tlvs, 0x80, 0);
        if (format1 != null && format1.length >= 2) {
            data.aip = Arrays.copyOfRange(format1, 0, 2);
            if (format1.length > 2) data.afl = Arrays.copyOfRange(format1, 2, format1.length);
        } else {
            byte[] aip = findTagValue(tlvs, TAG_AIP, 0);
            byte[] afl = findTagValue(tlvs, TAG_AFL, 0);
            if (aip != null && aip.length == 2) data.aip = aip.clone();
            if (afl != null) data.afl = afl.clone();
            wipe(aip);
            wipe(afl);
        }
        data.recordsAllowed = data.afl != null && data.afl.length > 0 && data.afl.length % 4 == 0;
        wipe(format1);
        wipeTlvs(tlvs);
        wipe(gpoBody);
        return data;
    }

    private static void addSafeFields(Context context, Map<String, String> fields, List<Tlv> tlvs) {
        addSafeFields(context, fields, tlvs, 0);
    }

    private static void addSafeFields(Context context, Map<String, String> fields, List<Tlv> tlvs, int depth) {
        if (depth > 8) return;
        for (Tlv tlv : tlvs) {
            String label = safeTagLabel(context, tlv.tag);
            if (label != null && tlv.value.length > 0) {
                String value;
                if (tlv.tag == TAG_APP_LABEL || tlv.tag == TAG_APP_PREFERRED_NAME
                        || tlv.tag == TAG_LANGUAGE_PREFERENCE) {
                    value = safeAscii(tlv.value);
                } else {
                    value = Nfc.toHex(tlv.value);
                }
                if (value != null && !value.isEmpty()) fields.put(label, value);
            }
            if (tlv.constructed) {
                List<Tlv> nested = parseTlvs(tlv.value);
                addSafeFields(context, fields, nested, depth + 1);
                wipeTlvs(nested);
            }
        }
    }

    /**
     * Adds the exact TLV encodings returned by the card, plus nested tag annotations.
     * This method is only called after the user has explicitly enabled sensitive output.
     */
    private static void captureFullResponse(FullDataCapture capture, String source,
                                            byte[] responseBody, List<Tlv> tlvs) {
        if (capture == null || responseBody == null || responseBody.length == 0) return;
        capture.add("\n[" + source + "] " + responseBody.length + " response bytes");
        int parsedEnd = captureFullTlvs(capture, source, responseBody, tlvs, 0);
        if (parsedEnd < responseBody.length) {
            byte[] remainder = Arrays.copyOfRange(responseBody, Math.max(0, parsedEnd), responseBody.length);
            capture.add("[" + source + "] UNPARSED TRAILING BYTES: " + Nfc.toHex(remainder));
            wipe(remainder);
        }
    }

    private static int captureFullTlvs(FullDataCapture capture, String source, byte[] encoded,
                                       List<Tlv> tlvs, int depth) {
        if (depth > 8 || tlvs == null || tlvs.isEmpty()) return 0;
        int parsedEnd = 0;
        for (Tlv tlv : tlvs) {
            if (tlv.start < 0 || tlv.next <= tlv.start || tlv.next > encoded.length) continue;
            byte[] raw = Arrays.copyOfRange(encoded, tlv.start, tlv.next);
            String tag = String.format(java.util.Locale.ROOT, "%X", tlv.tag);
            capture.add("[" + source + "] TAG " + tag + " (" + tagDescription(tlv.tag)
                    + ", " + tlv.value.length + " value bytes): " + Nfc.toHex(raw));
            wipe(raw);
            String decoded = decodeSensitiveValue(tlv.tag, tlv.value);
            if (decoded != null) capture.add("[" + source + "] DECODED: " + decoded);
            parsedEnd = Math.max(parsedEnd, tlv.next);
            if (tlv.constructed) {
                List<Tlv> nested = parseTlvs(tlv.value);
                String nestedSource = source + " / " + tag;
                int nestedEnd = captureFullTlvs(capture, nestedSource, tlv.value, nested, depth + 1);
                if (nestedEnd < tlv.value.length) {
                    byte[] remainder = Arrays.copyOfRange(tlv.value, Math.max(0, nestedEnd), tlv.value.length);
                    capture.add("[" + nestedSource + "] UNPARSED TRAILING BYTES: " + Nfc.toHex(remainder));
                    wipe(remainder);
                }
                wipeTlvs(nested);
            }
        }
        return parsedEnd;
    }

    private static String tagDescription(int tag) {
        switch (tag) {
            case 0x4F: return "Application Identifier";
            case 0x50: return "Application Label";
            case 0x57: return "Track 2 Equivalent Data";
            case 0x5A: return "Application PAN";
            case 0x5F20: return "Cardholder Name";
            case 0x5F24: return "Application Expiration Date";
            case 0x5F25: return "Application Effective Date";
            case 0x5F28: return "Issuer Country Code";
            case 0x5F2A: return "Transaction Currency Code";
            case 0x5F2D: return "Language Preference";
            case 0x5F30: return "Service Code";
            case 0x5F34: return "PAN Sequence Number";
            case 0x6F: return "FCI Template";
            case 0x70: return "EMV Record Template";
            case 0x77: return "Response Message Template Format 2";
            case 0x80: return "Response Message Template Format 1";
            case 0x82: return "Application Interchange Profile";
            case 0x84: return "Dedicated File Name";
            case 0x87: return "Application Priority Indicator";
            case 0x8C: return "CDOL1";
            case 0x8D: return "CDOL2";
            case 0x8E: return "Cardholder Verification Method List";
            case 0x90: return "Issuer Public Key Certificate";
            case 0x92: return "Issuer Public Key Remainder";
            case 0x93: return "Signed Static Application Data";
            case 0x94: return "Application File Locator";
            case 0x95: return "Terminal Verification Results";
            case 0x9A: return "Transaction Date";
            case 0x9C: return "Transaction Type";
            case 0x9F02: return "Amount Authorised";
            case 0x9F03: return "Amount Other";
            case 0x9F07: return "Application Usage Control";
            case 0x9F08: return "Application Version Number";
            case 0x9F10: return "Issuer Application Data";
            case 0x9F17: return "PIN Try Counter";
            case 0x9F12: return "Application Preferred Name";
            case 0x9F1A: return "Terminal Country Code";
            case 0x9F26: return "Application Cryptogram";
            case 0x9F27: return "Cryptogram Information Data";
            case 0x9F32: return "Issuer Public Key Exponent";
            case 0x9F34: return "Cardholder Verification Method Results";
            case 0x9F36: return "Application Transaction Counter";
            case 0x9F37: return "Unpredictable Number";
            case 0x9F38: return "Processing Options Data Object List";
            case 0x9F42: return "Application Currency Code";
            case 0x9F44: return "Application Currency Exponent";
            case 0x9F4A: return "Static Data Authentication Tag List";
            case 0x9F4B: return "Signed Dynamic Application Data";
            case 0x9F4C: return "ICC Dynamic Number";
            case 0x9F4D: return "Log Entry";
            case 0x9F4F: return "Log Format";
            case 0x9F6B: return "Track 2 Data (contactless)";
            case 0x9F6C: return "Card Transaction Qualifiers";
            default: return "Unknown / proprietary EMV tag";
        }
    }

    private static String decodeSensitiveValue(int tag, byte[] value) {
        if (value == null || value.length == 0) return null;
        switch (tag) {
            case 0x5A:
                return "Application PAN: " + decodePackedDigits(value, false);
            case 0x57:
                return "Track 2 Equivalent Data: " + decodePackedDigits(value, true).replace('D', '=');
            case 0x9F6B:
                return "Track 2 Data (contactless): " + decodePackedDigits(value, true).replace('D', '=');
            case 0x5F20:
                return "Cardholder Name: " + decodeCardholderName(value);
            case 0x5F24:
                return "Application Expiration Date (YYMMDD): " + decodePackedDigits(value, false);
            case 0x5F25:
                return "Application Effective Date (YYMMDD): " + decodePackedDigits(value, false);
            case 0x5F34:
                return "PAN Sequence Number: " + decodePackedDigits(value, false);
            default:
                return null;
        }
    }

    private static String decodePackedDigits(byte[] value, boolean keepTrackSeparator) {
        String hex = Nfc.toHex(value);
        StringBuilder decoded = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char nibble = Character.toUpperCase(hex.charAt(i));
            if (nibble >= '0' && nibble <= '9') {
                decoded.append(nibble);
            } else if (keepTrackSeparator && nibble == 'D') {
                decoded.append('D');
            } else if (nibble == 'F') {
                // F is the conventional padding nibble at the end of packed EMV numeric data.
                if (i < hex.length() - 1) decoded.append('F');
            } else {
                decoded.append(nibble);
            }
        }
        while (decoded.length() > 0 && decoded.charAt(decoded.length() - 1) == 'F') {
            decoded.setLength(decoded.length() - 1);
        }
        return decoded.toString();
    }

    private static String decodeCardholderName(byte[] value) {
        String name = new String(value, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
        StringBuilder clean = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            clean.append(Character.isISOControl(c) ? ' ' : c);
        }
        return clean.toString().trim();
    }

    private static String safeTagLabel(Context context, int tag) {
        switch (tag) {
            case TAG_AIP: return context.getString(R.string.nfc_reader_emv_tag_aip);
            case TAG_AFL: return context.getString(R.string.nfc_reader_emv_tag_afl);
            case TAG_APP_LABEL: return context.getString(R.string.nfc_reader_emv_tag_app_label);
            case TAG_APP_PREFERRED_NAME: return context.getString(R.string.nfc_reader_emv_tag_app_preferred);
            case TAG_APP_PRIORITY: return context.getString(R.string.nfc_reader_emv_tag_app_priority);
            case TAG_LANGUAGE_PREFERENCE: return context.getString(R.string.nfc_reader_emv_tag_language);
            case TAG_APP_CURRENCY: return context.getString(R.string.nfc_reader_emv_tag_currency);
            case TAG_APP_CURRENCY_EXPONENT: return context.getString(R.string.nfc_reader_emv_tag_currency_exponent);
            case TAG_ISSUER_COUNTRY: return context.getString(R.string.nfc_reader_emv_tag_country);
            case TAG_APP_VERSION: return context.getString(R.string.nfc_reader_emv_tag_version);
            default: return null;
        }
    }

    private static String safeAscii(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c == 0) continue;
            if (c < 0x20 || c > 0x7E) return Nfc.toHex(bytes);
            out.append((char) c);
        }
        String value = out.toString().trim();
        return value.length() > 48 ? value.substring(0, 48) : value;
    }

    private static List<Application> parseApplications(byte[] response) {
        List<Application> applications = new ArrayList<>();
        byte[] responseBody = body(response);
        List<Tlv> tlvs = parseTlvs(responseBody);
        collectApplications(tlvs, applications, 0);
        wipeTlvs(tlvs);
        wipe(responseBody);
        return applications;
    }

    private static void collectApplications(List<Tlv> tlvs, List<Application> out, int depth) {
        if (depth > 8 || out.size() >= MAX_APPLICATIONS * 2) return;
        for (Tlv tlv : tlvs) {
            if (tlv.tag == 0x61) {
                List<Tlv> children = parseTlvs(tlv.value);
                Tlv aid = findDirectTag(children, 0x4F);
                Tlv label = findDirectTag(children, TAG_APP_LABEL);
                Tlv priority = findDirectTag(children, TAG_APP_PRIORITY);
                if (aid != null && aid.value.length >= 5 && aid.value.length <= 16) {
                    String aidHex = Nfc.toHex(aid.value);
                    String appLabel = label == null ? "" : safeAscii(label.value);
                    String appPriority = priority == null || priority.value.length == 0 ? "" : Nfc.toHex(priority.value);
                    boolean exists = false;
                    for (Application item : out) if (item.aid.equals(aidHex)) exists = true;
                    if (!exists) out.add(new Application(aidHex, appLabel, appPriority));
                }
                collectApplications(children, out, depth + 1);
                wipeTlvs(children);
            } else if (tlv.constructed) {
                List<Tlv> children = parseTlvs(tlv.value);
                collectApplications(children, out, depth + 1);
                wipeTlvs(children);
            }
        }
    }

    private static Tlv findDirectTag(List<Tlv> tlvs, int tag) {
        for (Tlv tlv : tlvs) if (tlv.tag == tag) return tlv;
        return null;
    }

    private static byte[] findTagValue(List<Tlv> tlvs, int tag, int depth) {
        if (depth > 8) return null;
        for (Tlv tlv : tlvs) {
            if (tlv.tag == tag) return tlv.value.clone();
            if (tlv.constructed) {
                List<Tlv> nested = parseTlvs(tlv.value);
                byte[] found = findTagValue(nested, tag, depth + 1);
                wipeTlvs(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<Tlv> parseTlvs(byte[] bytes) {
        List<Tlv> result = new ArrayList<>();
        if (bytes == null) return result;
        int pos = 0;
        while (pos < bytes.length) {
            Tlv tlv = readTlv(bytes, pos);
            if (tlv == null || tlv.next <= pos) break;
            result.add(tlv);
            pos = tlv.next;
        }
        return result;
    }

    private static Tlv readTlv(byte[] bytes, int start) {
        if (bytes == null || start < 0 || start >= bytes.length) return null;
        int pos = start;
        int first = bytes[pos++] & 0xFF;
        int tag = first;
        if ((first & 0x1F) == 0x1F) {
            int count = 0;
            int b;
            do {
                if (pos >= bytes.length || count++ >= 3) return null;
                b = bytes[pos++] & 0xFF;
                tag = (tag << 8) | b;
            } while ((b & 0x80) != 0);
        }
        if (pos >= bytes.length) return null;
        int length = bytes[pos++] & 0xFF;
        if (length == 0x81) {
            if (pos >= bytes.length) return null;
            length = bytes[pos++] & 0xFF;
        } else if (length == 0x82) {
            if (pos + 1 >= bytes.length) return null;
            length = ((bytes[pos] & 0xFF) << 8) | (bytes[pos + 1] & 0xFF);
            pos += 2;
        } else if ((length & 0x80) != 0) {
            return null;
        }
        if (length < 0 || length > bytes.length - pos) return null;
        return new Tlv(tag, (first & 0x20) != 0, start,
                Arrays.copyOfRange(bytes, pos, pos + length), pos + length);
    }

    /** Handles ISO 7816 6Cxx corrected Le and 61xx GET RESPONSE chaining. */
    private static byte[] exchange(IsoDep isoDep, byte[] command) throws IOException {
        byte[] response = isoDep.transceive(command);
        if (response == null || response.length < 2) return response == null ? new byte[0] : response;
        int sw1 = response[response.length - 2] & 0xFF;
        int sw2 = response[response.length - 1] & 0xFF;
        if (sw1 == 0x6C && command.length >= 5) {
            byte[] corrected = command.clone();
            corrected[corrected.length - 1] = (byte) sw2;
            wipe(response);
            response = isoDep.transceive(corrected);
            wipe(corrected);
        }
        if (response.length < 2 || (response[response.length - 2] & 0xFF) != 0x61) return response;

        byte[] aggregate = body(response);
        int loops = 0;
        while (response.length >= 2 && (response[response.length - 2] & 0xFF) == 0x61 && loops++ < 8) {
            int remaining = response[response.length - 1] & 0xFF;
            byte[] getResponse = new byte[] {0x00, (byte) 0xC0, 0x00, 0x00, (byte) remaining};
            byte[] next = isoDep.transceive(getResponse);
            wipe(response);
            response = next;
            if (response == null || response.length < 2) break;
            byte[] chunk = body(response);
            if (aggregate.length + chunk.length > MAX_RESPONSE_BYTES) {
                wipe(chunk);
                break;
            }
            aggregate = concat(aggregate, chunk);
            wipe(chunk);
        }
        byte[] finalResponse = new byte[aggregate.length + 2];
        System.arraycopy(aggregate, 0, finalResponse, 0, aggregate.length);
        if (response != null && response.length >= 2) {
            finalResponse[finalResponse.length - 2] = response[response.length - 2];
            finalResponse[finalResponse.length - 1] = response[response.length - 1];
        }
        wipe(aggregate);
        wipe(response);
        return finalResponse;
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] result = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        wipe(left);
        return result;
    }

    private static byte[] body(byte[] response) {
        if (response == null || response.length <= 2) return new byte[0];
        return Arrays.copyOf(response, response.length - 2);
    }

    private static boolean isSuccess(byte[] response) {
        return response != null && response.length >= 2
                && response[response.length - 2] == (byte) 0x90 && response[response.length - 1] == 0x00;
    }

    private static String sw(byte[] response) {
        if (response == null || response.length < 2) return "--";
        return String.format(java.util.Locale.ROOT, "%02X%02X",
                response[response.length - 2] & 0xFF, response[response.length - 1] & 0xFF);
    }

    private static byte[] fromHex(String hex) {
        if (hex == null || (hex.length() & 1) != 0) throw new IllegalArgumentException("Invalid hex");
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Invalid hex");
            result[i] = (byte) ((hi << 4) | lo);
        }
        return result;
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    private static void wipeTlvs(List<Tlv> tlvs) {
        if (tlvs == null) return;
        for (Tlv tlv : tlvs) {
            wipe(tlv.value);
        }
    }

    private static final class FullDataCapture {
        final List<String> lines = new ArrayList<>();
        int usedChars;
        boolean truncated;

        void add(String line) {
            if (line == null || line.isEmpty()) return;
            if (usedChars >= MAX_FULL_DATA_CHARS) {
                truncated = true;
                return;
            }
            int available = MAX_FULL_DATA_CHARS - usedChars;
            if (line.length() > available) {
                lines.add(line.substring(0, Math.max(0, available)));
                usedChars += available;
                truncated = true;
                return;
            }
            lines.add(line);
            usedChars += line.length();
        }
    }

    static final class Report {
        final List<ApplicationResult> applications;
        final boolean truncated;
        final String error;
        final List<String> fullDataLines;
        final boolean fullDataTruncated;
        final boolean includesSensitiveData;

        Report(List<ApplicationResult> applications, boolean truncated, List<String> fullDataLines,
               boolean fullDataTruncated, boolean includesSensitiveData) {
            this.applications = applications;
            this.truncated = truncated;
            this.error = null;
            this.fullDataLines = fullDataLines;
            this.fullDataTruncated = fullDataTruncated;
            this.includesSensitiveData = includesSensitiveData;
        }

        private Report(String error) {
            this.applications = new ArrayList<>();
            this.truncated = false;
            this.error = error;
            this.fullDataLines = null;
            this.fullDataTruncated = false;
            this.includesSensitiveData = false;
        }

        static Report failure(String error) { return new Report(error); }

        String toDisplayText(Context context) {
            StringBuilder out = new StringBuilder();
            if (error != null) return error;
            out.append(context.getString(R.string.nfc_reader_emv_app_count, applications.size())).append('\n');
            for (int i = 0; i < applications.size(); i++) {
                ApplicationResult app = applications.get(i);
                if (i > 0) out.append("\n");
                out.append(context.getString(R.string.nfc_reader_emv_app_heading, i + 1,
                        app.label.isEmpty() ? context.getString(R.string.nfc_reader_emv_default_app) : app.label)).append('\n')
                        .append(context.getString(R.string.nfc_reader_emv_aid, app.aid)).append('\n')
                        .append(context.getString(R.string.nfc_reader_emv_select_gpo, app.selectStatus,
                                app.gpoStatus.isEmpty() ? context.getString(R.string.nfc_reader_emv_not_run) : app.gpoStatus)).append('\n');
                if (!app.fields.isEmpty()) {
                    for (Map.Entry<String, String> entry : app.fields.entrySet()) {
                        out.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
                    }
                }
                if (app.recordsRead > 0 || app.recordErrors > 0) {
                    out.append(context.getString(R.string.nfc_reader_emv_record_count,
                            app.recordsRead, app.recordErrors)).append('\n');
                    for (String statusLine : app.recordStatusLines) out.append(statusLine).append('\n');
                }
                if (app.limitReached) out.append(context.getString(R.string.nfc_reader_emv_record_limit)).append('\n');
                if (app.message != null) out.append(app.message).append('\n');
            }
            if (truncated) out.append(context.getString(R.string.nfc_reader_emv_app_limit)).append('\n');
            if (includesSensitiveData) {
                out.append("\n\n").append(context.getString(R.string.nfc_reader_emv_full_data_header)).append('\n');
                if (fullDataLines != null) {
                    for (String line : fullDataLines) out.append(line).append('\n');
                }
                if (fullDataTruncated) out.append(context.getString(R.string.nfc_reader_emv_full_data_truncated)).append('\n');
                out.append(context.getString(R.string.nfc_reader_emv_full_data_warning));
            } else {
                out.append('\n').append(context.getString(R.string.nfc_reader_emv_filtered));
            }
            return out.toString().trim();
        }
    }

    private static final class Application {
        final String aid;
        final String label;
        final String priority;
        Application(String aid, String label, String priority) {
            this.aid = aid;
            this.label = label == null ? "" : label;
            this.priority = priority == null ? "" : priority;
        }
    }

    private static final class ApplicationResult {
        final String aid;
        final String label;
        final String priority;
        final Map<String, String> fields = new LinkedHashMap<>();
        String selectStatus = "--";
        String gpoStatus = "";
        String message;
        int recordsRead;
        int recordErrors;
        boolean limitReached;
        final List<String> recordStatusLines = new ArrayList<>();
        ApplicationResult(String aid, String label, String priority) {
            this.aid = aid;
            this.label = label == null ? "" : label;
            this.priority = priority == null ? "" : priority;
        }
    }

    private static final class GpoData {
        byte[] aip;
        byte[] afl;
        boolean recordsAllowed;
    }

    private static final class Tlv {
        final int tag;
        final boolean constructed;
        final int start;
        final byte[] value;
        final int next;
        Tlv(int tag, boolean constructed, int start, byte[] value, int next) {
            this.tag = tag;
            this.constructed = constructed;
            this.start = start;
            this.value = value;
            this.next = next;
        }
    }
}
