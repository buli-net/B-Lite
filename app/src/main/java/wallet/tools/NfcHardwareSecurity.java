package wallet.tools;

import android.nfc.Tag;
import android.nfc.FormatException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.tech.Ndef;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
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
        public final boolean hardwarePasswordImplemented;

        private TagInfo(Family family, boolean implemented) {
            this.family = family;
            this.hardwarePasswordImplemented = implemented;
        }
    }

    private static final class NtagLayout {
        final Family family;
        final int config0Page;
        final int accessPage;
        final int passwordPage;
        final int packPage;
        final int lastUserPage;

        NtagLayout(Family family, int config0Page) {
            this(family, config0Page, config0Page - 2);
        }

        NtagLayout(Family family, int config0Page, int lastUserPage) {
            this.family = family;
            this.config0Page = config0Page;
            this.accessPage = config0Page + 1;
            this.passwordPage = config0Page + 2;
            this.packPage = config0Page + 3;
            this.lastUserPage = lastUserPage;
        }
    }

    private static final class ClassicBlockRef {
        final int sector;
        final int block;
        final int offset;
        ClassicBlockRef(int sector, int block, int offset) {
            this.sector = sector;
            this.block = block;
            this.offset = offset;
        }
    }

    private static final class ClassicTrailerRead {
        final byte[] bytes;
        final boolean authenticatedAsKeyA;
        ClassicTrailerRead(byte[] bytes, boolean authenticatedAsKeyA) {
            this.bytes = bytes;
            this.authenticatedAsKeyA = authenticatedAsKeyA;
        }
    }

    private static final class ClassicNdefData {
        final byte[] bytes;
        final List<ClassicBlockRef> blocks;
        final byte[][] authKeys;
        final List<Integer> sectors;
        ClassicNdefData(byte[] bytes, List<ClassicBlockRef> blocks, byte[][] authKeys, List<Integer> sectors) {
            this.bytes = bytes;
            this.blocks = blocks;
            this.authKeys = authKeys;
            this.sectors = sectors;
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

    /** Identify the protocol family without embedding display strings in the driver. */
    public static TagInfo identify(Tag tag) {
        if (tag == null) return new TagInfo(Family.UNKNOWN, false);
        if (MifareClassic.get(tag) != null) {
            boolean ndefMapped = isClassicNdefMapped(tag);
            return new TagInfo(Family.MIFARE_CLASSIC, ndefMapped);
        }
        try {
            NtagLayout layout = identifyNtagLayout(tag);
            if (layout != null) return new TagInfo(layout.family, true);
        } catch (IOException ignored) {
            // Continue with technology-level identification.
        }
        String[] techs = tag.getTechList();
        if (contains(techs, "MifareUltralight")) return new TagInfo(Family.MIFARE_ULTRALIGHT_OTHER, false);
        if (contains(techs, "IsoDep")) return new TagInfo(Family.ISO_DEP, false);
        if (contains(techs, "NfcV")) return new TagInfo(Family.NFC_V, false);
        if (contains(techs, "NfcF")) return new TagInfo(Family.NFC_F, false);
        if (contains(techs, "NfcB")) return new TagInfo(Family.NFC_B, false);
        if (contains(techs, "NfcA")) return new TagInfo(Family.NFC_A, false);
        if (contains(techs, "Ndef")) return new TagInfo(Family.NDEF_ONLY, false);
        return new TagInfo(Family.UNKNOWN, false);
    }

    public static boolean supportsHardwarePassword(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return isClassicNdefMapped(tag);
        try { return identifyNtagLayout(tag) != null; }
        catch (IOException | RuntimeException e) { return false; }
    }

    /** Whether setting a new common password can be done without guessing a current chip credential. */
    public static boolean canSetHardwarePassword(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return isClassicNdefMapped(tag);
        return canSetType2HardwarePassword(tag);
    }

    /** A change is offered only when the detected tag reports an active supported hardware password. */
    public static boolean canChangeHardwarePassword(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) {
            return isClassicNdefMapped(tag) && classicNdefUsesNonPublicKey(tag);
        }
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        NtagLayout layout = null;
        try {
            ul.connect();
            layout = identifyNtagLayout(ul);
            if (layout == null) return false;
            byte[][] configPages = readNtagConfig(ul, layout);
            return new NtagConfig(layout, configPages[0], configPages[1]).isProtectionActive();
        } catch (IOException | RuntimeException e) {
            // If the exact model was positively identified but its protection configuration
            // cannot be read, allow Change only; the authenticated operation will validate it.
            return layout != null;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
        }
    }

    /** CFGLOCK permanently disables Type 2 configuration changes, so do not show Remove in that state. */
    public static boolean canRemoveHardwarePassword(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) {
            return isClassicNdefMapped(tag) && classicNdefUsesNonPublicKey(tag);
        }
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) return false;
            byte[][] configPages = readNtagConfig(ul, layout);
            NtagConfig config = new NtagConfig(layout, configPages[0], configPages[1]);
            return config.isProtectionActive() && (config.access & 0x40) == 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean canSetType2HardwarePassword(Tag tag) {
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) return false;
            byte[][] configPages = readNtagConfig(ul, layout);
            NtagConfig config = new NtagConfig(layout, configPages[0], configPages[1]);
            // CFGLOCK makes the protection configuration permanently read-only. Do not offer
            // initial setup when the tag itself cannot accept the required configuration writes.
            return !config.isProtectionActive() && (config.access & 0x40) == 0;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
        }
    }


    /** Reads a sector trailer using one credential, without trying both roles back-to-back before reading.
     * MIFARE Classic can reject subsequent commands if Key B is readable as data, so each role is tested
     * only after the previous role failed to yield a valid trailer. No other keys are guessed. */
    private static ClassicTrailerRead readClassicTrailerWithCredential(
            MifareClassic classic, int sector, byte[] credential) throws IOException {
        int trailerBlock = classic.sectorToBlock(sector) + classic.getBlockCountInSector(sector) - 1;
        if (classic.authenticateSectorWithKeyA(sector, credential)) {
            try {
                byte[] trailer = classic.readBlock(trailerBlock);
                if (trailer != null && trailer.length == 16 && validAccessBytes(trailer)) {
                    return new ClassicTrailerRead(trailer, true);
                }
                wipe(trailer);
            } catch (IOException | RuntimeException ignored) {
                // Try the same user-supplied credential as Key B below; this is not key guessing.
            }
        }
        if (classic.authenticateSectorWithKeyB(sector, credential)) {
            try {
                byte[] trailer = classic.readBlock(trailerBlock);
                if (trailer != null && trailer.length == 16 && validAccessBytes(trailer)) {
                    return new ClassicTrailerRead(trailer, false);
                }
                wipe(trailer);
            } catch (IOException | RuntimeException ignored) {
                // Fail closed; caller reports that sector access could not be verified.
            }
        }
        return null;
    }

    /** Derives an in-memory, family-specific hardware credential from user-entered text. */
    public static String type2PasswordHexFromUser(String userPassword) throws IOException {
        return hex(passwordBytesFromUser(userPassword, 4, "type2"));
    }

    /** Returns a six-byte Classic sector key derived from a user-facing password. */
    public static byte[] classicKeyFromUser(String userPassword) throws IOException {
        return passwordBytesFromUser(userPassword, 6, "mifare-classic");
    }

    private static byte[] passwordBytesFromUser(String userPassword, int byteCount, String family) throws IOException {
        if (userPassword == null || userPassword.isEmpty()) throw new IOException("Enter the hardware password.");
        byte[] raw = userPassword.getBytes(StandardCharsets.UTF_8);
        try {
            if (raw.length == byteCount) return Arrays.copyOf(raw, raw.length);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("nfc-hardware-credential/v1/".getBytes(StandardCharsets.UTF_8));
            digest.update(family.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(raw);
            byte[] full = digest.digest();
            byte[] out = Arrays.copyOf(full, byteCount);
            wipe(full);
            return out;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable on this Android device.", e);
        } finally {
            wipe(raw);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02X", b & 0xFF));
        wipe(bytes);
        return out.toString();
    }

    /** The common UX asks for a hardware password whenever protection is active, even when the chip
     * protects writing only. The chip driver still enforces its actual read permissions. */
    public static boolean requiresPasswordForNdefRead(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return classicNdefUsesNonPublicKey(tag);
        try { return supportsType2Password(tag) && appearsPasswordWriteProtected(tag); }
        catch (RuntimeException e) { return false; }
    }

    /** True when this detected NDEF mapping uses a non-public sector key, or Type 2 protection is active. */
    public static boolean requiresPasswordForNdefWrite(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return classicNdefUsesNonPublicKey(tag);
        try { return supportsType2Password(tag) && appearsPasswordWriteProtected(tag); }
        catch (RuntimeException e) { return false; }
    }

    private static boolean supportsType2Password(Tag tag) {
        try { return identifyNtagLayout(tag) != null; }
        catch (IOException | RuntimeException e) { return false; }
    }

    /** Creates or changes the hardware credential from user-facing text; the credential is derived in memory only. */
    public static void updateHardwarePasswordFromUserText(Tag tag, NtagAction action,
                                                           String currentPassword, String newPassword) throws IOException {
        if (tag == null) throw new IOException("No NFC tag is available.");
        if (MifareClassic.get(tag) != null) {
            updateClassicNdefPassword(tag, action, currentPassword, newPassword);
            return;
        }
        String currentHex = currentPassword == null || currentPassword.isEmpty() ? "" : type2PasswordHexFromUser(currentPassword);
        String newHex = action == NtagAction.REMOVE ? "" : type2PasswordHexFromUser(newPassword);
        updateHardwarePassword(tag, action, currentHex, newHex);
    }

    /** Changes hardware password settings on positively identified supported Type 2 tags. */
    public static void updateHardwarePassword(Tag tag, NtagAction action,
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
                return;
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
                return;
            }
            return;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
            wipe(current);
            wipe(next);
            wipe(configuredPack);
        }
    }

    /** Report whether NDEF data appears read-protected; false means not established as protected. */
    public static boolean appearsPasswordReadProtected(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return classicNdefUsesNonPublicKey(tag);
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        NtagLayout layout = null;
        try {
            ul.connect();
            layout = identifyNtagLayout(ul);
            if (layout == null) return false;
            byte[][] configPages = readNtagConfig(ul, layout);
            return new NtagConfig(layout, configPages[0], configPages[1]).protectedFromRead();
        } catch (IOException e) {
            // Configuration read failed only after attempting exact model identification.
            // Report unknown/probably protected rather than prompting for every NFC-A tag.
            return layout != null;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
        }
    }

    /** Detects hardware protection, not ordinary read-only access bits. */
    public static boolean appearsPasswordWriteProtected(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return classicNdefUsesNonPublicKey(tag);
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        NtagLayout layout = null;
        try {
            ul.connect();
            layout = identifyNtagLayout(ul);
            if (layout == null) return false;
            byte[][] configPages = readNtagConfig(ul, layout);
            return new NtagConfig(layout, configPages[0], configPages[1]).isProtectionActive();
        } catch (IOException e) {
            // A positive GET_VERSION match plus inaccessible config pages is not evidence that
            // the card is unprotected. Fail closed and let PWD_AUTH validate during the action.
            return layout != null;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
        }
    }

    /** User-facing interface. Type 2 passwords are converted here; MIFARE Classic dispatches to its sector driver. */
    public static byte[] readProtectedNdef(Tag tag, String userPassword) throws IOException {
        if (tag == null) throw new IOException("No NFC tag is available.");
        if (MifareClassic.get(tag) != null) {
            byte[] key = classicKeyFromUser(userPassword);
            try { return readProtectedClassicNdef(tag, key); }
            finally { wipe(key); }
        }
        return readProtectedType2Ndef(tag, type2PasswordHexFromUser(userPassword));
    }

    /** User-facing interface. The supplied password is derived into the credential type required by the scanned chip. */
    public static void writeProtectedNdef(Tag tag, String userPassword, byte[] ndefMessageBytes) throws IOException {
        if (tag == null) throw new IOException("No NFC tag is available.");
        if (MifareClassic.get(tag) != null) {
            byte[] key = classicKeyFromUser(userPassword);
            try { writeProtectedClassicNdef(tag, key, ndefMessageBytes); }
            finally { wipe(key); }
            return;
        }
        writeProtectedType2Ndef(tag, type2PasswordHexFromUser(userPassword), ndefMessageBytes);
    }

    private static boolean isClassicNdefMapped(Tag tag) {
        MifareClassic classic = MifareClassic.get(tag);
        if (classic == null) return false;
        try {
            classic.connect();
            return !readClassicNdefSectors(classic).isEmpty();
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            try { classic.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean classicNdefUsesNonPublicKey(Tag tag) {
        MifareClassic classic = MifareClassic.get(tag);
        if (classic == null) return false;
        try {
            classic.connect();
            List<Integer> sectors = readClassicNdefSectors(classic);
            if (sectors.isEmpty()) return false;
            byte[] forumKey = Arrays.copyOf(MifareClassic.KEY_NFC_FORUM, 6);
            byte[] factoryKey = Arrays.copyOf(MifareClassic.KEY_DEFAULT, 6);
            try {
                for (int sector : sectors) {
                    // A sector still authenticating with either documented NDEF or factory-default
                    // credentials is not evidence that the user has set a personal password.
                    // Check authentication itself, not data readability, because access bits may
                    // restrict reads while the standard credential remains active.
                    boolean forumAccepted = canAuthenticateClassicSectorWithKey(classic, sector, forumKey);
                    boolean factoryAccepted = !forumAccepted
                            && canAuthenticateClassicSectorWithKey(classic, sector, factoryKey);
                    if (!forumAccepted && !factoryAccepted) return true;
                }
            } finally {
                wipe(forumKey);
                wipe(factoryKey);
            }
            return false;
        } catch (IOException | RuntimeException e) {
            // Unknown directory/authentication state must not be reported as unprotected.
            return true;
        } finally {
            try { classic.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean canAuthenticateClassicSectorWithKey(
            MifareClassic classic, int sector, byte[] key) throws IOException {
        if (classic.authenticateSectorWithKeyA(sector, key)) return true;
        return classic.authenticateSectorWithKeyB(sector, key);
    }

    private static void updateClassicNdefPassword(Tag tag, NtagAction action,
                                                   String currentPassword, String newPassword) throws IOException {
        MifareClassic classic = MifareClassic.get(tag);
        if (classic == null) throw new IOException("Unsupported tag technology.");

        byte[] current = currentPassword == null || currentPassword.trim().isEmpty()
                ? null : classicKeyFromUser(currentPassword);
        byte[] next = action == NtagAction.REMOVE ? null : classicKeyFromUser(newPassword);
        byte[] standardKey = Arrays.copyOf(MifareClassic.KEY_NFC_FORUM, 6);
        byte[] factoryKey = Arrays.copyOf(MifareClassic.KEY_DEFAULT, 6);
        if (action == NtagAction.REMOVE) next = Arrays.copyOf(standardKey, standardKey.length);
        List<byte[]> sectorCredentials = new ArrayList<>();
        List<Integer> writableSectors = new ArrayList<>();
        try {
            classic.connect();
            List<Integer> sectors = readClassicNdefSectors(classic);
            if (sectors.isEmpty()) throw new IOException("No standard NDEF sectors were found.");

            // Verify all affected sectors before writing any trailer. Initial setup checks the standard
            // NDEF credential first, then an explicitly supplied current credential if present.
            for (int sector : sectors) {
                byte[] selectedKey = null;
                List<byte[]> candidates = new ArrayList<>();
                if (action == NtagAction.SET) {
                    // Initial setup checks the documented NDEF key and the factory-default sector
                    // key. These are finite protocol defaults, not arbitrary key guessing.
                    candidates.add(standardKey);
                    if (!Arrays.equals(factoryKey, standardKey)) candidates.add(factoryKey);
                    if (current != null && !Arrays.equals(current, standardKey)
                            && !Arrays.equals(current, factoryKey)) candidates.add(current);
                } else {
                    // Prefer the text password provided by the user, then allow protocol-default
                    // credentials to recover sectors left at defaults by an interrupted operation.
                    if (current != null) candidates.add(current);
                    if (current == null || !Arrays.equals(current, standardKey)) candidates.add(standardKey);
                    if ((current == null || !Arrays.equals(current, factoryKey))
                            && !Arrays.equals(factoryKey, standardKey)) candidates.add(factoryKey);
                }

                for (byte[] candidate : candidates) {
                    ClassicTrailerRead trailerRead = readClassicTrailerWithCredential(classic, sector, candidate);
                    if (trailerRead == null) continue;
                    try {
                        int condition = classicTrailerCondition(trailerRead.bytes);
                        boolean canUpdateTrailer;
                        if (condition == 0 || condition == 1) {
                            canUpdateTrailer = classic.authenticateSectorWithKeyA(sector, candidate);
                        } else if (condition == 3 || condition == 4) {
                            canUpdateTrailer = classic.authenticateSectorWithKeyB(sector, candidate);
                        } else {
                            canUpdateTrailer = false;
                        }
                        if (canUpdateTrailer) {
                            selectedKey = Arrays.copyOf(candidate, candidate.length);
                            break;
                        }
                    } finally {
                        wipe(trailerRead.bytes);
                    }
                }
                if (selectedKey == null) {
                    throw new IOException("The existing credential or sector permissions do not allow a safe password change. No NDEF sector keys were changed.");
                }
                sectorCredentials.add(selectedKey);
                writableSectors.add(sector);
            }

            for (int i = 0; i < writableSectors.size(); i++) {
                int sector = writableSectors.get(i);
                byte[] authKey = sectorCredentials.get(i);
                int trailerBlock = classic.sectorToBlock(sector) + classic.getBlockCountInSector(sector) - 1;
                ClassicTrailerRead trailerRead = readClassicTrailerWithCredential(classic, sector, authKey);
                if (trailerRead == null) throw new IOException("A sector could not be re-authenticated. Processing stopped.");
                byte[] trailer = trailerRead.bytes;
                try {
                    int condition = classicTrailerCondition(trailer);
                    boolean authenticated = (condition == 0 || condition == 1)
                            ? classic.authenticateSectorWithKeyA(sector, authKey)
                            : (condition == 3 || condition == 4)
                                    && classic.authenticateSectorWithKeyB(sector, authKey);
                    if (!authenticated) throw new IOException("Sector access changed during the operation. Processing stopped.");
                    System.arraycopy(next, 0, trailer, 0, 6);
                    System.arraycopy(next, 0, trailer, 10, 6);
                    classic.writeBlock(trailerBlock, trailer);
                    if (!(classic.authenticateSectorWithKeyA(sector, next)
                            || classic.authenticateSectorWithKeyB(sector, next))) {
                        throw new IOException("A sector changed but verification with the new credential failed.");
                    }
                } finally {
                    wipe(trailer);
                }
            }
        } finally {
            try { classic.close(); } catch (IOException ignored) { }
            wipe(current);
            wipe(next);
            wipe(standardKey);
            wipe(factoryKey);
            for (byte[] key : sectorCredentials) wipe(key);
        }
    }

    private static int classicTrailerCondition(byte[] trailer) {
        int b7 = trailer[7] & 0xFF;
        int b8 = trailer[8] & 0xFF;
        // For trailer block 3, C1 is bit 3 of byte 7; C2 is bit 3 of byte 8;
        // C3 is bit 7 of byte 8. The complement bits are checked separately.
        int c1 = (b7 >>> 3) & 1;
        int c2 = (b8 >>> 3) & 1;
        int c3 = (b8 >>> 7) & 1;
        return (c1 << 2) | (c2 << 1) | c3;
    }

    private static List<Integer> readClassicNdefSectors(MifareClassic classic) throws IOException {
        if (!classic.authenticateSectorWithKeyA(0, MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY)) {
            throw new IOException("The MAD directory sector is not accessible with the standard MIFARE Application Directory key.");
        }
        int first = classic.sectorToBlock(0);
        byte[] mad1 = new byte[32];
        byte[] block1 = classic.readBlock(first + 1);
        byte[] block2 = classic.readBlock(first + 2);
        System.arraycopy(block1, 0, mad1, 0, 16);
        System.arraycopy(block2, 0, mad1, 16, 16);
        wipe(block1); wipe(block2);
        List<Integer> result = new ArrayList<>();
        for (int sector = 1; sector <= Math.min(15, classic.getSectorCount() - 1); sector++) {
            int off = 2 + (sector - 1) * 2;
            if (isNdefAid(mad1[off], mad1[off + 1])) result.add(sector);
        }
        wipe(mad1);
        if (classic.getSectorCount() > 16) {
            if (!classic.authenticateSectorWithKeyA(16, MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY)) {
                throw new IOException("The MIFARE 4K MAD2 directory is not accessible with its standard directory key.");
            }
            byte[] mad2 = new byte[48];
            int base = classic.sectorToBlock(16);
            for (int i = 0; i < 3; i++) {
                byte[] block = classic.readBlock(base + i);
                System.arraycopy(block, 0, mad2, i * 16, 16);
                wipe(block);
            }
            for (int sector = 17; sector < classic.getSectorCount(); sector++) {
                int off = 2 + (sector - 17) * 2;
                if (off + 1 < mad2.length && isNdefAid(mad2[off], mad2[off + 1])) result.add(sector);
            }
            wipe(mad2);
        }
        return result;
    }

    private static boolean isNdefAid(byte a, byte b) {
        return ((a & 0xFF) == 0xE1 && (b & 0xFF) == 0x03)
                || ((a & 0xFF) == 0x03 && (b & 0xFF) == 0xE1);
    }

    private static ClassicNdefData loadClassicNdefData(MifareClassic classic, byte[] userKey) throws IOException {
        List<Integer> sectors = readClassicNdefSectors(classic);
        if (sectors.isEmpty()) throw new IOException("The MAD directory contains no standard NDEF sectors.");
        List<ClassicBlockRef> refs = new ArrayList<>();
        int total = 0;
        for (int sector : sectors) total += (classic.getBlockCountInSector(sector) - 1) * 16;
        byte[] memory = new byte[total];
        byte[] standardKey = Arrays.copyOf(MifareClassic.KEY_NFC_FORUM, 6);
        byte[][] authKeys = new byte[classic.getSectorCount()][];
        int offset = 0;
        try {
            for (int sector : sectors) {
                byte[] selectedKey = userKey;
                boolean auth = classic.authenticateSectorWithKeyA(sector, selectedKey);
                if (!auth) auth = classic.authenticateSectorWithKeyB(sector, selectedKey);
                if (!auth && !Arrays.equals(userKey, standardKey)) {
                    // The standard NDEF key is a defined credential, not a brute-force candidate.
                    // This also permits recovery if a multi-sector key update was interrupted.
                    selectedKey = standardKey;
                    auth = classic.authenticateSectorWithKeyA(sector, selectedKey);
                    if (!auth) auth = classic.authenticateSectorWithKeyB(sector, selectedKey);
                }
                if (!auth) throw new IOException("The supplied credential could not authenticate an NDEF sector.");
                authKeys[sector] = Arrays.copyOf(selectedKey, selectedKey.length);
                int startBlock = classic.sectorToBlock(sector);
                int dataBlocks = classic.getBlockCountInSector(sector) - 1;
                for (int blockOffset = 0; blockOffset < dataBlocks; blockOffset++) {
                    int blockNumber = startBlock + blockOffset;
                    byte[] block = classic.readBlock(blockNumber);
                    System.arraycopy(block, 0, memory, offset, 16);
                    refs.add(new ClassicBlockRef(sector, blockNumber, offset));
                    offset += 16;
                    wipe(block);
                }
            }
            return new ClassicNdefData(memory, refs, authKeys, sectors);
        } catch (IOException | RuntimeException e) {
            wipe(memory);
            for (byte[] key : authKeys) wipe(key);
            throw e;
        } finally {
            wipe(standardKey);
        }
    }

    private static byte[] readProtectedClassicNdef(Tag tag, byte[] userKey) throws IOException {
        MifareClassic classic = MifareClassic.get(tag);
        if (classic == null) throw new IOException("This tag is not MIFARE Classic.");
        ClassicNdefData data = null;
        try {
            classic.connect();
            data = loadClassicNdefData(classic, userKey);
            return parseNdefTlv(data.bytes, 0, data.bytes.length);
        } finally {
            if (data != null) wipeClassicNdefData(data);
            try { classic.close(); } catch (IOException ignored) { }
        }
    }

    private static void writeProtectedClassicNdef(Tag tag, byte[] userKey, byte[] ndefMessageBytes) throws IOException {
        if (ndefMessageBytes == null || ndefMessageBytes.length == 0) throw new IOException("An empty NDEF message is not accepted.");
        MifareClassic classic = MifareClassic.get(tag);
        if (classic == null) throw new IOException("This tag is not MIFARE Classic.");
        ClassicNdefData data = null;
        int written = 0;
        try {
            classic.connect();
            data = loadClassicNdefData(classic, userKey);
            int start = findNdefTlvStart(data.bytes, 0, data.bytes.length);
            if (start < 0) throw new IOException("No safe standard NDEF TLV position was found. No blocks were written.");
            byte[] tlv = buildNdefTlv(ndefMessageBytes);
            int end = start + tlv.length;
            if (end >= data.bytes.length) { wipe(tlv); throw new IOException("The NDEF message does not fit in the mapped Classic NDEF sectors."); }
            byte[] updated = Arrays.copyOf(data.bytes, data.bytes.length);
            System.arraycopy(tlv, 0, updated, start, tlv.length);
            updated[end] = (byte) 0xFE;
            wipe(tlv);
            try {
                for (ClassicBlockRef ref : data.blocks) {
                    boolean changed = false;
                    for (int i = 0; i < 16; i++) {
                        if (updated[ref.offset + i] != data.bytes[ref.offset + i]) { changed = true; break; }
                    }
                    if (!changed) continue;
                    byte[] authKey = data.authKeys[ref.sector];
                    if (authKey == null || (!classic.authenticateSectorWithKeyA(ref.sector, authKey)
                            && !classic.authenticateSectorWithKeyB(ref.sector, authKey))) {
                        throw new IOException("Authentication was lost for sector " + ref.sector + ". " + written + " data block(s) were written before stopping.");
                    }
                    byte[] block = Arrays.copyOfRange(updated, ref.offset, ref.offset + 16);
                    try { classic.writeBlock(ref.block, block); written++; }
                    catch (IOException | RuntimeException e) {
                        throw new IOException("Could not write sector " + ref.sector + ". " + written + " data block(s) may already have been written; the tag could be partially updated.", e);
                    } finally { wipe(block); }
                }
            } finally { wipe(updated); }
            // Release this technology connection before opening a new one for read-back verification.
            try { classic.close(); } catch (IOException ignored) { }
            byte[] verified = readProtectedClassicNdef(tag, userKey);
            try {
                if (!Arrays.equals(ndefMessageBytes, verified)) throw new IOException("Classic NDEF was written but read-back verification did not match.");
            } finally { wipe(verified); }
        } finally {
            if (data != null) wipeClassicNdefData(data);
            try { classic.close(); } catch (IOException ignored) { }
        }
    }

    private static void wipeClassicNdefData(ClassicNdefData data) {
        wipe(data.bytes);
        for (byte[] key : data.authKeys) wipe(key);
    }

    private static byte[] readProtectedType2Ndef(Tag tag, String passwordHex) throws IOException {
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
    private static void writeProtectedType2Ndef(Tag tag, String passwordHex, byte[] ndefMessageBytes) throws IOException {
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

    /** Clears the NDEF payload through the driver's authenticated memory path. */
    public static void clearProtectedNdef(Tag tag, String userPassword) throws IOException {
        NdefRecord emptyRecord = new NdefRecord(NdefRecord.TNF_EMPTY, null, null, new byte[0]);
        writeProtectedNdef(tag, userPassword, new NdefMessage(emptyRecord).toByteArray());
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
                return new NtagLayout(Family.MIFARE_ULTRALIGHT_EV1_48, 0x10, 0x0F);
            }
            if (size == 0x0E) {
                return new NtagLayout(Family.MIFARE_ULTRALIGHT_EV1_128, 0x25, 0x23);
            }
            return null;
        }

        if (productType != 0x04) return null;
        if (subtype == 0x01 && size == 0x0B) {
            // NTAG210 has user pages 04h-0Fh; unlike larger variants it has no dynamic-lock page.
            return new NtagLayout(Family.NTAG210, 0x10, 0x0F);
        }
        if (subtype == 0x01 && size == 0x0E) {
            return new NtagLayout(Family.NTAG212, 0x25, 0x23);
        }
        if (subtype != 0x02) return null;
        if (size == 0x0F) return new NtagLayout(Family.NTAG213, 0x29);
        if (size == 0x11) return new NtagLayout(Family.NTAG215, 0x83);
        if (size == 0x13) return new NtagLayout(Family.NTAG216, 0xE3);
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
