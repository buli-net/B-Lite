package wallet.tools;

import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.security.SecureRandom;

/**
 * Chip-specific NFC security operations. This class deliberately performs no key guessing.
 * NTAG210/212/213/215/216 and MIFARE Ultralight EV1 use their documented 32-bit PWD_AUTH mechanism. MIFARE Classic uses caller-supplied
 * sector keys; those keys are not a universal card password.
 */
public final class NfcHardwareSecurity {
    private static final byte CMD_GET_VERSION = 0x60;
    private static final byte CMD_READ = 0x30;
    private static final byte CMD_WRITE = (byte) 0xA2;
    private static final byte CMD_PWD_AUTH = 0x1B;
    private static final SecureRandom PACK_RANDOM = new SecureRandom();
    private static final byte[] DEFAULT_NTAG_PASSWORD = new byte[] {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
    };
    private static final byte[] DEFAULT_NTAG_PACK = new byte[] { 0x00, 0x00 };

    public enum Family {
        NTAG210, NTAG212, NTAG213, NTAG215, NTAG216,
        MIFARE_ULTRALIGHT_EV1_48, MIFARE_ULTRALIGHT_EV1_128,
        MIFARE_CLASSIC, MIFARE_ULTRALIGHT_OTHER,
        ISO_DEP, NFC_V, NFC_F, NFC_A, NFC_B, NDEF_ONLY, UNKNOWN
    }

    public enum NtagAction { SET, CHANGE, REMOVE }

    public static final class TagInfo {
        public final Family family;
        public final String name;
        public final String details;
        public final boolean hardwarePasswordImplemented;

        private TagInfo(Family family, String name, String details, boolean implemented) {
            this.family = family;
            this.name = name;
            this.details = details;
            this.hardwarePasswordImplemented = implemented;
        }
    }

    private static final class NtagLayout {
        final Family family;
        final String name;
        final int config0Page;
        final int accessPage;
        final int passwordPage;
        final int packPage;
        final int lastUserPage;

        NtagLayout(Family family, String name, int config0Page) {
            this(family, name, config0Page, config0Page - 2);
        }

        NtagLayout(Family family, String name, int config0Page, int lastUserPage) {
            this.family = family;
            this.name = name;
            this.config0Page = config0Page;
            this.accessPage = config0Page + 1;
            this.passwordPage = config0Page + 2;
            this.packPage = config0Page + 3;
            this.lastUserPage = lastUserPage;
        }
    }

    private static final class NtagConfig {
        final NtagLayout layout;
        final byte[] config0;
        final byte[] accessPage;
        final int auth0;
        final int access;

        NtagConfig(NtagLayout layout, byte[] config0, byte[] accessPage) {
            this.layout = layout;
            this.config0 = config0;
            this.accessPage = accessPage;
            this.auth0 = config0[3] & 0xFF;
            this.access = accessPage[0] & 0xFF;
        }

        boolean protectedFromRead() {
            return isProtectionActive() && (access & 0x80) != 0;
        }

        boolean isProtectionActive() {
            return auth0 != 0xFF && auth0 <= layout.lastUserPage;
        }
    }

    private NfcHardwareSecurity() { }

    /** Best-effort identification; a technology family is never presented as an exact chip model. */
    public static TagInfo identify(Tag tag) {
        if (tag == null) return new TagInfo(Family.UNKNOWN, "Unknown NFC tag", "No tag object.", false);
        if (MifareClassic.get(tag) != null) {
            MifareClassic classic = MifareClassic.get(tag);
            String size = classic == null ? "" : String.format(Locale.ROOT,
                    "%d sectors, %d bytes", classic.getSectorCount(), classic.getSize());
            return new TagInfo(Family.MIFARE_CLASSIC, "MIFARE Classic-compatible",
                    "Android exposes MIFARE Classic. " + size
                            + ". Key management is sector-based, requires the current key, and depends on access bits.", true);
        }
        try {
            NtagLayout layout = identifyNtagLayout(tag);
            if (layout != null) {
                return new TagInfo(layout.family, layout.name,
                        "NXP GET_VERSION response matched. Supports a 32-bit hardware password, configurable read/write protection and a 16-bit PACK acknowledgement.", true);
            }
        } catch (IOException ignored) {
            // Fall through to generic technology reporting.
        }
        String[] techs = tag.getTechList();
        if (contains(techs, "MifareUltralight")) {
            return new TagInfo(Family.MIFARE_ULTRALIGHT_OTHER, "MIFARE Ultralight-family / Type 2 candidate",
                    "The tag exposes MifareUltralight, but GET_VERSION did not identify a supported NTAG210/212/213/215/216 or MIFARE Ultralight EV1 model. No password command will be guessed.", false);
        }
        if (contains(techs, "IsoDep")) {
            return new TagInfo(Family.ISO_DEP, "ISO-DEP (ISO/IEC 14443-4)",
                    "ISO-DEP identifies a transport protocol, not a single chip. DESFire and other products need their own authenticated protocol driver.", false);
        }
        if (contains(techs, "NfcV")) return new TagInfo(Family.NFC_V, "NFC-V / ISO 15693",
                "This technology can contain different chip families. No generic password operation is safe without a specific chip driver.", false);
        if (contains(techs, "NfcF")) return new TagInfo(Family.NFC_F, "NFC-F / FeliCa",
                "FeliCa security is service/card-specific and requires the supported chip's authentication protocol.", false);
        if (contains(techs, "NfcB")) return new TagInfo(Family.NFC_B, "NFC-B / ISO 14443-B",
                "This is a technology family, not a unique model. No generic password command is sent.", false);
        if (contains(techs, "NfcA")) return new TagInfo(Family.NFC_A, "NFC-A / ISO 14443-A",
                "This is a technology family only. Exact chip identification is required before password operations.", false);
        if (contains(techs, "Ndef")) return new TagInfo(Family.NDEF_ONLY, "NDEF tag (chip model unknown)",
                "NDEF support does not reveal the security chip/model. Hardware password management is not enabled for unidentified tags.", false);
        return new TagInfo(Family.UNKNOWN, "Unknown NFC tag", "The chip model could not be identified with the available Android technologies.", false);
    }

    public static boolean supportsHardwarePassword(Tag tag) {
        try { return identifyNtagLayout(tag) != null; }
        catch (IOException | RuntimeException e) { return false; }
    }

    /** True only for a positively identified supported Type 2 chip whose user-memory reads require PWD_AUTH. */
    public static boolean requiresPasswordForNdefRead(Tag tag) {
        try {
            return supportsHardwarePassword(tag) && appearsPasswordReadProtected(tag);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True only for a positively identified supported Type 2 chip with active AUTH0 write protection. */
    public static boolean requiresPasswordForNdefWrite(Tag tag) {
        try {
            return supportsHardwarePassword(tag) && appearsPasswordWriteProtected(tag);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Changes hardware password settings on positively identified NTAG210/212/213/215/216 and MIFARE Ultralight EV1 tags. */
    public static String updateHardwarePassword(Tag tag, NtagAction action,
                                            String currentPasswordHex, String newPasswordHex) throws IOException {
        if (tag == null) throw new IOException("No NFC tag is available.");
        byte[] current = currentPasswordHex == null || currentPasswordHex.trim().isEmpty()
                ? null : parseHex(currentPasswordHex, 4, "Current password");
        byte[] next;
        try {
            next = newPasswordHex == null || newPasswordHex.trim().isEmpty()
                    ? null : parseHex(newPasswordHex, 4, "New password");
        } catch (IOException e) {
            wipe(current);
            throw e;
        }
        if (action != NtagAction.REMOVE && next == null) {
            wipe(current);
            wipe(next);
            throw new IOException("Enter an 8-character hexadecimal password.");
        }
        if (action != NtagAction.REMOVE && Arrays.equals(next, DEFAULT_NTAG_PASSWORD)) {
            wipe(current);
            wipe(next);
            throw new IOException("The factory default password FFFFFFFF is not allowed as a new password. Choose a non-default value.");
        }
        if (action == NtagAction.CHANGE && current != null && next != null && Arrays.equals(current, next)) {
            wipe(current);
            wipe(next);
            throw new IOException("The new password must differ from the current hardware password.");
        }

        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) {
            wipe(current);
            wipe(next);
            throw new IOException("Android does not expose MifareUltralight commands for this tag.");
        }
        byte[] configuredPack = null;
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) throw new IOException("The tag is not positively identified as a supported NTAG210/212/213/215/216 or MIFARE Ultralight EV1 model.");

            byte[] config0 = null;
            byte[] accessPage = null;
            IOException readError = null;
            try {
                byte[][] configPages = readNtagConfig(ul, layout);
                config0 = configPages[0];
                accessPage = configPages[1];
            } catch (IOException e) {
                readError = e;
            }

            boolean suppliedAuth = false;
            if (readError != null || config0 == null || accessPage == null) {
                if (current == null) {
                    throw new IOException("The configuration is not readable. The tag may already be password-protected; enter its current hardware password and retry.", readError);
                }
                authenticate(ul, current);
                suppliedAuth = true;
                byte[][] configPages = readNtagConfig(ul, layout);
                config0 = configPages[0];
                accessPage = configPages[1];
            }
            NtagConfig config = new NtagConfig(layout, config0, accessPage);
            // CFGLOCK permanently blocks writing CFG0/CFG1 after a power cycle. Password
            // rotation can still use PWD/PACK, but changing or removing the protection scope
            // cannot be done safely while CFGLOCK is set.
            boolean configLocked = (config.access & 0x40) != 0;
            if (configLocked && (action != NtagAction.CHANGE || !config.isProtectionActive())) {
                throw new IOException("This Type 2 tag has CFGLOCK enabled. Its access configuration is permanently locked, so protection cannot be enabled or removed by this app.");
            }
            if (config.isProtectionActive() && !suppliedAuth) {
                if (current == null) throw new IOException("This tag already has an active password protection. Enter the current password.");
                authenticate(ul, current);
                suppliedAuth = true;
            } else if (suppliedAuth && current != null) {
                // Authentication was already verified above; do not send duplicate attempts.
            }

            if (action == NtagAction.CHANGE && !config.isProtectionActive()) {
                throw new IOException("Password protection is not active. Use Set password first.");
            }
            if (action == NtagAction.REMOVE && !config.isProtectionActive()) {
                throw new IOException("This tag does not report active hardware password protection.");
            }
            if (config.isProtectionActive() && current == null) {
                throw new IOException("The current hardware password is required.");
            }
            if (config.isProtectionActive() && !suppliedAuth) {
                throw new IOException("The current hardware password was not authenticated.");
            }

            if (action == NtagAction.REMOVE) {
                // Disable protection first so an interrupted reset cannot leave the user locked out.
                byte[] openConfig0 = Arrays.copyOf(config.config0, 4);
                openConfig0[3] = (byte) 0xFF;
                writePage(ul, layout.config0Page, openConfig0);
                byte[] openAccess = Arrays.copyOf(config.accessPage, 4);
                openAccess[0] = (byte) (config.access & ~0x80);
                writePage(ul, layout.accessPage, openAccess);
                writePage(ul, layout.passwordPage, DEFAULT_NTAG_PASSWORD);
                writePage(ul, layout.packPage, new byte[] { 0, 0, 0, 0 });
                return "Removed Type 2 read/write password protection and restored the chip's default PWD/PACK values. Verify by scanning the tag again.";
            }

            // PWD and PACK are never readable. Use a per-operation random PACK rather than the
            // public factory value, and write it before PWD so a PACK-write failure cannot
            // unexpectedly replace the currently usable password.
            configuredPack = new byte[2];
            PACK_RANDOM.nextBytes(configuredPack);
            byte[] packPage = new byte[] { configuredPack[0], configuredPack[1], 0, 0 };
            try { writePage(ul, layout.packPage, packPage); }
            finally { wipe(packPage); }
            writePage(ul, layout.passwordPage, next);
            if (!(action == NtagAction.CHANGE && configLocked)) {
                byte[] protectedAccess = Arrays.copyOf(config.accessPage, 4);
                protectedAccess[0] = (byte) (config.access | 0x80); // PROT=1: read + write
                writePage(ul, layout.accessPage, protectedAccess);
                byte[] protectedConfig0 = Arrays.copyOf(config.config0, 4);
                protectedConfig0[3] = 0x04; // protect user memory from the first NDEF/user page
                writePage(ul, layout.config0Page, protectedConfig0); // AUTH0 last
            }

            // Verify both password acceptance and the PACK configured above.
            authenticateExpectedPack(ul, next, configuredPack);
            if (action == NtagAction.CHANGE && configLocked) {
                return "Hardware password changed and verified. CFGLOCK prevents changing the existing protection scope, so the chip's current AUTH0/PROT settings were preserved.";
            }
            return (action == NtagAction.SET ? layout.name + " hardware password set" : layout.name + " hardware password changed")
                    + ". Read and write protection is enabled from the first user page onward. Keep the password safe; this is a 32-bit convenience password, not strong cryptographic authentication.";
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
            wipe(current);
            wipe(next);
            wipe(configuredPack);
        }
    }

    /** Change a caller-known MIFARE Classic key on sectors which authenticate with that key.
     * Both Key A and Key B are updated to the new key while preserving validated access bytes. */
    public static String changeMifareClassicKeys(Tag tag, String currentKeyHex, String newKeyHex) throws IOException {
        byte[] current = parseHex(currentKeyHex, 6, "Current MIFARE Classic key");
        byte[] next;
        try {
            next = parseHex(newKeyHex, 6, "New MIFARE Classic key");
        } catch (IOException e) {
            wipe(current);
            throw e;
        }
        if (Arrays.equals(current, next)) {
            wipe(current);
            wipe(next);
            throw new IOException("The new sector key must differ from the current key.");
        }
        MifareClassic classic = MifareClassic.get(tag);
        if (classic == null) {
            wipe(current);
            wipe(next);
            throw new IOException("This tag is not exposed as MIFARE Classic by Android.");
        }
        int changed = 0;
        int skipped = 0;
        int locked = 0;
        try {
            classic.connect();
            for (int sector = 0; sector < classic.getSectorCount(); sector++) {
                boolean authA = false;
                boolean authB = false;
                try { authA = classic.authenticateSectorWithKeyA(sector, current); } catch (IOException ignored) { }
                if (!authA) {
                    try { authB = classic.authenticateSectorWithKeyB(sector, current); } catch (IOException ignored) { }
                }
                if (!authA && !authB) { skipped++; continue; }
                int trailerBlock = classic.sectorToBlock(sector) + classic.getBlockCountInSector(sector) - 1;
                byte[] trailer = null;
                byte[] updated = null;
                try {
                    trailer = classic.readBlock(trailerBlock);
                    if (trailer == null || trailer.length != 16 || !validAccessBytes(trailer)) {
                        locked++;
                        continue;
                    }
                    updated = Arrays.copyOf(trailer, 16);
                    System.arraycopy(next, 0, updated, 0, 6);
                    System.arraycopy(next, 0, updated, 10, 6);
                    classic.writeBlock(trailerBlock, updated);
                    boolean verified = classic.authenticateSectorWithKeyA(sector, next)
                            || classic.authenticateSectorWithKeyB(sector, next);
                    if (verified) changed++; else locked++;
                } catch (IOException | RuntimeException e) {
                    locked++;
                } finally {
                    wipe(updated);
                    wipe(trailer);
                }
            }
        } finally {
            try { classic.close(); } catch (IOException ignored) { }
            wipe(current);
            wipe(next);
        }
        return "MIFARE Classic sector-key change finished: " + changed + " sector(s) changed and verified, "
                + skipped + " sector(s) did not authenticate with the supplied current key, "
                + locked + " sector(s) could not be changed/verified. A key is not a global card password; review all sectors individually.";
    }

    /** Report whether NDEF data appears read-protected; false means not established as protected. */
    public static boolean appearsPasswordReadProtected(Tag tag) {
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) return false;
            byte[][] configPages = readNtagConfig(ul, layout);
            return new NtagConfig(layout, configPages[0], configPages[1]).protectedFromRead();
        } catch (IOException e) {
            // We have already positively identified a supported password-capable Type 2 chip. Its AUTH0 setting can make the
            // configuration read itself require authentication, so request a password rather
            // than treating an inaccessible configuration as an unprotected tag.
            return true;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
        }
    }

    /** Detect a hardware password configuration that can block writes; unknown protected config is treated conservatively. */
    public static boolean appearsPasswordWriteProtected(Tag tag) {
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) return false;
            byte[][] configPages = readNtagConfig(ul, layout);
            return new NtagConfig(layout, configPages[0], configPages[1]).isProtectionActive();
        } catch (IOException e) {
            return true;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
        }
    }

    /** Raw authenticated Type-2 NDEF read for supported password-protected Type 2 chips. */
    public static byte[] readProtectedNdef(Tag tag, String passwordHex) throws IOException {
        byte[] password = parseHex(passwordHex, 4, "hardware password");
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) { wipe(password); throw new IOException("MIFARE Ultralight-compatible commands are unavailable."); }
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) throw new IOException("Unsupported tag model.");
            authenticate(ul, password);
            byte[] memory = readMemory(ul, layout.lastUserPage + 1);
            try { return parseNdefTlv(memory, 16, (layout.lastUserPage + 1) * 4); }
            finally { Arrays.fill(memory, (byte) 0); }
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
            wipe(password);
        }
    }

    /** Raw authenticated Type-2 NDEF write for supported password-protected Type 2 chips. Only a plain NDEF TLV at the normal
     * page-4 user-memory start is modified; unknown/private TLV layouts are refused. */
    public static void writeProtectedNdef(Tag tag, String passwordHex, byte[] ndefMessageBytes) throws IOException {
        byte[] password = parseHex(passwordHex, 4, "hardware password");
        if (ndefMessageBytes == null || ndefMessageBytes.length == 0) {
            wipe(password);
            throw new IOException("An empty NDEF message is not accepted by this writer.");
        }
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) { wipe(password); throw new IOException("MIFARE Ultralight-compatible commands are unavailable."); }
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) throw new IOException("Unsupported tag model.");
            authenticate(ul, password);
            int memoryEnd = (layout.lastUserPage + 1) * 4;
            byte[] memory = readMemory(ul, layout.lastUserPage + 1);
            try {
                int tlvStart = findNdefTlvStart(memory, 16, memoryEnd);
                if (tlvStart < 16) throw new IOException("The tag has a non-standard or protected TLV layout. Safe NDEF write was refused.");
                byte[] tlv = buildNdefTlv(ndefMessageBytes);
                int end = tlvStart + tlv.length;
                if (end >= memoryEnd) throw new IOException("The NDEF message does not fit in the tag's user memory.");
                System.arraycopy(tlv, 0, memory, tlvStart, tlv.length);
                memory[end] = (byte) 0xFE;
                int firstPage = tlvStart / 4;
                int lastPage = end / 4;
                for (int page = firstPage; page <= lastPage; page++) {
                    byte[] data = Arrays.copyOfRange(memory, page * 4, page * 4 + 4);
                    writePage(ul, page, data);
                    Arrays.fill(data, (byte) 0);
                }
                Arrays.fill(tlv, (byte) 0);
                byte[] verifyMemory = readMemory(ul, layout.lastUserPage + 1);
                byte[] verified;
                try {
                    verified = parseNdefTlv(verifyMemory, 16, memoryEnd);
                } finally {
                    Arrays.fill(verifyMemory, (byte) 0);
                }
                try {
                    if (!Arrays.equals(ndefMessageBytes, verified)) {
                        throw new IOException("Write finished, but read-back verification did not match.");
                    }
                } finally {
                    Arrays.fill(verified, (byte) 0);
                }
            } finally {
                Arrays.fill(memory, (byte) 0);
            }
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
            wipe(password);
        }
    }

    public static String familyName(Family family) {
        switch (family) {
            case NTAG210: return "NTAG210";
            case NTAG212: return "NTAG212";
            case NTAG213: return "NTAG213";
            case NTAG215: return "NTAG215";
            case NTAG216: return "NTAG216";
            case MIFARE_ULTRALIGHT_EV1_48: return "MIFARE Ultralight EV1 (48-byte user memory)";
            case MIFARE_ULTRALIGHT_EV1_128: return "MIFARE Ultralight EV1 (128-byte user memory)";
            case MIFARE_CLASSIC: return "MIFARE Classic";
            case MIFARE_ULTRALIGHT_OTHER: return "MIFARE Ultralight / Type 2 candidate";
            case ISO_DEP: return "ISO-DEP";
            case NFC_V: return "NFC-V / ISO 15693";
            case NFC_F: return "NFC-F / FeliCa";
            case NFC_A: return "NFC-A";
            case NFC_B: return "NFC-B";
            case NDEF_ONLY: return "NDEF (model unknown)";
            default: return "Unknown tag";
        }
    }

    private static NtagLayout identifyNtagLayout(Tag tag) throws IOException {
        MifareUltralight ul = MifareUltralight.get(tag);
        // Only probe tags for which Android exposes the Type-2 command interface. Sending a
        // GET_VERSION frame to an arbitrary NFC-A/ISO-DEP tag is not a safe way to identify it,
        // and the same MifareUltralight interface is required for the operations below.
        if (ul == null) return null;
        boolean wasConnected = false;
        try {
            ul.connect();
            wasConnected = true;
            return identifyNtagLayout(ul.transceive(new byte[] { CMD_GET_VERSION }));
        } finally {
            if (wasConnected) try { ul.close(); } catch (IOException ignored) { }
        }
    }

    private static NtagLayout identifyNtagLayout(MifareUltralight ul) throws IOException {
        return identifyNtagLayout(ul.transceive(new byte[] { CMD_GET_VERSION }));
    }

    private static NtagLayout identifyNtagLayout(byte[] version) {
        // Product versions are accepted only when the full NXP 8-byte GET_VERSION signature matches.
        if (version == null || version.length != 8 || (version[0] & 0xFF) != 0x00
                || (version[1] & 0xFF) != 0x04 || (version[4] & 0xFF) != 0x01
                || (version[5] & 0xFF) != 0x00 || (version[7] & 0xFF) != 0x03) return null;
        int productType = version[2] & 0xFF;
        int subtype = version[3] & 0xFF;
        int size = version[6] & 0xFF;

        // MIFARE Ultralight EV1 uses the same documented PWD_AUTH command and password layout,
        // but its GET_VERSION product type is 03h (not NTAG's 04h). The data-sheet memory layout
        // has CFG0 at 10h/25h and the final user page at 0Fh/23h.
        if (productType == 0x03 && (subtype == 0x01 || subtype == 0x02)) {
            if (size == 0x0B) {
                return new NtagLayout(Family.MIFARE_ULTRALIGHT_EV1_48,
                        "MIFARE Ultralight EV1 48-byte", 0x10, 0x0F);
            }
            if (size == 0x0E) {
                return new NtagLayout(Family.MIFARE_ULTRALIGHT_EV1_128,
                        "MIFARE Ultralight EV1 128-byte", 0x25, 0x23);
            }
            return null;
        }

        if (productType != 0x04) return null;
        if (subtype == 0x01 && size == 0x0B) {
            // NTAG210 has user pages 04h-0Fh; unlike larger variants it has no dynamic-lock page.
            return new NtagLayout(Family.NTAG210, "NTAG210", 0x10, 0x0F);
        }
        if (subtype == 0x01 && size == 0x0E) {
            return new NtagLayout(Family.NTAG212, "NTAG212", 0x25, 0x23);
        }
        if (subtype != 0x02) return null;
        if (size == 0x0F) return new NtagLayout(Family.NTAG213, "NTAG213", 0x29);
        if (size == 0x11) return new NtagLayout(Family.NTAG215, "NTAG215", 0x83);
        if (size == 0x13) return new NtagLayout(Family.NTAG216, "NTAG216", 0xE3);
        return null;
    }

    /**
     * Reads CFG0 and CFG1 in a single READ command. This matters for NTAG216 because CFG1 is
     * near the end of memory and a READ starting at CFG1 would span beyond its last valid page.
     */
    private static byte[][] readNtagConfig(MifareUltralight ul, NtagLayout layout) throws IOException {
        byte[] response = ul.transceive(new byte[] { CMD_READ, (byte) layout.config0Page });
        if (response == null || response.length != 16) {
            throw new IOException("Type 2 configuration pages are not readable (possibly protected or permanently locked).");
        }
        byte[] config0 = Arrays.copyOfRange(response, 0, 4);
        byte[] access = Arrays.copyOfRange(response, 4, 8);
        Arrays.fill(response, (byte) 0);
        return new byte[][] { config0, access };
    }

    private static byte[] readPages(MifareUltralight ul, int page) throws IOException {
        byte[] response = ul.transceive(new byte[] { CMD_READ, (byte) page });
        if (response == null || response.length != 16) throw new IOException("Could not read tag memory at page " + page + ".");
        return response;
    }

    private static byte[] readMemory(MifareUltralight ul, int pageCount) throws IOException {
        byte[] memory = new byte[pageCount * 4];
        for (int page = 0; page < pageCount; page += 4) {
            byte[] response = readPages(ul, page);
            int copy = Math.min(response.length, memory.length - page * 4);
            System.arraycopy(response, 0, memory, page * 4, copy);
            Arrays.fill(response, (byte) 0);
        }
        return memory;
    }

    private static void writePage(MifareUltralight ul, int page, byte[] data) throws IOException {
        if (data == null || data.length != 4) throw new IOException("A Type-2 page must contain exactly 4 bytes.");
        byte[] copy = Arrays.copyOf(data, 4);
        try { ul.writePage(page, copy); }
        finally { Arrays.fill(copy, (byte) 0); }
    }

    private static void authenticate(MifareUltralight ul, byte[] password) throws IOException {
        byte[] command = new byte[5];
        byte[] response = null;
        try {
            command[0] = CMD_PWD_AUTH;
            System.arraycopy(password, 0, command, 1, 4);
            response = ul.transceive(command);
            if (response == null || response.length != 2) {
                throw new IOException("Hardware password authentication failed. Check the current password.");
            }
            // The response is PACK, not the password. For an existing password, accept any PACK
            // value; after changing it, authenticateExpectedPack verifies the configured PACK.
        } finally {
            Arrays.fill(command, (byte) 0);
            wipe(response);
        }
    }

    private static void authenticateExpectedPack(MifareUltralight ul, byte[] password,
                                                 byte[] expectedPack) throws IOException {
        byte[] command = new byte[5];
        command[0] = CMD_PWD_AUTH;
        System.arraycopy(password, 0, command, 1, 4);
        byte[] response = null;
        try {
            response = ul.transceive(command);
            if (response == null || response.length != 2
                    || response[0] != expectedPack[0] || response[1] != expectedPack[1]) {
                throw new IOException("The new hardware password/PACK verification failed. Keep the tag on the reader and verify the configuration before retrying.");
            }
        } finally {
            Arrays.fill(command, (byte) 0);
            wipe(response);
        }
    }

    private static byte[] parseNdefTlv(byte[] memory, int start, int end) throws IOException {
        int pos = start;
        while (pos < end) {
            int type = memory[pos] & 0xFF;
            if (type == 0x00) { pos++; continue; }
            if (type == 0xFE) break;
            if (type != 0x03) {
                if (pos + 1 >= end) break;
                int length = memory[pos + 1] & 0xFF;
                int header = 2;
                if (length == 0xFF) {
                    if (pos + 3 >= end) break;
                    length = ((memory[pos + 2] & 0xFF) << 8) | (memory[pos + 3] & 0xFF);
                    header = 4;
                }
                if (length < 0 || pos + header + length > end) break;
                pos += header + length;
                continue;
            }
            if (pos + 1 >= end) throw new IOException("NDEF TLV is incomplete.");
            int length = memory[pos + 1] & 0xFF;
            int header = 2;
            if (length == 0xFF) {
                if (pos + 3 >= end) throw new IOException("Extended NDEF TLV is incomplete.");
                length = ((memory[pos + 2] & 0xFF) << 8) | (memory[pos + 3] & 0xFF);
                header = 4;
            }
            if (length == 0) return new byte[0];
            if (length < 0 || pos + header + length > end) throw new IOException("NDEF TLV exceeds readable tag memory.");
            return Arrays.copyOfRange(memory, pos + header, pos + header + length);
        }
        throw new IOException("No readable NDEF TLV was found after authentication.");
    }

    private static int findNdefTlvStart(byte[] memory, int start, int end) throws IOException {
        int pos = start;
        while (pos < end) {
            int type = memory[pos] & 0xFF;
            if (type == 0x00) { pos++; continue; }
            if (type == 0xFE) return pos;
            if (type == 0x03) return pos;
            // Only preserve other TLVs if a well-formed NDEF TLV later exists; replacing arbitrary
            // control TLVs can corrupt tag configuration, so this writer refuses such layouts.
            return -1;
        }
        return start;
    }

    private static byte[] buildNdefTlv(byte[] ndef) throws IOException {
        if (ndef.length < 0xFF) {
            byte[] out = new byte[ndef.length + 2];
            out[0] = 0x03;
            out[1] = (byte) ndef.length;
            System.arraycopy(ndef, 0, out, 2, ndef.length);
            return out;
        }
        if (ndef.length > 0xFFFF) throw new IOException("NDEF message is too large.");
        byte[] out = new byte[ndef.length + 4];
        out[0] = 0x03;
        out[1] = (byte) 0xFF;
        out[2] = (byte) ((ndef.length >> 8) & 0xFF);
        out[3] = (byte) (ndef.length & 0xFF);
        System.arraycopy(ndef, 0, out, 4, ndef.length);
        return out;
    }

    private static boolean validAccessBytes(byte[] trailer) {
        int b6 = trailer[6] & 0xFF;
        int b7 = trailer[7] & 0xFF;
        int b8 = trailer[8] & 0xFF;
        return ((((b6 >>> 4) & 0x0F) ^ (b8 & 0x0F)) == 0x0F)
                && (((b6 & 0x0F) ^ ((b7 >>> 4) & 0x0F)) == 0x0F)
                && (((b7 & 0x0F) ^ ((b8 >>> 4) & 0x0F)) == 0x0F);
    }

    private static byte[] parseHex(String value, int byteCount, String label) throws IOException {
        String hex = value == null ? "" : value.trim().replace(" ", "").replace(":", "").replace("-", "");
        if (hex.length() != byteCount * 2) throw new IOException(label + " must contain exactly " + (byteCount * 2) + " hexadecimal characters.");
        byte[] out = new byte[byteCount];
        for (int i = 0; i < byteCount; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                Arrays.fill(out, (byte) 0);
                throw new IOException(label + " can contain hexadecimal characters 0-9 and A-F only.");
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    private static byte[] DEFAULT_NTAG_PACK_AND_RFUI() {
        return new byte[] { DEFAULT_NTAG_PACK[0], DEFAULT_NTAG_PACK[1], 0, 0 };
    }

    private static void wipe(byte[] bytes) {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
    }

    private static boolean contains(String[] items, String suffix) {
        if (items == null) return false;
        for (String item : items) if (item != null && item.endsWith(suffix)) return true;
        return false;
    }
}
