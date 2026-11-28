package wallet.nfc;

import android.content.Context;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import wallet.main.R;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
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
                captureFullResponse(context, fullData, "PPSE SELECT", ppseBody, ppseTlvs);
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
            if (fullData != null) fullData.addMissingEssentialFields(context);
            return new Report(results, applications.size() > MAX_APPLICATIONS,
                    fullData == null ? null : fullData.lines,
                    fullData == null ? null : fullData.displayFields,
                    fullData != null && fullData.displayFieldsTruncated,
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
                captureFullResponse(context, fullData, "AID " + app.aid + " / SELECT FCI", selectBody, selectTlvs);
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
                captureFullResponse(context, fullData, "AID " + app.aid + " / GPO", gpoBody, gpoTlvs);
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
                        captureFullResponse(context, fullData,
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
    private static void captureFullResponse(Context context, FullDataCapture capture, String source,
                                            byte[] responseBody, List<Tlv> tlvs) {
        if (capture == null || responseBody == null || responseBody.length == 0) return;
        capture.add("\n[" + source + "] " + responseBody.length + " response bytes");
        int parsedEnd = captureFullTlvs(context, capture, source, responseBody, tlvs, 0);
        if (parsedEnd < responseBody.length) {
            byte[] remainder = Arrays.copyOfRange(responseBody, Math.max(0, parsedEnd), responseBody.length);
            capture.add("[" + source + "] UNPARSED TRAILING BYTES: " + Nfc.toHex(remainder));
            wipe(remainder);
        }
    }

    private static int captureFullTlvs(Context context, FullDataCapture capture, String source, byte[] encoded,
                                       List<Tlv> tlvs, int depth) {
        if (depth > 8 || tlvs == null || tlvs.isEmpty()) return 0;
        int parsedEnd = 0;
        for (Tlv tlv : tlvs) {
            if (tlv.start < 0 || tlv.next <= tlv.start || tlv.next > encoded.length) continue;
            byte[] raw = Arrays.copyOfRange(encoded, tlv.start, tlv.next);
            String tag = String.format(java.util.Locale.ROOT, "%X", tlv.tag);
            String rawHex = Nfc.toHex(raw);
            String sourceLabel = displaySource(context, source);
            String tagLabel = tagDescription(context, tlv.tag);
            String decoded = formatHumanValue(context, tlv.tag, tlv.value);
            capture.add("[" + source + "] TAG " + tag + " (" + tagLabel
                    + ", " + tlv.value.length + " value bytes): " + rawHex);
            if (!tlv.constructed) {
                capture.addDisplayField(new DisplayField(tlv.tag,
                        categoryLabel(context, tlv.tag), tagLabel, decoded,
                        "Tag " + tag + " · " + sourceLabel, rawHex));
            }
            wipe(raw);
            parsedEnd = Math.max(parsedEnd, tlv.next);
            if (tlv.constructed) {
                List<Tlv> nested = parseTlvs(tlv.value);
                String nestedSource = source + " / " + tag;
                int nestedEnd = captureFullTlvs(context, capture, nestedSource, tlv.value, nested, depth + 1);
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

    private static String tagDescription(Context context, int tag) {
        switch (tag) {
            case 0x4F: return context.getString(R.string.nfc_reader_emv_field_aid);
            case 0x50: return context.getString(R.string.nfc_reader_emv_field_app_label);
            case 0x57: return context.getString(R.string.nfc_reader_emv_field_track2);
            case 0x5A: return context.getString(R.string.nfc_reader_emv_field_pan);
            case 0x5F20: case 0x9F0B: return context.getString(R.string.nfc_reader_emv_field_cardholder);
            case 0x5F24: return context.getString(R.string.nfc_reader_emv_field_expiry);
            case 0x5F25: return context.getString(R.string.nfc_reader_emv_field_effective);
            case 0x5F28: return context.getString(R.string.nfc_reader_emv_field_country);
            case 0x5F30: return context.getString(R.string.nfc_reader_emv_field_service_code);
            case 0x5F2A: return context.getString(R.string.nfc_reader_emv_field_transaction_currency);
            case 0x5F2D: return context.getString(R.string.nfc_reader_emv_field_language);
            case 0x5F34: return context.getString(R.string.nfc_reader_emv_field_pan_sequence);
            case 0x6F: return context.getString(R.string.nfc_reader_emv_field_fci);
            case 0x70: return context.getString(R.string.nfc_reader_emv_field_record_template);
            case 0x77: return context.getString(R.string.nfc_reader_emv_field_response_template);
            case 0x80: return context.getString(R.string.nfc_reader_emv_field_gpo_template);
            case 0x82: return context.getString(R.string.nfc_reader_emv_field_aip);
            case 0x84: return context.getString(R.string.nfc_reader_emv_field_df_name);
            case 0x87: return context.getString(R.string.nfc_reader_emv_field_app_priority);
            case 0x8C: return context.getString(R.string.nfc_reader_emv_field_cdol1);
            case 0x8D: return context.getString(R.string.nfc_reader_emv_field_cdol2);
            case 0x8E: return context.getString(R.string.nfc_reader_emv_field_cvm_list);
            case 0x90: return context.getString(R.string.nfc_reader_emv_field_issuer_cert);
            case 0x92: return context.getString(R.string.nfc_reader_emv_field_issuer_remainder);
            case 0x93: return context.getString(R.string.nfc_reader_emv_field_signed_static);
            case 0x94: return context.getString(R.string.nfc_reader_emv_field_afl);
            case 0x95: return context.getString(R.string.nfc_reader_emv_field_tvr);
            case 0x9A: return context.getString(R.string.nfc_reader_emv_field_transaction_date);
            case 0x9C: return context.getString(R.string.nfc_reader_emv_field_transaction_type);
            case 0x9F02: return context.getString(R.string.nfc_reader_emv_field_amount_authorised);
            case 0x9F03: return context.getString(R.string.nfc_reader_emv_field_amount_other);
            case 0x9F07: return context.getString(R.string.nfc_reader_emv_field_usage_control);
            case 0x9F08: return context.getString(R.string.nfc_reader_emv_field_app_version);
            case 0x9F10: return context.getString(R.string.nfc_reader_emv_field_issuer_data);
            case 0x9F17: return context.getString(R.string.nfc_reader_emv_field_pin_try_counter);
            case 0x9F12: return context.getString(R.string.nfc_reader_emv_field_app_preferred);
            case 0x9F1A: return context.getString(R.string.nfc_reader_emv_field_terminal_country);
            case 0x9F26: return context.getString(R.string.nfc_reader_emv_field_cryptogram);
            case 0x9F27: return context.getString(R.string.nfc_reader_emv_field_cryptogram_info);
            case 0x9F32: return context.getString(R.string.nfc_reader_emv_field_issuer_exponent);
            case 0x9F34: return context.getString(R.string.nfc_reader_emv_field_cvm_result);
            case 0x9F36: return context.getString(R.string.nfc_reader_emv_field_atc);
            case 0x9F37: return context.getString(R.string.nfc_reader_emv_field_unpredictable);
            case 0x9F38: return context.getString(R.string.nfc_reader_emv_field_pdol);
            case 0x9F42: return context.getString(R.string.nfc_reader_emv_field_app_currency);
            case 0x9F44: return context.getString(R.string.nfc_reader_emv_field_currency_exponent);
            case 0x9F4A: return context.getString(R.string.nfc_reader_emv_field_static_auth_list);
            case 0x9F4B: return context.getString(R.string.nfc_reader_emv_field_signed_dynamic);
            case 0x9F4C: return context.getString(R.string.nfc_reader_emv_field_dynamic_number);
            case 0x9F4D: return context.getString(R.string.nfc_reader_emv_field_log_entry);
            case 0x9F4F: return context.getString(R.string.nfc_reader_emv_field_log_format);
            case 0x9F6B: return context.getString(R.string.nfc_reader_emv_field_track2_contactless);
            case 0x9F6C: return context.getString(R.string.nfc_reader_emv_field_ctq);
            default: return context.getString(R.string.nfc_reader_emv_field_unknown, String.format(java.util.Locale.ROOT, "%X", tag));
        }
    }

    private static String categoryLabel(Context context, int tag) {
        switch (tag) {
            case 0x5A: case 0x57: case 0x9F6B: case 0x5F20: case 0x9F0B: case 0x5F24: case 0x5F25: case 0x5F34:
                return context.getString(R.string.nfc_reader_emv_group_card);
            case 0x4F: case 0x50: case 0x9F12: case 0x87: case 0x5F28: case 0x5F2A:
            case 0x5F2D: case 0x9F42: case 0x9F44: case 0x9F08: case 0x9F1A:
                return context.getString(R.string.nfc_reader_emv_group_application);
            case 0x82: case 0x94: case 0x8E: case 0x95: case 0x9F07: case 0x9F17:
            case 0x9F26: case 0x9F27: case 0x9F34: case 0x9F36: case 0x9F10:
                return context.getString(R.string.nfc_reader_emv_group_security);
            default:
                return context.getString(R.string.nfc_reader_emv_group_other);
        }
    }

    private static String displaySource(Context context, String source) {
        if (source == null) return context.getString(R.string.nfc_reader_emv_source_unknown);
        if (source.startsWith("PPSE")) return context.getString(R.string.nfc_reader_emv_source_directory);
        if (source.contains("/ GPO")) return context.getString(R.string.nfc_reader_emv_source_processing);
        if (source.contains("/ SELECT FCI")) return context.getString(R.string.nfc_reader_emv_source_application);
        if (source.contains("RECORD")) return context.getString(R.string.nfc_reader_emv_source_record);
        return context.getString(R.string.nfc_reader_emv_source_card_response);
    }

    private static String formatHumanValue(Context context, int tag, byte[] value) {
        if (value == null || value.length == 0) return context.getString(R.string.nfc_reader_emv_value_empty);
        switch (tag) {
            case 0x4F: {
                String aid = Nfc.toHex(value);
                return aidBrand(context, aid) + "\n" + aid;
            }
            case 0x50: case 0x9F12:
                return decodeTextOrHex(value);
            case 0x5A:
                return context.getString(R.string.nfc_reader_emv_value_pan,
                        groupDigits(decodePackedDigits(value, false)));
            case 0x57: case 0x9F6B:
                return decodeTrack2(context, value);
            case 0x5F20: case 0x9F0B:
                return decodeCardholderName(value);
            case 0x5F24:
                return formatExpiry(value);
            case 0x5F25:
                return formatEffectiveDate(value);
            case 0x5F28: case 0x9F1A:
                return countryName(context, decodePackedDigits(value, false));
            case 0x5F2A: case 0x9F42:
                return currencyName(context, decodePackedDigits(value, false));
            case 0x5F2D:
                return decodeLanguagePreference(context, value);
            case 0x9F44:
                if (value.length == 1) return context.getString(R.string.nfc_reader_emv_value_currency_exponent, value[0] & 0xFF);
                break;
            case 0x9F02: case 0x9F03:
                return formatAmount(context, value);
            case 0x9A:
                return formatTransactionDate(context, value);
            case 0x9C:
                if (value.length == 1) return decodeTransactionType(context, value[0] & 0xFF);
                break;
            case 0x95:
                return decodeTvr(context, value);
            case 0x5F34:
                return decodePackedDigits(value, false);
            case 0x82:
                return decodeAip(context, value);
            case 0x94:
                return decodeAfl(context, value);
            case 0x8E:
                return decodeCvmList(context, value);
            case 0x9F34:
                return decodeCvmResult(context, value);
            case 0x9F07:
                return decodeApplicationUsageControl(context, value);
            case 0x9F17:
                if (value.length == 1) return context.getString(R.string.nfc_reader_emv_value_pin_tries, value[0] & 0xFF);
                break;
            case 0x9F27:
                return decodeCryptogramInfo(context, value);
            case 0x9F36:
                if (value.length == 2) return String.valueOf(((value[0] & 0xFF) << 8) | (value[1] & 0xFF));
                break;
            case 0x9F4D:
                if (value.length >= 2) return context.getString(R.string.nfc_reader_emv_value_log_entry,
                        (value[0] & 0xF8) >>> 3, value[1] & 0xFF);
                break;
            case 0x5F30:
                return context.getString(R.string.nfc_reader_emv_value_service_code, decodePackedDigits(value, false));
        }
        // Unknown and proprietary tags are never guessed to be text. Preserve their exact value as HEX.
        return context.getString(R.string.nfc_reader_emv_value_raw_only, Nfc.toHex(value));
    }

    private static String groupDigits(String digits) {
        if (digits == null || digits.isEmpty()) return digits == null ? "" : digits;
        StringBuilder grouped = new StringBuilder(digits.length() + digits.length() / 4);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) grouped.append(' ');
            grouped.append(digits.charAt(i));
        }
        return grouped.toString();
    }

    private static String decodeLanguagePreference(Context context, byte[] value) {
        String codes = decodeText(value).toLowerCase(java.util.Locale.ROOT).replace(" ", "");
        if (codes.isEmpty()) return Nfc.toHex(value);
        List<String> languages = new ArrayList<>();
        for (int i = 0; i + 1 < codes.length(); i += 2) {
            String code = codes.substring(i, i + 2);
            int label;
            switch (code) {
                case "en": label = R.string.nfc_reader_emv_language_english; break;
                case "vi": label = R.string.nfc_reader_emv_language_vietnamese; break;
                case "fr": label = R.string.nfc_reader_emv_language_french; break;
                case "de": label = R.string.nfc_reader_emv_language_german; break;
                case "es": label = R.string.nfc_reader_emv_language_spanish; break;
                case "ja": label = R.string.nfc_reader_emv_language_japanese; break;
                case "zh": label = R.string.nfc_reader_emv_language_chinese; break;
                default: label = R.string.nfc_reader_emv_language_unknown; break;
            }
            languages.add(context.getString(label, code));
        }
        if ((codes.length() & 1) != 0) {
            languages.add(context.getString(R.string.nfc_reader_emv_language_unknown,
                    codes.substring(codes.length() - 1)));
        }
        return android.text.TextUtils.join("\n", languages);
    }

    private static String formatAmount(Context context, byte[] value) {
        String digits = decodePackedDigits(value, false);
        if (digits.isEmpty()) return Nfc.toHex(value);
        return context.getString(R.string.nfc_reader_emv_value_amount_minor_units, formatNumberDigits(digits));
    }

    private static String formatNumberDigits(String digits) {
        if (digits == null || digits.isEmpty()) return digits == null ? "" : digits;
        try {
            long value = Long.parseLong(digits);
            return NumberFormat.getIntegerInstance().format(value);
        } catch (NumberFormatException ignored) {
            return digits;
        }
    }

    private static String formatTransactionDate(Context context, byte[] value) {
        String digits = decodePackedDigits(value, false);
        if (digits.length() != 6) return Nfc.toHex(value);
        return context.getString(R.string.nfc_reader_emv_value_transaction_date,
                digits.substring(4, 6), digits.substring(2, 4), digits.substring(0, 2));
    }

    private static String decodeTransactionType(Context context, int type) {
        switch (type) {
            case 0x00: return context.getString(R.string.nfc_reader_emv_transaction_type_purchase);
            case 0x01: return context.getString(R.string.nfc_reader_emv_transaction_type_cash);
            case 0x09: return context.getString(R.string.nfc_reader_emv_transaction_type_cashback);
            case 0x20: return context.getString(R.string.nfc_reader_emv_transaction_type_refund);
            default: return context.getString(R.string.nfc_reader_emv_transaction_type_unknown,
                    String.format(java.util.Locale.ROOT, "%02X", type));
        }
    }

    private static String decodeTvr(Context context, byte[] value) {
        if (value.length != 5) return Nfc.toHex(value);
        List<String> flags = new ArrayList<>();
        int b1 = value[0] & 0xFF;
        int b2 = value[1] & 0xFF;
        int b3 = value[2] & 0xFF;
        int b4 = value[3] & 0xFF;
        int b5 = value[4] & 0xFF;
        if ((b1 & 0x80) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_offline_auth_not_performed));
        if ((b1 & 0x40) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_sda_failed));
        if ((b1 & 0x20) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_icc_data_missing));
        if ((b1 & 0x10) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_exception_file));
        if ((b1 & 0x08) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_dda_failed));
        if ((b1 & 0x04) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_cda_failed));
        if ((b1 & 0x02) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_sda_selected));
        if ((b2 & 0x80) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_version_mismatch));
        if ((b2 & 0x40) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_application_expired));
        if ((b2 & 0x20) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_application_not_yet_effective));
        if ((b2 & 0x10) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_service_not_allowed));
        if ((b2 & 0x08) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_new_card));
        if ((b3 & 0x80) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_cvm_failed));
        if ((b3 & 0x40) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_unrecognised_cvm));
        if ((b3 & 0x20) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_pin_try_limit));
        if ((b3 & 0x10) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_pin_pad_unavailable));
        if ((b3 & 0x08) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_pin_not_entered));
        if ((b3 & 0x04) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_online_pin_entered));
        if ((b4 & 0x80) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_floor_limit));
        if ((b4 & 0x40) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_lower_offline_limit));
        if ((b4 & 0x20) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_upper_offline_limit));
        if ((b4 & 0x10) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_random_online));
        if ((b4 & 0x08) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_merchant_online));
        if ((b5 & 0x80) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_default_tdol));
        if ((b5 & 0x40) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_issuer_auth_failed));
        if ((b5 & 0x20) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_script_before_ac_failed));
        if ((b5 & 0x10) != 0) flags.add(context.getString(R.string.nfc_reader_emv_tvr_script_after_ac_failed));
        String detail = flags.isEmpty() ? context.getString(R.string.nfc_reader_emv_value_no_flags)
                : android.text.TextUtils.join("\n• ", flags);
        return Nfc.toHex(value) + "\n• " + detail + "\n" + context.getString(R.string.nfc_reader_emv_tvr_note);
    }

    private static String decodeText(byte[] value) {
        StringBuilder out = new StringBuilder(value.length);
        for (byte b : value) {
            int c = b & 0xFF;
            if (c == 0 || c == 0xFF) continue;
            if (c < 0x20 || c > 0x7E) return Nfc.toHex(value);
            out.append((char) c);
        }
        String text = out.toString().replace('<', ' ').trim();
        return text.isEmpty() ? Nfc.toHex(value) : text;
    }

    private static String decodeTextOrHex(byte[] value) {
        String decoded = decodeText(value);
        boolean printable = true;
        for (byte b : value) {
            int c = b & 0xFF;
            if (c != 0 && c != 0xFF && (c < 0x20 || c > 0x7E)) { printable = false; break; }
        }
        return printable ? decoded : "HEX: " + Nfc.toHex(value);
    }

    private static String formatExpiry(byte[] value) {
        String digits = decodePackedDigits(value, false);
        if (digits.length() >= 4) return digits.substring(2, 4) + "/" + digits.substring(0, 2);
        return digits;
    }

    private static String formatEffectiveDate(byte[] value) {
        String digits = decodePackedDigits(value, false);
        if (digits.length() == 6) return digits.substring(4, 6) + "/" + digits.substring(2, 4) + "/" + digits.substring(0, 2);
        return digits;
    }

    private static String decodeTrack2(Context context, byte[] value) {
        String track = decodePackedDigits(value, true);
        int separator = track.indexOf('D');
        if (separator <= 0 || track.length() < separator + 8) {
            return context.getString(R.string.nfc_reader_emv_value_raw_only, track);
        }
        String pan = track.substring(0, separator);
        String expiry = track.substring(separator + 1, separator + 5);
        String service = track.substring(separator + 5, separator + 8);
        String discretionary = track.substring(separator + 8);
        StringBuilder out = new StringBuilder();
        out.append(context.getString(R.string.nfc_reader_emv_value_track_pan, groupDigits(pan)));
        out.append('\n').append(context.getString(R.string.nfc_reader_emv_value_track_expiry,
                expiry.substring(2, 4) + "/" + expiry.substring(0, 2)));
        out.append('\n').append(context.getString(R.string.nfc_reader_emv_value_track_service,
                decodeServiceCode(context, service)));
        out.append('\n').append(context.getString(R.string.nfc_reader_emv_value_track_discretionary,
                discretionary.isEmpty() ? context.getString(R.string.nfc_reader_emv_value_not_present) : discretionary));
        return out.toString();
    }

    private static String decodeServiceCode(Context context, String service) {
        return service == null ? "" : service;
    }

    private static String decodeAip(Context context, byte[] value) {
        if (value.length != 2) return Nfc.toHex(value);
        int flags = value[0] & 0xFF;
        List<String> features = new ArrayList<>();
        if ((flags & 0x80) != 0) features.add(context.getString(R.string.nfc_reader_emv_aip_xda));
        if ((flags & 0x40) != 0) features.add(context.getString(R.string.nfc_reader_emv_aip_sda));
        if ((flags & 0x20) != 0) features.add(context.getString(R.string.nfc_reader_emv_aip_dda));
        if ((flags & 0x10) != 0) features.add(context.getString(R.string.nfc_reader_emv_aip_cvm));
        if ((flags & 0x08) != 0) features.add(context.getString(R.string.nfc_reader_emv_aip_terminal_risk));
        if ((flags & 0x04) != 0) features.add(context.getString(R.string.nfc_reader_emv_aip_issuer_auth));
        if ((flags & 0x01) != 0) features.add(context.getString(R.string.nfc_reader_emv_aip_cda));
        String description = features.isEmpty() ? context.getString(R.string.nfc_reader_emv_value_no_flags)
                : android.text.TextUtils.join("\n• ", features);
        return Nfc.toHex(value) + "\n• " + description;
    }

    private static String decodeAfl(Context context, byte[] value) {
        if (value.length == 0 || value.length % 4 != 0) return Nfc.toHex(value);
        StringBuilder out = new StringBuilder(Nfc.toHex(value));
        out.append('\n');
        for (int i = 0; i < value.length; i += 4) {
            int sfi = (value[i] & 0xFF) >>> 3;
            int first = value[i + 1] & 0xFF;
            int last = value[i + 2] & 0xFF;
            int auth = value[i + 3] & 0xFF;
            if (i > 0) out.append('\n');
            out.append(context.getString(R.string.nfc_reader_emv_afl_entry, sfi, first, last, auth));
        }
        return out.toString();
    }

    private static String decodeCvmList(Context context, byte[] value) {
        if (value.length < 8 || ((value.length - 8) & 1) != 0) return Nfc.toHex(value);
        String amountX = decodePackedDigits(Arrays.copyOfRange(value, 0, 4), false);
        String amountY = decodePackedDigits(Arrays.copyOfRange(value, 4, 8), false);
        StringBuilder out = new StringBuilder();
        out.append(context.getString(R.string.nfc_reader_emv_cvm_amounts,
                formatNumberDigits(amountX), formatNumberDigits(amountY)));
        for (int i = 8; i + 1 < value.length; i += 2) {
            int method = value[i] & 0x3F;
            int condition = value[i + 1] & 0xFF;
            out.append("\n• ").append(cvmMethod(context, method)).append(" — ").append(cvmCondition(context, condition));
            if ((value[i] & 0x40) != 0) out.append(" (").append(context.getString(R.string.nfc_reader_emv_cvm_continue_on_failure)).append(')');
        }
        return out.toString();
    }

    private static String hexRange(byte[] value, int start, int end) {
        StringBuilder out = new StringBuilder((end - start) * 2);
        for (int i = start; i < end && i < value.length; i++) {
            out.append(String.format(java.util.Locale.ROOT, "%02X", value[i] & 0xFF));
        }
        return out.toString();
    }

    private static String decodeCvmResult(Context context, byte[] value) {
        if (value.length != 3) return Nfc.toHex(value);
        int method = value[0] & 0x3F;
        int condition = value[1] & 0xFF;
        int result = value[2] & 0xFF;
        String outcome = result == 0x02 ? context.getString(R.string.nfc_reader_emv_cvm_success)
                : result == 0x01 ? context.getString(R.string.nfc_reader_emv_cvm_failed)
                : context.getString(R.string.nfc_reader_emv_cvm_unknown);
        return cvmMethod(context, method) + "\n" + cvmCondition(context, condition) + "\n" + outcome;
    }

    private static String cvmMethod(Context context, int method) {
        switch (method) {
            case 0x00: return context.getString(R.string.nfc_reader_emv_cvm_fail);
            case 0x01: return context.getString(R.string.nfc_reader_emv_cvm_plaintext_pin);
            case 0x02: return context.getString(R.string.nfc_reader_emv_cvm_online_pin);
            case 0x03: return context.getString(R.string.nfc_reader_emv_cvm_plaintext_pin_signature);
            case 0x04: return context.getString(R.string.nfc_reader_emv_cvm_enciphered_pin);
            case 0x05: return context.getString(R.string.nfc_reader_emv_cvm_enciphered_pin_signature);
            case 0x1E: return context.getString(R.string.nfc_reader_emv_cvm_signature);
            case 0x1F: return context.getString(R.string.nfc_reader_emv_cvm_no_cvm);
            default: return context.getString(R.string.nfc_reader_emv_cvm_unknown_method, String.format(java.util.Locale.ROOT, "%02X", method));
        }
    }

    private static String cvmCondition(Context context, int condition) {
        switch (condition) {
            case 0x00: return context.getString(R.string.nfc_reader_emv_cvm_condition_always);
            case 0x01: return context.getString(R.string.nfc_reader_emv_cvm_condition_cash);
            case 0x02: return context.getString(R.string.nfc_reader_emv_cvm_condition_not_cash);
            case 0x03: return context.getString(R.string.nfc_reader_emv_cvm_condition_terminal_support);
            case 0x04: return context.getString(R.string.nfc_reader_emv_cvm_condition_manual_cash);
            case 0x05: return context.getString(R.string.nfc_reader_emv_cvm_condition_cashback);
            case 0x06: return context.getString(R.string.nfc_reader_emv_cvm_condition_under_x);
            case 0x07: return context.getString(R.string.nfc_reader_emv_cvm_condition_over_x);
            case 0x08: return context.getString(R.string.nfc_reader_emv_cvm_condition_under_y);
            case 0x09: return context.getString(R.string.nfc_reader_emv_cvm_condition_over_y);
            default: return context.getString(R.string.nfc_reader_emv_cvm_unknown_condition, String.format(java.util.Locale.ROOT, "%02X", condition));
        }
    }

    private static String decodeApplicationUsageControl(Context context, byte[] value) {
        if (value.length != 2) return Nfc.toHex(value);
        int first = value[0] & 0xFF;
        int second = value[1] & 0xFF;
        List<String> allowed = new ArrayList<>();
        int[] masks = {0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02, 0x01};
        int[] labels = {
                R.string.nfc_reader_emv_auc_domestic_cash, R.string.nfc_reader_emv_auc_international_cash,
                R.string.nfc_reader_emv_auc_domestic_goods, R.string.nfc_reader_emv_auc_international_goods,
                R.string.nfc_reader_emv_auc_domestic_services, R.string.nfc_reader_emv_auc_international_services,
                R.string.nfc_reader_emv_auc_atm, R.string.nfc_reader_emv_auc_non_atm
        };
        for (int i = 0; i < masks.length; i++) if ((first & masks[i]) != 0) allowed.add(context.getString(labels[i]));
        if ((second & 0x80) != 0) allowed.add(context.getString(R.string.nfc_reader_emv_auc_domestic_cashback));
        if ((second & 0x40) != 0) allowed.add(context.getString(R.string.nfc_reader_emv_auc_international_cashback));
        String details = allowed.isEmpty() ? context.getString(R.string.nfc_reader_emv_value_no_flags)
                : android.text.TextUtils.join("\n• ", allowed);
        return Nfc.toHex(value) + "\n• " + details;
    }

    private static String decodeCryptogramInfo(Context context, byte[] value) {
        if (value.length != 1) return Nfc.toHex(value);
        int type = value[0] & 0xC0;
        String meaning = type == 0x00 ? context.getString(R.string.nfc_reader_emv_cryptogram_aac)
                : type == 0x40 ? context.getString(R.string.nfc_reader_emv_cryptogram_tc)
                : type == 0x80 ? context.getString(R.string.nfc_reader_emv_cryptogram_arqc)
                : context.getString(R.string.nfc_reader_emv_cryptogram_reserved);
        return Nfc.toHex(value) + " · " + meaning;
    }

    private static String aidBrand(Context context, String aid) {
        if (aid.startsWith("A000000003")) return context.getString(R.string.nfc_reader_emv_scheme_visa);
        if (aid.startsWith("A000000004")) return context.getString(R.string.nfc_reader_emv_scheme_mastercard);
        if (aid.startsWith("A000000025")) return context.getString(R.string.nfc_reader_emv_scheme_amex);
        if (aid.startsWith("A000000152")) return context.getString(R.string.nfc_reader_emv_scheme_discover);
        if (aid.startsWith("A000000065")) return context.getString(R.string.nfc_reader_emv_scheme_jcb);
        return context.getString(R.string.nfc_reader_emv_scheme_unknown);
    }

    private static String currencyName(Context context, String numeric) {
        String code = normalizeIsoNumericCode(numeric);
        String name;
        switch (code) {
            case "840": name = "USD"; break;
            case "978": name = "EUR"; break;
            case "392": name = "JPY"; break;
            case "156": name = "CNY"; break;
            case "704": name = "VND"; break;
            case "826": name = "GBP"; break;
            case "036": name = "AUD"; break;
            case "124": name = "CAD"; break;
            case "702": name = "SGD"; break;
            case "356": name = "INR"; break;
            case "764": name = "THB"; break;
            case "458": name = "MYR"; break;
            case "360": name = "IDR"; break;
            case "410": name = "KRW"; break;
            default: name = null;
        }
        return name == null ? context.getString(R.string.nfc_reader_emv_value_currency_unknown, code)
                : context.getString(R.string.nfc_reader_emv_value_currency, name, code);
    }

    private static String normalizeIsoNumericCode(String value) {
        if (value == null) return "";
        String normalized = value;
        while (normalized.length() > 3 && normalized.charAt(0) == '0') {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static String countryName(Context context, String numeric) {
        numeric = normalizeIsoNumericCode(numeric);
        int nameRes;
        switch (numeric == null ? "" : numeric) {
            case "840": nameRes = R.string.nfc_reader_emv_country_us; break;
            case "826": nameRes = R.string.nfc_reader_emv_country_gb; break;
            case "392": nameRes = R.string.nfc_reader_emv_country_jp; break;
            case "156": nameRes = R.string.nfc_reader_emv_country_cn; break;
            case "704": nameRes = R.string.nfc_reader_emv_country_vn; break;
            case "276": nameRes = R.string.nfc_reader_emv_country_de; break;
            case "250": nameRes = R.string.nfc_reader_emv_country_fr; break;
            case "356": nameRes = R.string.nfc_reader_emv_country_in; break;
            case "702": nameRes = R.string.nfc_reader_emv_country_sg; break;
            case "764": nameRes = R.string.nfc_reader_emv_country_th; break;
            case "036": nameRes = R.string.nfc_reader_emv_country_au; break;
            case "124": nameRes = R.string.nfc_reader_emv_country_ca; break;
            case "410": nameRes = R.string.nfc_reader_emv_country_kr; break;
            case "458": nameRes = R.string.nfc_reader_emv_country_my; break;
            case "360": nameRes = R.string.nfc_reader_emv_country_id; break;
            default: return context.getString(R.string.nfc_reader_emv_value_country_unknown, numeric);
        }
        return context.getString(R.string.nfc_reader_emv_value_country, context.getString(nameRes), numeric);
    }

    private static String decodePackedDigits(byte[] value, boolean keepTrackSeparator) {
        String hex = Nfc.toHex(value);
        StringBuilder decoded = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char nibble = Character.toUpperCase(hex.charAt(i));
            if (nibble >= '0' && nibble <= '9') decoded.append(nibble);
            else if (keepTrackSeparator && nibble == 'D') decoded.append('D');
            else if (nibble == 'F') {
                if (i < hex.length() - 1) decoded.append('F');
            } else decoded.append(nibble);
        }
        while (decoded.length() > 0 && decoded.charAt(decoded.length() - 1) == 'F') {
            decoded.setLength(decoded.length() - 1);
        }
        return decoded.toString();
    }

    private static String decodeCardholderName(byte[] value) {
        String raw = new String(value, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
        StringBuilder clean = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            clean.append(c == '<' ? ' ' : (Character.isISOControl(c) ? ' ' : c));
        }
        return clean.toString().replaceAll("\\s+", " ").trim();
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
        final List<DisplayField> displayFields = new ArrayList<>();
        final Set<Integer> seenTags = new HashSet<>();
        boolean displayFieldsTruncated;
        int usedChars;
        boolean truncated;

        void addDisplayField(DisplayField field) {
            if (field == null) return;
            if (field.tag >= 0) seenTags.add(field.tag);
            if (displayFields.size() >= 300) {
                displayFieldsTruncated = true;
                return;
            }
            displayFields.add(field);
        }

        boolean hasAnyTag(int... tags) {
            for (int tag : tags) if (seenTags.contains(tag)) return true;
            return false;
        }

        void addMissingEssentialFields(Context context) {
            String unavailable = context.getString(R.string.nfc_reader_emv_value_not_returned);
            String scope = context.getString(R.string.nfc_reader_emv_value_missing_scope);
            if (!hasAnyTag(0x5A, 0x57, 0x9F6B)) addDisplayField(new DisplayField(0x5A,
                    context.getString(R.string.nfc_reader_emv_group_card),
                    context.getString(R.string.nfc_reader_emv_field_pan), unavailable, scope, ""));
            if (!hasAnyTag(0x5F20)) addDisplayField(new DisplayField(0x5F20,
                    context.getString(R.string.nfc_reader_emv_group_card),
                    context.getString(R.string.nfc_reader_emv_field_cardholder), unavailable, scope, ""));
            if (!hasAnyTag(0x57, 0x9F6B)) addDisplayField(new DisplayField(0x57,
                    context.getString(R.string.nfc_reader_emv_group_card),
                    context.getString(R.string.nfc_reader_emv_field_track2), unavailable, scope, ""));
            if (!hasAnyTag(0x50, 0x9F12)) addDisplayField(new DisplayField(0x50,
                    context.getString(R.string.nfc_reader_emv_group_application),
                    context.getString(R.string.nfc_reader_emv_field_app_label), unavailable, scope, ""));
            if (!hasAnyTag(0x5F24, 0x57, 0x9F6B)) addDisplayField(new DisplayField(0x5F24,
                    context.getString(R.string.nfc_reader_emv_group_card),
                    context.getString(R.string.nfc_reader_emv_field_expiry), unavailable, scope, ""));
            if (!hasAnyTag(0x5F28)) addDisplayField(new DisplayField(0x5F28,
                    context.getString(R.string.nfc_reader_emv_group_application),
                    context.getString(R.string.nfc_reader_emv_field_country), unavailable, scope, ""));
            if (!hasAnyTag(0x5F2A, 0x9F42)) addDisplayField(new DisplayField(0x5F2A,
                    context.getString(R.string.nfc_reader_emv_group_application),
                    context.getString(R.string.nfc_reader_emv_field_transaction_currency), unavailable, scope, ""));
        }

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

    static final class DisplayField {
        final int tag;
        final String category;
        final String label;
        final String value;
        final String detail;
        final String rawHex;

        DisplayField(int tag, String category, String label, String value, String detail, String rawHex) {
            this.tag = tag;
            this.category = category;
            this.label = label;
            this.value = value;
            this.detail = detail;
            this.rawHex = rawHex;
        }
    }

    static final class Report {
        final List<ApplicationResult> applications;
        final boolean truncated;
        final String error;
        final List<String> fullDataLines;
        final List<DisplayField> displayFields;
        final boolean displayFieldsTruncated;
        final boolean fullDataTruncated;
        final boolean includesSensitiveData;

        Report(List<ApplicationResult> applications, boolean truncated, List<String> fullDataLines,
               List<DisplayField> displayFields, boolean displayFieldsTruncated,
               boolean fullDataTruncated, boolean includesSensitiveData) {
            this.applications = applications;
            this.truncated = truncated;
            this.error = null;
            this.fullDataLines = fullDataLines;
            this.displayFields = displayFields == null ? new ArrayList<>() : new ArrayList<>(displayFields);
            this.displayFieldsTruncated = displayFieldsTruncated;
            this.fullDataTruncated = fullDataTruncated;
            this.includesSensitiveData = includesSensitiveData;
        }

        private Report(String error) {
            this.applications = new ArrayList<>();
            this.truncated = false;
            this.error = error;
            this.fullDataLines = null;
            this.displayFields = new ArrayList<>();
            this.displayFieldsTruncated = false;
            this.fullDataTruncated = false;
            this.includesSensitiveData = false;
        }

        static Report failure(String error) { return new Report(error); }

        String toTechnicalText(Context context) {
            StringBuilder out = new StringBuilder();
            out.append(context.getString(R.string.nfc_reader_emv_technical_header)).append('\n');
            if (fullDataLines != null) {
                for (String line : fullDataLines) out.append(line).append('\n');
            }
            if (fullDataTruncated) out.append(context.getString(R.string.nfc_reader_emv_full_data_truncated)).append('\n');
            return out.toString().trim();
        }

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
