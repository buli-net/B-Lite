package wallet.nfc;

import android.nfc.Tag;
import android.nfc.tech.IsoDep;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * NFC Forum Type 4 Tag NDEF driver over ISO-DEP / ISO 7816-4.
 *
 * This driver only accesses the standardized NDEF application, Capability Container and NDEF
 * file. It never selects payment applications, proprietary files, or performs authentication.
 * Writes are allowed only when the CC explicitly advertises free write access (0x00).
 */
final class NfcType4TagDriver {
    private static final byte[] NDEF_AID = hex("D2760000850101");
    private static final int MAX_CC_LENGTH = 256;
    private static final int MAX_NDEF_FILE_SIZE = 0x7FFF;
    private static final int MAX_CHUNK = 240;

    private NfcType4TagDriver() { }

    static final class Inspection {
        boolean ndefApplicationFound;
        boolean ntag424Dna;
        String ntagVersion = "";
        int mappingVersion;
        int mle;
        int mlc;
        int ndefFileId;
        int ndefFileSize;
        int nlenSize;
        int readAccess = 0xFF;
        int writeAccess = 0xFF;
        int ccLength;
        String error = "";

        boolean canRead() { return ndefApplicationFound && readAccess == 0x00 && mle > 0; }
        boolean canWrite() { return ndefApplicationFound && writeAccess == 0x00 && mlc > 0; }
        int capacity() { return Math.max(0, ndefFileSize - nlenSize); }
        String fileIdHex() { return String.format(java.util.Locale.ROOT, "%04X", ndefFileId); }
    }

    static final class ReadResult {
        final Inspection inspection;
        final byte[] message;
        ReadResult(Inspection inspection, byte[] message) {
            this.inspection = inspection;
            this.message = message;
        }
    }

    static final class OperationResult {
        final boolean success;
        final String reason;
        OperationResult(boolean success, String reason) {
            this.success = success;
            this.reason = reason == null ? "" : reason;
        }
    }

    static Inspection inspect(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) return null;
        boolean connected = false;
        try {
            isoDep.setTimeout(4000);
            isoDep.connect();
            connected = true;
            Inspection result = new Inspection();
            discoverNdefApplication(isoDep, result);
            if (result.ndefApplicationFound) {
                byte[] version = readNtag424Version(isoDep);
                if (version != null) {
                    result.ntag424Dna = true;
                    result.ntagVersion = String.format(java.util.Locale.ROOT,
                            "HW %02X.%02X / SW %02X.%02X", version[3] & 0xFF, version[4] & 0xFF,
                            version[10] & 0xFF, version[11] & 0xFF);
                    wipe(version);
                }
            }
            return result.ndefApplicationFound ? result : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connected) try { isoDep.close(); } catch (IOException ignored) { }
        }
    }

    static ReadResult read(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) return null;
        boolean connected = false;
        Inspection inspection = new Inspection();
        byte[] nlenBytes = null;
        try {
            isoDep.setTimeout(4000);
            isoDep.connect();
            connected = true;
            discoverNdefApplication(isoDep, inspection);
            if (inspection.ndefApplicationFound) {
                byte[] version = readNtag424Version(isoDep);
                if (version != null) {
                    inspection.ntag424Dna = true;
                    inspection.ntagVersion = String.format(java.util.Locale.ROOT,
                            "HW %02X.%02X / SW %02X.%02X", version[3] & 0xFF, version[4] & 0xFF,
                            version[10] & 0xFF, version[11] & 0xFF);
                    wipe(version);
                }
            }
            if (!inspection.ndefApplicationFound || !inspection.canRead()) {
                return new ReadResult(inspection, null);
            }
            if (!selectFile(isoDep, inspection.ndefFileId)) {
                inspection.error = "NDEF file selection failed";
                return new ReadResult(inspection, null);
            }
            nlenBytes = readExact(isoDep, 0, inspection.nlenSize, inspection.mle);
            long length = readUnsigned(nlenBytes);
            if (length < 0 || length > inspection.capacity()) {
                inspection.error = "NDEF length exceeds the file capacity";
                return new ReadResult(inspection, null);
            }
            byte[] payload = readExact(isoDep, inspection.nlenSize, (int) length, inspection.mle);
            return new ReadResult(inspection, payload);
        } catch (IOException | RuntimeException e) {
            inspection.error = safeError(e);
            return new ReadResult(inspection, null);
        } finally {
            wipe(nlenBytes);
            if (connected) try { isoDep.close(); } catch (IOException ignored) { }
        }
    }

    static OperationResult write(Tag tag, byte[] ndefMessage) {
        if (ndefMessage == null) return new OperationResult(false, "No NDEF data supplied");
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) return new OperationResult(false, "ISO-DEP is unavailable");
        boolean connected = false;
        Inspection info = new Inspection();
        byte[] verify = null;
        try {
            isoDep.setTimeout(5000);
            isoDep.connect();
            connected = true;
            discoverNdefApplication(isoDep, info);
            if (!info.ndefApplicationFound) return new OperationResult(false, "NDEF Type 4 application not found");
            if (!info.canWrite()) return new OperationResult(false, "NDEF write access is not advertised as free in the CC");
            if (!info.canRead()) return new OperationResult(false, "NDEF read access is not advertised as free; read-back verification is unavailable");
            if (ndefMessage.length > info.capacity()) return new OperationResult(false, "NDEF message exceeds the advertised file capacity");
            if (!selectFile(isoDep, info.ndefFileId)) return new OperationResult(false, "NDEF file selection failed");
            updateExact(isoDep, 0, new byte[info.nlenSize], info.mlc);
            updateChunks(isoDep, info.nlenSize, ndefMessage, info.mlc);
            updateExact(isoDep, 0, unsignedBytes(ndefMessage.length, info.nlenSize), info.mlc);
            verify = readMessageOnConnection(isoDep, info);
            if (!Arrays.equals(ndefMessage, verify)) return new OperationResult(false, "NDEF write completed but read-back verification did not match");
            return new OperationResult(true, "NDEF Type 4 file written and verified");
        } catch (IOException | RuntimeException e) {
            return new OperationResult(false, safeError(e));
        } finally {
            wipe(verify);
            if (connected) try { isoDep.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Clears the current NDEF message by setting NLEN to zero, then overwrites the previous
     * payload bytes with zeroes when free write access is explicitly advertised. This is a
     * logical NDEF erase, not a guarantee of physical flash sanitization or chip factory reset.
     */
    static OperationResult erase(Tag tag) {
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) return new OperationResult(false, "ISO-DEP is unavailable");
        boolean connected = false;
        Inspection info = new Inspection();
        byte[] nlenBytes = null;
        byte[] zeros = null;
        try {
            isoDep.setTimeout(5000);
            isoDep.connect();
            connected = true;
            discoverNdefApplication(isoDep, info);
            if (!info.ndefApplicationFound) return new OperationResult(false, "NDEF Type 4 application not found");
            if (!info.canWrite()) return new OperationResult(false, "NDEF write access is not advertised as free in the CC");
            if (!info.canRead()) return new OperationResult(false, "NDEF read access is not advertised as free; erase size cannot be safely determined");
            if (!selectFile(isoDep, info.ndefFileId)) return new OperationResult(false, "NDEF file selection failed");
            nlenBytes = readExact(isoDep, 0, info.nlenSize, info.mle);
            long oldLength = readUnsigned(nlenBytes);
            if (oldLength > info.capacity()) return new OperationResult(false, "Existing NDEF length is invalid");
            updateExact(isoDep, 0, new byte[info.nlenSize], info.mlc);
            int remaining = (int) oldLength;
            int offset = info.nlenSize;
            while (remaining > 0) {
                int count = Math.min(remaining, writeChunkLimit(isoDep, info.mlc));
                zeros = new byte[count];
                updateExact(isoDep, offset, zeros, info.mlc);
                wipe(zeros);
                zeros = null;
                offset += count;
                remaining -= count;
            }
            byte[] check = readExact(isoDep, 0, info.nlenSize, info.mle);
            try {
                if (readUnsigned(check) != 0) return new OperationResult(false, "NDEF length did not clear");
            } finally { wipe(check); }
            if (oldLength > 0) {
                byte[] verifyZeros = readExact(isoDep, info.nlenSize, (int) oldLength, info.mle);
                try {
                    for (byte value : verifyZeros) if (value != 0) return new OperationResult(false, "Some previous NDEF payload bytes could not be overwritten");
                } finally { wipe(verifyZeros); }
            }
            return new OperationResult(true, "NDEF message cleared; chip configuration and protected files were not changed");
        } catch (IOException | RuntimeException e) {
            return new OperationResult(false, safeError(e));
        } finally {
            wipe(nlenBytes);
            wipe(zeros);
            if (connected) try { isoDep.close(); } catch (IOException ignored) { }
        }
    }

    private static void discoverNdefApplication(IsoDep isoDep, Inspection out) throws IOException {
        byte[] select = selectByName(NDEF_AID);
        byte[] response = exchange(isoDep, select);
        wipe(select);
        if (!is9000(response)) { wipe(response); return; }
        wipe(response);
        if (!selectFile(isoDep, 0xE103)) return;
        byte[] prefix = readExact(isoDep, 0, 2, 256);
        int ccLength = ((prefix[0] & 0xFF) << 8) | (prefix[1] & 0xFF);
        wipe(prefix);
        if (ccLength < 7 || ccLength > MAX_CC_LENGTH) {
            out.error = "Invalid Capability Container length";
            return;
        }
        byte[] cc = readExact(isoDep, 0, ccLength, 256);
        try {
            out.ccLength = ccLength;
            out.mappingVersion = cc[2] & 0xFF;
            int mappingMajor = (out.mappingVersion >>> 4) & 0x0F;
            if (mappingMajor < 1 || mappingMajor > 3) throw new IOException("Unsupported Type 4 mapping version");
            out.mle = ((cc[3] & 0xFF) << 8) | (cc[4] & 0xFF);
            out.mlc = ((cc[5] & 0xFF) << 8) | (cc[6] & 0xFF);
            int position = 7;
            while (position < ccLength) {
                int tag = cc[position++] & 0xFF;
                if (tag == 0x00) continue;
                if (tag == 0xFE) break;
                if (position >= ccLength) throw new IOException("Truncated CC TLV");
                int length = cc[position++] & 0xFF;
                if (length == 0xFF) {
                    if (position + 2 > ccLength) throw new IOException("Truncated extended CC TLV");
                    length = ((cc[position] & 0xFF) << 8) | (cc[position + 1] & 0xFF);
                    position += 2;
                }
                if (length < 0 || position + length > ccLength) throw new IOException("Invalid CC TLV length");
                if ((tag == 0x04 && length == 6) || (tag == 0x06 && length == 8)) {
                    int fileId = ((cc[position] & 0xFF) << 8) | (cc[position + 1] & 0xFF);
                    long fileSize = tag == 0x04
                            ? (((cc[position + 2] & 0xFF) << 8) | (cc[position + 3] & 0xFF))
                            : (((long) (cc[position + 2] & 0xFF) << 24)
                                | ((long) (cc[position + 3] & 0xFF) << 16)
                                | ((long) (cc[position + 4] & 0xFF) << 8)
                                | (long) (cc[position + 5] & 0xFF));
                    int readIndex = tag == 0x04 ? position + 4 : position + 6;
                    int writeIndex = readIndex + 1;
                    if (fileId != 0 && fileId != 0xFFFF && fileSize >= 2 && fileSize <= MAX_NDEF_FILE_SIZE) {
                        out.ndefFileId = fileId;
                        out.ndefFileSize = (int) fileSize;
                        out.nlenSize = tag == 0x04 ? 2 : 4;
                        out.readAccess = cc[readIndex] & 0xFF;
                        out.writeAccess = cc[writeIndex] & 0xFF;
                    }
                }
                position += length;
            }
            out.ndefApplicationFound = out.ndefFileId != 0 && out.nlenSize > 0;
            if (!out.ndefApplicationFound) out.error = "No supported NDEF File Control TLV in CC";
            if (out.mle <= 0 || out.mlc <= 0) out.error = "Invalid MLe/MLc in CC";
        } finally { wipe(cc); }
    }

    private static byte[] readNtag424Version(IsoDep isoDep) {
        byte[] part1 = null, part2 = null, part3 = null;
        try {
            // Re-select the NDEF application DF before the manufacturer's public GET_VERSION command.
            byte[] selectApplication = selectByName(NDEF_AID);
            byte[] selected = exchange(isoDep, selectApplication);
            wipe(selectApplication);
            boolean appSelected = is9000(selected);
            wipe(selected);
            if (!appSelected) return null;
            byte[] first = exchange(isoDep, new byte[] {(byte) 0x90, 0x60, 0x00, 0x00, 0x00});
            if (!hasStatus(first, 0x91, 0xAF) || first.length != 9) {
                if (hasStatus(first, 0x91, 0xAF)) {
                    wipe(first);
                    for (int i = 0; i < 2; i++) {
                        byte[] drain = exchange(isoDep, new byte[] {(byte) 0x90, (byte) 0xAF, 0x00, 0x00, 0x00});
                        boolean done = hasStatus(drain, 0x91, 0x00);
                        wipe(drain);
                        if (done) break;
                    }
                } else wipe(first);
                return null;
            }
            part1 = body(first); wipe(first);
            byte[] second = exchange(isoDep, new byte[] {(byte) 0x90, (byte) 0xAF, 0x00, 0x00, 0x00});
            if (!hasStatus(second, 0x91, 0xAF) || second.length != 9) { wipe(second); return null; }
            part2 = body(second); wipe(second);
            byte[] third = exchange(isoDep, new byte[] {(byte) 0x90, (byte) 0xAF, 0x00, 0x00, 0x00});
            if (!hasStatus(third, 0x91, 0x00)) { wipe(third); return null; }
            part3 = body(third); wipe(third);
            // NXP NTAG424 DNA: NXP vendor 04h, NTAG HW type 04h, SW type 04h/subtype 02h.
            if ((part1[0] & 0xFF) != 0x04 || (part1[1] & 0xFF) != 0x04
                    || (part2[0] & 0xFF) != 0x04 || (part2[1] & 0xFF) != 0x04
                    || (part2[2] & 0xFF) != 0x02 || (part3.length < 13)) return null;
            byte[] combined = new byte[part1.length + part2.length + part3.length];
            System.arraycopy(part1, 0, combined, 0, part1.length);
            System.arraycopy(part2, 0, combined, part1.length, part2.length);
            System.arraycopy(part3, 0, combined, part1.length + part2.length, part3.length);
            return combined;
        } catch (IOException | RuntimeException ignored) {
            return null;
        } finally { wipe(part1); wipe(part2); wipe(part3); }
    }

    private static byte[] readMessageOnConnection(IsoDep isoDep, Inspection info) throws IOException {
        if (!selectFile(isoDep, info.ndefFileId)) throw new IOException("NDEF file selection failed during verification");
        byte[] nlen = readExact(isoDep, 0, info.nlenSize, info.mle);
        try {
            long length = readUnsigned(nlen);
            if (length > info.capacity()) throw new IOException("NDEF length exceeds capacity during verification");
            return readExact(isoDep, info.nlenSize, (int) length, info.mle);
        } finally { wipe(nlen); }
    }

    private static boolean selectFile(IsoDep isoDep, int fileId) throws IOException {
        byte[] command = new byte[] {0x00, (byte) 0xA4, 0x00, 0x0C, 0x02,
                (byte) (fileId >>> 8), (byte) fileId};
        byte[] response = exchange(isoDep, command);
        boolean success = is9000(response);
        wipe(response);
        return success;
    }

    private static byte[] selectByName(byte[] aid) {
        byte[] command = new byte[6 + aid.length];
        command[0] = 0x00; command[1] = (byte) 0xA4; command[2] = 0x04;
        command[3] = 0x00; command[4] = (byte) aid.length;
        System.arraycopy(aid, 0, command, 5, aid.length);
        command[command.length - 1] = 0x00;
        return command;
    }

    private static byte[] readExact(IsoDep isoDep, int offset, int length, int mle) throws IOException {
        if (length < 0 || offset < 0 || (long) offset + length > 0x8000L) throw new IOException("Type 4 file offset/length is outside the supported range");
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        int position = offset;
        int remaining = length;
        while (remaining > 0) {
            int chunk = Math.min(remaining, Math.min(MAX_CHUNK, Math.max(1, mle)));
            chunk = Math.min(chunk, Math.max(1, isoDep.getMaxTransceiveLength() - 2));
            byte[] command = new byte[] {0x00, (byte) 0xB0,
                    (byte) (position >>> 8), (byte) position, (byte) chunk};
            byte[] response = exchange(isoDep, command);
            wipe(command);
            if (!is9000(response)) {
                if (response.length >= 2 && (response[response.length - 2] & 0xFF) == 0x6C) {
                    int corrected = response[response.length - 1] & 0xFF;
                    wipe(response);
                    if (corrected == 0) corrected = 256;
                    chunk = Math.min(remaining, Math.min(corrected, MAX_CHUNK));
                    command = new byte[] {0x00, (byte) 0xB0,
                            (byte) (position >>> 8), (byte) position, (byte) chunk};
                    response = exchange(isoDep, command);
                    wipe(command);
                }
                if (!is9000(response)) {
                    String reason = "READ BINARY failed with SW " + status(response);
                    wipe(response);
                    throw new IOException(reason);
                }
            }
            int dataLength = response.length - 2;
            if (dataLength <= 0 || dataLength > remaining) {
                wipe(response);
                throw new IOException("Unexpected READ BINARY response length");
            }
            output.write(response, 0, dataLength);
            position += dataLength;
            remaining -= dataLength;
            wipe(response);
        }
        return output.toByteArray();
    }

    private static void updateChunks(IsoDep isoDep, int offset, byte[] data, int mlc) throws IOException {
        int position = offset;
        int sourceOffset = 0;
        while (sourceOffset < data.length) {
            int count = Math.min(data.length - sourceOffset, writeChunkLimit(isoDep, mlc));
            byte[] chunk = Arrays.copyOfRange(data, sourceOffset, sourceOffset + count);
            try { updateExact(isoDep, position, chunk, mlc); }
            finally { wipe(chunk); }
            position += count;
            sourceOffset += count;
        }
    }

    private static int writeChunkLimit(IsoDep isoDep, int mlc) throws IOException {
        int limit = Math.min(MAX_CHUNK, Math.min(255, mlc));
        limit = Math.min(limit, isoDep.getMaxTransceiveLength() - 6);
        if (limit <= 0) throw new IOException("ISO-DEP transceive size is too small for UPDATE BINARY");
        return limit;
    }

    private static void updateExact(IsoDep isoDep, int offset, byte[] data, int mlc) throws IOException {
        if (offset < 0 || offset + data.length > 0x8000 || data.length > writeChunkLimit(isoDep, mlc)) {
            throw new IOException("UPDATE BINARY offset/length exceeds the supported range");
        }
        byte[] command = new byte[5 + data.length];
        command[0] = 0x00; command[1] = (byte) 0xD6;
        command[2] = (byte) (offset >>> 8); command[3] = (byte) offset;
        command[4] = (byte) data.length;
        System.arraycopy(data, 0, command, 5, data.length);
        byte[] response = exchange(isoDep, command);
        wipe(command);
        if (!is9000(response)) {
            String reason = "UPDATE BINARY failed with SW " + status(response);
            wipe(response);
            throw new IOException(reason);
        }
        wipe(response);
    }

    private static byte[] exchange(IsoDep isoDep, byte[] command) throws IOException {
        byte[] response = isoDep.transceive(command);
        if (response == null || response.length < 2) throw new IOException("APDU response is too short");
        return response;
    }

    private static boolean is9000(byte[] response) { return hasStatus(response, 0x90, 0x00); }
    private static boolean hasStatus(byte[] response, int sw1, int sw2) {
        return response != null && response.length >= 2
                && (response[response.length - 2] & 0xFF) == sw1
                && (response[response.length - 1] & 0xFF) == sw2;
    }
    private static String status(byte[] response) {
        if (response == null || response.length < 2) return "????";
        return String.format(java.util.Locale.ROOT, "%02X%02X",
                response[response.length - 2] & 0xFF, response[response.length - 1] & 0xFF);
    }
    private static byte[] body(byte[] response) { return Arrays.copyOf(response, response.length - 2); }
    private static long readUnsigned(byte[] bytes) {
        long value = 0;
        for (byte b : bytes) value = (value << 8) | (b & 0xFFL);
        return value;
    }
    private static byte[] unsignedBytes(long value, int length) {
        byte[] result = new byte[length];
        for (int i = length - 1; i >= 0; i--) { result[i] = (byte) value; value >>>= 8; }
        return result;
    }
    private static String safeError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return "Type 4 operation failed";
        // APDU status and protocol errors are useful diagnostics; never include card payloads.
        return message.length() > 120 ? message.substring(0, 120) : message;
    }
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return result;
    }
    private static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
}
