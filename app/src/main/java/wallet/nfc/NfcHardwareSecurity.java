package wallet.nfc;

import wallet.ui.LocalizedErrors;
import wallet.main.R;

import android.nfc.Tag;
import android.nfc.TagLostException;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.FormatException;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.nfc.tech.IsoDep;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.MifareUltralight;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.bitcoinj.base.Sha256Hash;
import org.bouncycastle.util.encoders.DecoderException;
import org.bouncycastle.util.encoders.Hex;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chip-specific NFC security operations. This class deliberately performs no key guessing.
 * NTAG210/212/213/215/216 and MIFARE Ultralight EV1 use their documented 32-bit PWD_AUTH mechanism. MIFARE Classic uses caller-supplied
 * sector keys;
 those keys are not a universal card password.
 */
public final class NfcHardwareSecurity {
    private static final byte CMD_GET_VERSION = 0x60;
    private static final byte CMD_READ = 0x30;
    private static final byte CMD_PWD_AUTH = 0x1B;
    private static final byte CMD_FAST_READ = 0x3A;
    private static final SecureRandom PACK_RANDOM = new SecureRandom();
    private static final byte[] DEFAULT_NTAG_PASSWORD = new byte[] {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
    };
    // NXP MF0ICU2 delivery-state 2-key 3DES key, ASCII "BREAKMEIFYOUCAN!".
    private static final byte[] DEFAULT_ULTRALIGHT_C_KEY = new byte[] {
            0x42, 0x52, 0x45, 0x41, 0x4B, 0x4D, 0x45, 0x49,
            0x46, 0x59, 0x4F, 0x55, 0x43, 0x41, 0x4E, 0x21
    };
    /**
     * The nine model families with chip-specific erase/restore logic are the five NTAG
     * variants, both Ultralight EV1 capacities, Ultralight C, and MIFARE Classic.
     * Remaining enum values represent generic protocols, not inferred chip models.
     */
    public enum Family {
        NTAG210, NTAG212, NTAG213, NTAG215, NTAG216,
        MIFARE_ULTRALIGHT_EV1_48, MIFARE_ULTRALIGHT_EV1_128,
        MIFARE_ULTRALIGHT_C, MIFARE_CLASSIC, MIFARE_ULTRALIGHT_OTHER,
        ISO_DEP, NFC_V, NFC_F, NFC_A, NFC_B, NDEF_ONLY, UNKNOWN
    }

    public enum NtagAction { SET, CHANGE, REMOVE }

    /** Result categories for destructive maintenance. Counts are sectors for Classic and pages for Type 2. */
    public enum MaintenanceCode {
        TYPE2_ERASED, TYPE2_ERASE_PARTIAL,
        ULTRALIGHT_C_ERASED, ULTRALIGHT_C_ERASE_PARTIAL,
        CLASSIC_ERASED, CLASSIC_ERASE_PARTIAL, NDEF_ERASED,
        TYPE2_FACTORY_LIMITED, TYPE2_FACTORY_PARTIAL,
        ULTRALIGHT_C_FACTORY_LIMITED, ULTRALIGHT_C_FACTORY_PARTIAL,
        CLASSIC_FACTORY_LIMITED, CLASSIC_FACTORY_PARTIAL, NDEF_FORMATTED, FACTORY_UNSUPPORTED
    }

    public static final class MaintenanceResult {
        public final MaintenanceCode code;
        public final int completed;
        public final int total;
        public final boolean configurationRestored;
        /** First non-zero page that could not be erased, or -1 when every page verified clear. */
        public final int firstFailedPage;

        private MaintenanceResult(MaintenanceCode code, int completed, int total, boolean configurationRestored) {
            this(code, completed, total, configurationRestored, -1);
        }

        private MaintenanceResult(MaintenanceCode code, int completed, int total,
                                  boolean configurationRestored, int firstFailedPage) {
            this.code = code;
            this.completed = completed;
            this.total = total;
            this.configurationRestored = configurationRestored;
            this.firstFailedPage = firstFailedPage;
        }
    }

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
        // Family-specific delivery defaults for the first two configuration pages.
        // NTAG defaults are zero in these bytes; Ultralight EV1 additionally has
        // MOD.STRG_MOD_EN and CFG1.VCTID defaults that must not be erased to zero.
        final int defaultModByte;
        final int defaultVctid;

        NtagLayout(Family family, int config0Page) {
            this(family, config0Page, config0Page - 2, 0, 0);
        }

        NtagLayout(Family family, int config0Page, int lastUserPage) {
            this(family, config0Page, lastUserPage, 0, 0);
        }

        NtagLayout(Family family, int config0Page, int lastUserPage,
                   int defaultModByte, int defaultVctid) {
            this.family = family;
            this.config0Page = config0Page;
            this.accessPage = config0Page + 1;
            this.passwordPage = config0Page + 2;
            this.packPage = config0Page + 3;
            this.lastUserPage = lastUserPage;
            this.defaultModByte = defaultModByte;
            this.defaultVctid = defaultVctid;
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
            return isUserMemoryWriteProtected() && (access & 0x80) != 0;
        }

        boolean isProtectionActive() {
            // AUTH0 can protect only the configuration/credential pages while leaving
            // user data pages publicly writable. Keep this broad predicate for operations
            // that modify PWD/PACK/CFG pages.
            return auth0 != 0xFF && auth0 <= layout.packPage;
        }

        boolean isUserMemoryWriteProtected() {
            // NTAG21x and Ultralight EV1 gate writes from AUTH0 onward. If AUTH0 starts
            // after the final user page (for example at PWD/PACK), erasing user data does
            // not require PWD_AUTH even though factory-configuration changes do.
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
        MifareUltralight ultralight = MifareUltralight.get(tag);
        if (ultralight != null) {
            boolean connected = false;
            try {
                ultralight.connect();
                connected = true;
                // GET_VERSION is the primary discriminator for all NTAG and Ultralight EV1
                // families. Android's TYPE_ULTRALIGHT_C value is only a transport hint, not
                // proof of an Ultralight C chip (some NTAG devices report it too).
                NtagLayout layout = identifyNtagLayout(ultralight);
                if (layout != null) return new TagInfo(layout.family, true);
            } catch (IOException | RuntimeException ignored) {
                // Older Type-2 products do not implement GET_VERSION.
            } finally {
                if (connected) try { ultralight.close(); } catch (IOException ignored) { }
            }
            if (probeUltralightCHandshake(tag)) {
                return new TagInfo(Family.MIFARE_ULTRALIGHT_C, false);
            }
            return new TagInfo(Family.MIFARE_ULTRALIGHT_OTHER, false);
        }
        String[] techs = tag.getTechList();
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
        try {
            // Includes NTAG210/212/213/215/216 and Ultralight EV1 only. Ultralight C
            // uses 3DES and has its own maintenance-key path, not PWD_AUTH.
            return identifyNtagLayout(tag) != null;
        }
        catch (IOException | RuntimeException e) {
            return false;
        }
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
            try {
                ul.close();
            } catch (IOException ignored) { }
        }
    }

    /**
     * Checks whether removal is available. If the protected chip refuses an unauthenticated
     * configuration read, the authenticated remove operation performs the definitive CFGLOCK check.
     */
    public static boolean canRemoveHardwarePassword(Tag tag) {
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
            NtagConfig config = new NtagConfig(layout, configPages[0], configPages[1]);
            return config.isProtectionActive() && (config.access & 0x40) == 0;
        } catch (IOException | RuntimeException e) {
            // With PROT enabled the chip may also deny an unauthenticated READ of CFG0/ACCESS.
            // The model is still positively identified by GET_VERSION; expose Remove and let the
            // authenticated operation read CFGLOCK and decide safely. Never write blindly here.
            return layout != null;
        } finally {
            try {
                ul.close();
            } catch (IOException ignored) { }
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
            try {
                ul.close();
            } catch (IOException ignored) { }
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
        if (userPassword == null || userPassword.isEmpty()) throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_required);
        byte[] raw = userPassword.getBytes(StandardCharsets.UTF_8);
        byte[] domain = null;
        byte[] input = null;
        byte[] full = null;
        try {
            if (raw.length == byteCount) return Arrays.copyOf(raw, raw.length);
            domain = ("nfc-hardware-credential/v1/" + family).getBytes(StandardCharsets.UTF_8);
            input = new byte[domain.length + 1 + raw.length];
            System.arraycopy(domain, 0, input, 0, domain.length);
            input[domain.length] = 0;
            System.arraycopy(raw, 0, input, domain.length + 1, raw.length);
            full = Sha256Hash.hash(input);
            return Arrays.copyOf(full, byteCount);
        } finally {
            wipe(domain);
            wipe(input);
            wipe(full);
            wipe(raw);
        }
    }

    private static String hex(byte[] bytes) {
        try {
            return Hex.toHexString(bytes).toUpperCase(java.util.Locale.ROOT);
        } finally {
            wipe(bytes);
        }
    }

    /** Returns true only when the NDEF/user-memory range itself is covered by AUTH0. */
    public static boolean requiresPasswordForNdefRead(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return classicNdefUsesNonPublicKey(tag);
        try {
            return supportsType2Password(tag) && appearsUserDataWriteProtected(tag);
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /** Returns true only when writing the NDEF/user-memory range requires PWD_AUTH. */
    public static boolean requiresPasswordForNdefWrite(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return classicNdefUsesNonPublicKey(tag);
        try {
            return supportsType2Password(tag) && appearsUserDataWriteProtected(tag);
        }
        catch (RuntimeException e) {
            return false;
        }
    }

    /** A factory-default operation may also need access to CFG/PWD/PACK pages. */
    public static boolean requiresPasswordForFactoryRestore(Tag tag) {
        if (tag == null) return false;
        if (MifareClassic.get(tag) != null) return classicNdefUsesNonPublicKey(tag);
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        boolean connected = false;
        NtagLayout layout = null;
        try {
            ul.connect();
            connected = true;
            layout = identifyNtagLayout(ul);
            if (layout == null) return false;
            byte[][] config = readNtagConfig(ul, layout);
            try {
                return new NtagConfig(layout, config[0], config[1]).isProtectionActive();
            } finally {
                wipe(config[0]);
                wipe(config[1]);
            }
        } catch (IOException | RuntimeException e) {
            // If a positively identified Type-2 chip denies configuration reads, request
            // credentials rather than attempting a configuration write without authentication.
            return layout != null;
        } finally {
            if (connected) try { ul.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean supportsType2Password(Tag tag) {
        try {
            return identifyNtagLayout(tag) != null;
        }
        catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Creates or changes the hardware credential from user-facing text; the credential is derived in memory only. */
    public static void updateHardwarePasswordFromUserText(Tag tag, NtagAction action,
                                                           String currentPassword, String newPassword) throws IOException {
        if (tag == null) throw new LocalizedErrors.IOException(R.string.error_nfc_missing_tag);
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
        if (tag == null) throw new LocalizedErrors.IOException(R.string.error_nfc_missing_tag);
        byte[] current = currentPasswordHex == null || currentPasswordHex.trim().isEmpty()
                ? null : parseHex(currentPasswordHex, 4, R.string.nfc_security_current_password);
        byte[] next;
        try {
            next = newPasswordHex == null || newPasswordHex.trim().isEmpty()
                    ? null : parseHex(newPasswordHex, 4, R.string.nfc_security_new_password);
        } catch (IOException e) {
            wipe(current);
            throw e;
        }
        if (action != NtagAction.REMOVE && next == null) {
            wipe(current);
            wipe(next);
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_hex_required);
        }
        if (action != NtagAction.REMOVE && Arrays.equals(next, DEFAULT_NTAG_PASSWORD)) {
            wipe(current);
            wipe(next);
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_default_password_forbidden);
        }
        if (action == NtagAction.CHANGE && current != null && next != null && Arrays.equals(current, next)) {
            wipe(current);
            wipe(next);
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_must_differ);
        }

        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) {
            wipe(current);
            wipe(next);
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_ultralight_commands_unavailable);
        }
        byte[] configuredPack = null;
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_model_unsupported);

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
                    throw new LocalizedErrors.IOException(R.string.nfc_hardware_configuration_unreadable, readError);
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
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_cfglock_enabled);
            }
            if (config.isProtectionActive() && !suppliedAuth) {
                if (current == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_already_active);
                authenticate(ul, current);
                suppliedAuth = true;
            } else if (suppliedAuth && current != null) {
                // Authentication was already verified above; do not send duplicate attempts.
            }

            if (action == NtagAction.CHANGE && !config.isProtectionActive()) {
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_inactive);
            }
            if (action == NtagAction.REMOVE && !config.isProtectionActive()) {
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_protection_unknown);
            }
            if (config.isProtectionActive() && current == null) {
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_current_password_required);
            }
            if (config.isProtectionActive() && !suppliedAuth) {
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_auth_failed);
            }

            if (action == NtagAction.REMOVE) {
                // The current credential has already passed PWD_AUTH and CFGLOCK is clear.
                // Disable PROT/AUTH0, restore the documented factory PWD/PACK, then verify all
                // three postconditions before reporting success. PWD/PACK are never readable, so
                // the final PWD_AUTH + PACK response is the only valid credential verification.
                byte[] openConfig0 = Arrays.copyOf(config.config0, 4);
                byte[] openAccess = Arrays.copyOf(config.accessPage, 4);
                byte[] defaultPackPage = new byte[] { 0, 0, 0, 0 };
                byte[] expectedPack = new byte[] { 0, 0 };
                try {
                    openConfig0[3] = (byte) 0xFF;
                    openAccess[0] = (byte) (config.access & ~0x80);
                    writePage(ul, layout.config0Page, openConfig0);
                    writePage(ul, layout.accessPage, openAccess);
                    writePage(ul, layout.passwordPage, DEFAULT_NTAG_PASSWORD);
                    writePage(ul, layout.packPage, defaultPackPage);

                    byte[][] verifyPages = readNtagConfig(ul, layout);
                    try {
                        NtagConfig verifiedConfig = new NtagConfig(layout, verifyPages[0], verifyPages[1]);
                        if (verifiedConfig.auth0 != 0xFF || (verifiedConfig.access & 0x80) != 0
                                || (verifiedConfig.access & 0x40) != 0) {
                            throw new LocalizedErrors.IOException(R.string.nfc_hardware_write_verify_failed);
                        }
                    } finally {
                        wipe(verifyPages[0]);
                        wipe(verifyPages[1]);
                    }
                    authenticateExpectedPack(ul, DEFAULT_NTAG_PASSWORD, expectedPack);
                } finally {
                    wipe(openConfig0);
                    wipe(openAccess);
                    wipe(defaultPackPage);
                    wipe(expectedPack);
                }
                return;
            }

            // PWD and PACK are never readable. Use a per-operation random PACK rather than the
            // public factory value, and write it before PWD so a PACK-write failure cannot
            // unexpectedly replace the currently usable password.
            configuredPack = new byte[2];
            PACK_RANDOM.nextBytes(configuredPack);
            byte[] packPage = new byte[] { configuredPack[0], configuredPack[1], 0, 0 };
            try {
                writePage(ul, layout.packPage, packPage);
            }
            finally {
                wipe(packPage);
            }
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
            try {
                ul.close();
            } catch (IOException ignored) { }
            wipe(current);
            wipe(next);
            wipe(configuredPack);
        }
    }

    /** Detects any active hardware password scope, including CFG/PWD/PACK-only protection. */
    public static boolean appearsPasswordWriteProtected(Tag tag) {
        return appearsType2PasswordProtection(tag, false);
    }

    /** Detects whether the actual user-data pages are protected by AUTH0. */
    private static boolean appearsUserDataWriteProtected(Tag tag) {
        return appearsType2PasswordProtection(tag, true);
    }

    private static boolean appearsType2PasswordProtection(Tag tag, boolean userMemoryOnly) {
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
            try {
                NtagConfig config = new NtagConfig(layout, configPages[0], configPages[1]);
                return userMemoryOnly ? config.isUserMemoryWriteProtected() : config.isProtectionActive();
            } finally {
                wipe(configPages[0]);
                wipe(configPages[1]);
            }
        } catch (IOException e) {
            // A positive GET_VERSION match plus inaccessible config pages is not evidence that
            // the card is unprotected. Fail closed and let the authenticated operation validate.
            return layout != null;
        } finally {
            try {
                ul.close();
            } catch (IOException ignored) { }
        }
    }

    /** User-facing interface. Type 2 passwords are converted here; MIFARE Classic dispatches to its sector driver. */
    public static byte[] readProtectedNdef(Tag tag, String userPassword) throws IOException {
        if (tag == null) throw new LocalizedErrors.IOException(R.string.error_nfc_missing_tag);
        if (MifareClassic.get(tag) != null) {
            byte[] key = classicKeyFromUser(userPassword);
            try {
                return readProtectedClassicNdef(tag, key);
            }
            finally {
                wipe(key);
            }
        }
        return readProtectedType2Ndef(tag, type2PasswordHexFromUser(userPassword));
    }

    /** User-facing interface. The supplied password is derived into the credential type required by the scanned chip. */
    public static void writeProtectedNdef(Tag tag, String userPassword, byte[] ndefMessageBytes) throws IOException {
        if (tag == null) throw new LocalizedErrors.IOException(R.string.error_nfc_missing_tag);
        if (MifareClassic.get(tag) != null) {
            byte[] key = classicKeyFromUser(userPassword);
            try {
                writeProtectedClassicNdef(tag, key, ndefMessageBytes);
            }
            finally {
                wipe(key);
            }
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
            try {
                classic.close();
            } catch (IOException ignored) { }
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
            try {
                classic.close();
            } catch (IOException ignored) { }
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
        if (classic == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_technology_unsupported);

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
            if (sectors.isEmpty()) throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_sectors_missing);

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
                    throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_change_unsafe);
                }
                sectorCredentials.add(selectedKey);
                writableSectors.add(sector);
            }

            for (int i = 0; i < writableSectors.size(); i++) {
                int sector = writableSectors.get(i);
                byte[] authKey = sectorCredentials.get(i);
                int trailerBlock = classic.sectorToBlock(sector) + classic.getBlockCountInSector(sector) - 1;
                ClassicTrailerRead trailerRead = readClassicTrailerWithCredential(classic, sector, authKey);
                if (trailerRead == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_sector_reauth_failed);
                byte[] trailer = trailerRead.bytes;
                try {
                    int condition = classicTrailerCondition(trailer);
                    boolean authenticated = (condition == 0 || condition == 1)
                            ? classic.authenticateSectorWithKeyA(sector, authKey)
                            : (condition == 3 || condition == 4)
                                    && classic.authenticateSectorWithKeyB(sector, authKey);
                    if (!authenticated) throw new LocalizedErrors.IOException(R.string.nfc_hardware_sector_access_changed);
                    System.arraycopy(next, 0, trailer, 0, 6);
                    System.arraycopy(next, 0, trailer, 10, 6);
                    classic.writeBlock(trailerBlock, trailer);
                    if (!(classic.authenticateSectorWithKeyA(sector, next)
                            || classic.authenticateSectorWithKeyB(sector, next))) {
                        throw new LocalizedErrors.IOException(R.string.nfc_hardware_new_credential_verify_failed);
                    }
                } finally {
                    wipe(trailer);
                }
            }
        } finally {
            try {
                classic.close();
            } catch (IOException ignored) { }
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
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_mad_directory_inaccessible);
        }
        int first = classic.sectorToBlock(0);
        byte[] mad1 = new byte[32];
        byte[] block1 = classic.readBlock(first + 1);
        byte[] block2 = classic.readBlock(first + 2);
        System.arraycopy(block1, 0, mad1, 0, 16);
        System.arraycopy(block2, 0, mad1, 16, 16);
        wipe(block1);
        wipe(block2);
        List<Integer> result = new ArrayList<>();
        for (int sector = 1; sector <= Math.min(15, classic.getSectorCount() - 1); sector++) {
            int off = 2 + (sector - 1) * 2;
            if (isNdefAid(mad1[off], mad1[off + 1])) result.add(sector);
        }
        wipe(mad1);
        if (classic.getSectorCount() > 16) {
            if (!classic.authenticateSectorWithKeyA(16, MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY)) {
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_mad2_directory_inaccessible);
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
        if (sectors.isEmpty()) throw new LocalizedErrors.IOException(R.string.nfc_hardware_standard_ndef_sectors_missing);
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
                if (!auth) throw new LocalizedErrors.IOException(R.string.nfc_hardware_credential_auth_failed);
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
        if (classic == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_mifare_classic_required);
        ClassicNdefData data = null;
        try {
            classic.connect();
            data = loadClassicNdefData(classic, userKey);
            return parseNdefTlv(data.bytes, 0, data.bytes.length);
        } finally {
            if (data != null) wipeClassicNdefData(data);
            try {
                classic.close();
            } catch (IOException ignored) { }
        }
    }

    private static void writeProtectedClassicNdef(Tag tag, byte[] userKey, byte[] ndefMessageBytes) throws IOException {
        if (ndefMessageBytes == null || ndefMessageBytes.length == 0) throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_empty);
        MifareClassic classic = MifareClassic.get(tag);
        if (classic == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_mifare_classic_required);
        ClassicNdefData data = null;
        int written = 0;
        try {
            classic.connect();
            data = loadClassicNdefData(classic, userKey);
            int start = findNdefTlvStart(data.bytes, 0, data.bytes.length);
            if (start < 0) throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_tlv_unsafe);
            byte[] tlv = buildNdefTlv(ndefMessageBytes);
            int end = start + tlv.length;
            if (end >= data.bytes.length) {
                wipe(tlv);
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_classic_ndef_too_large);
            }
            byte[] updated = Arrays.copyOf(data.bytes, data.bytes.length);
            System.arraycopy(tlv, 0, updated, start, tlv.length);
            updated[end] = (byte) 0xFE;
            wipe(tlv);
            try {
                for (ClassicBlockRef ref : data.blocks) {
                    boolean changed = false;
                    for (int i = 0; i < 16; i++) {
                        if (updated[ref.offset + i] != data.bytes[ref.offset + i]) {
                            changed = true;
                            break;
                        }
                    }
                    if (!changed) continue;
                    byte[] authKey = data.authKeys[ref.sector];
                    if (authKey == null || (!classic.authenticateSectorWithKeyA(ref.sector, authKey)
                            && !classic.authenticateSectorWithKeyB(ref.sector, authKey))) {
                        throw new LocalizedErrors.IOException(R.string.nfc_hardware_auth_lost_sector, ref.sector, written);
                    }
                    byte[] block = Arrays.copyOfRange(updated, ref.offset, ref.offset + 16);
                    try {
                        classic.writeBlock(ref.block, block);
                        written++;
                    }
                    catch (IOException | RuntimeException e) {
                        throw new LocalizedErrors.IOException(R.string.nfc_hardware_write_sector_failed, e, ref.sector, written);
                    } finally {
                        wipe(block);
                    }
                }
            } finally {
                wipe(updated);
            }
            // Release this technology connection before opening a new one for read-back verification.
            try {
                classic.close();
            } catch (IOException ignored) { }
            byte[] verified = readProtectedClassicNdef(tag, userKey);
            try {
                if (!Arrays.equals(ndefMessageBytes, verified)) throw new LocalizedErrors.IOException(R.string.nfc_hardware_classic_ndef_verification_failed);
            } finally {
                wipe(verified);
            }
        } finally {
            if (data != null) wipeClassicNdefData(data);
            try {
                classic.close();
            } catch (IOException ignored) { }
        }
    }

    private static void wipeClassicNdefData(ClassicNdefData data) {
        wipe(data.bytes);
        for (byte[] key : data.authKeys) wipe(key);
    }

    /**
     * Erases user data for every technology this application can safely write. Type-2 cards are
     * erased page-by-page over their documented user area; Classic cards are erased block-by-block
     * only in sectors that accept a known/default or explicitly supplied key. Other NFC families
     * are handled through the standard writable NDEF interface, never by guessing proprietary APDUs.
     */
    public static MaintenanceResult eraseUserData(Tag tag, String userPassword) throws IOException {
        if (tag == null) throw new LocalizedErrors.IOException(R.string.error_nfc_missing_tag);
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul != null) {
            int lastPage = type2LastUserPage(ul);
            if (lastPage >= 4) {
                Type2EraseSummary erased = eraseType2UserPages(ul, lastPage, userPassword);
                boolean ultralightC = isUltralightC(tag);
                MaintenanceCode code = ultralightC
                        ? (erased.isComplete() ? MaintenanceCode.ULTRALIGHT_C_ERASED
                                : MaintenanceCode.ULTRALIGHT_C_ERASE_PARTIAL)
                        : (erased.isComplete() ? MaintenanceCode.TYPE2_ERASED
                                : MaintenanceCode.TYPE2_ERASE_PARTIAL);
                return new MaintenanceResult(code, erased.erasedPages, erased.totalPages, false,
                        erased.firstFailedPage);
            }
        }
        MifareClassic classic = MifareClassic.get(tag);
        if (classic != null) {
            ClassicSweep sweep = sweepClassicUserMemory(classic, userPassword, false);
            return new MaintenanceResult(sweep.completedSectors == sweep.totalSectors
                    ? MaintenanceCode.CLASSIC_ERASED : MaintenanceCode.CLASSIC_ERASE_PARTIAL,
                    sweep.completedSectors, sweep.totalSectors, false);
        }
        return eraseNdefData(tag);
    }

    /**
     * Restores writable user/configuration state where the chip exposes a documented path. This is
     * deliberately not advertised as a universal factory reset: UID, OTP, manufacturer data and
     * one-time lock bits cannot be reverted by software. Unsupported protocols are rejected rather
     * than being NDEF-formatted and mislabeled as factory-reset.
     */
    public static MaintenanceResult restoreFactoryDefaults(Tag tag, String userPassword) throws IOException {
        if (tag == null) throw new LocalizedErrors.IOException(R.string.error_nfc_missing_tag);
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul != null) {
            int lastPage = type2LastUserPage(ul);
            if (lastPage >= 4) {
                Type2EraseSummary erased = eraseType2UserPages(ul, lastPage, userPassword);
                // Formatting is intentionally best-effort: writable pages are cleared even when
                // a one-way page lock prevents clearing another page. Configuration reset is
                // still attempted independently and its result is reported separately.
                if (isUltralightC(tag)) {
                    boolean securityDefaultsRestored = restoreUltralightCFactorySettings(ul, userPassword);
                    MaintenanceCode code = erased.isComplete()
                            ? MaintenanceCode.ULTRALIGHT_C_FACTORY_LIMITED
                            : MaintenanceCode.ULTRALIGHT_C_FACTORY_PARTIAL;
                    return new MaintenanceResult(code, erased.erasedPages, erased.totalPages,
                            securityDefaultsRestored, erased.firstFailedPage);
                }
                NtagLayout layout = layoutForType2Reset(ul);
                boolean configRestored = layout != null
                        && restoreType2SecurityDefaults(ul, layout, userPassword);
                MaintenanceCode code = erased.isComplete()
                        ? MaintenanceCode.TYPE2_FACTORY_LIMITED : MaintenanceCode.TYPE2_FACTORY_PARTIAL;
                return new MaintenanceResult(code, erased.erasedPages, erased.totalPages,
                        configRestored, erased.firstFailedPage);
            }
        }
        MifareClassic classic = MifareClassic.get(tag);
        if (classic != null) {
            ClassicSweep sweep = sweepClassicUserMemory(classic, userPassword, true);
            return new MaintenanceResult(sweep.completedSectors == sweep.totalSectors
                    ? MaintenanceCode.CLASSIC_FACTORY_LIMITED : MaintenanceCode.CLASSIC_FACTORY_PARTIAL,
                    sweep.completedSectors, sweep.totalSectors, sweep.completedSectors == sweep.totalSectors);
        }
        return formatNdefLayer(tag);
    }

    /**
     * Formats only the NFC Forum NDEF layer for generic tags. This is intentionally reported
     * separately from chip factory reset: proprietary memory, UID, lock bits and credentials
     * are outside the guarantees of Android's Ndef/NdefFormatable interfaces.
     */
    private static MaintenanceResult formatNdefLayer(Tag tag) throws IOException {
        NdefMessage emptyMessage = new NdefMessage(
                new NdefRecord(NdefRecord.TNF_EMPTY, null, null, new byte[0]));
        // An already-NDEF-formatted tag has no standard Android "reformat" operation.
        // Do not make Format a second spelling of Erase; only NdefFormatable tags can be
        // formatted here. Existing NDEF tags must use Erase for content removal.
        if (Ndef.get(tag) != null) {
            return new MaintenanceResult(MaintenanceCode.FACTORY_UNSUPPORTED, 0, 0, false);
        }

        NdefFormatable formatable = NdefFormatable.get(tag);
        if (formatable == null) {
            return new MaintenanceResult(MaintenanceCode.FACTORY_UNSUPPORTED, 0, 0, false);
        }

        try {
            formatable.connect();
            formatable.format(emptyMessage);
        } catch (FormatException e) {
            throw new LocalizedErrors.IOException(R.string.nfc_security_operation_failed_generic, e);
        } finally {
            try { formatable.close(); } catch (IOException ignored) { }
        }

        Ndef formatted = Ndef.get(tag);
        if (formatted == null) {
            throw new LocalizedErrors.IOException(R.string.nfc_security_clear_unverified);
        }
        try {
            formatted.connect();
            NdefMessage check = formatted.getNdefMessage();
            if (!isSingleEmptyNdefMessage(check)) {
                throw new LocalizedErrors.IOException(R.string.nfc_security_clear_unverified);
            }
        } catch (FormatException e) {
            throw new LocalizedErrors.IOException(R.string.nfc_security_operation_failed_generic, e);
        } finally {
            try { formatted.close(); } catch (IOException ignored) { }
        }
        return new MaintenanceResult(MaintenanceCode.NDEF_FORMATTED, 1, 1, false);
    }

    private static boolean isSingleEmptyNdefMessage(NdefMessage message) {
        if (message == null || message.getRecords().length != 1) return false;
        NdefRecord record = message.getRecords()[0];
        return record.getTnf() == NdefRecord.TNF_EMPTY
                && record.getType().length == 0
                && record.getId().length == 0
                && record.getPayload().length == 0;
    }

    private static MaintenanceResult eraseNdefData(Tag tag) throws IOException {
        Ndef ndef = Ndef.get(tag);
        if (ndef == null) {
            if (IsoDep.get(tag) != null) {
                NfcType4TagDriver.Inspection info = NfcType4TagDriver.inspect(tag);
                if (info != null && info.ndefApplicationFound) {
                    NfcType4TagDriver.OperationResult operation = NfcType4TagDriver.erase(tag);
                    if (operation.success) return new MaintenanceResult(MaintenanceCode.NDEF_ERASED, 1, 1, false);
                    throw new LocalizedErrors.IOException(R.string.nfc_security_type4_erase_failed, operation.reason);
                }
            }
            throw new LocalizedErrors.IOException(R.string.nfc_security_clear_unsupported);
        }
        try {
            ndef.connect();
            if (!ndef.isWritable()) throw new LocalizedErrors.IOException(R.string.nfc_security_read_only_no_change);
            NdefRecord empty = new NdefRecord(NdefRecord.TNF_EMPTY, null, null, new byte[0]);
            NdefMessage emptyMessage = new NdefMessage(empty);
            ndef.writeNdefMessage(emptyMessage);
            NdefMessage check = ndef.getNdefMessage();
            if (!isSingleEmptyNdefMessage(check)) {
                throw new LocalizedErrors.IOException(R.string.nfc_security_clear_unverified);
            }
            return new MaintenanceResult(MaintenanceCode.NDEF_ERASED, 1, 1, false);
        } catch (FormatException e) {
            throw new LocalizedErrors.IOException(R.string.nfc_security_operation_failed_generic, e);
        } finally {
            try { ndef.close(); } catch (IOException ignored) { }
        }
    }

    private static int type2LastUserPage(MifareUltralight ul) {
        if (ul == null) return -1;
        boolean connected = false;
        try {
            ul.connect();
            connected = true;
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout != null) return layout.lastUserPage;
            // Probe the actual Ultralight C command signature regardless of Android's coarse
            // TYPE_ULTRALIGHT / TYPE_ULTRALIGHT_C value. This avoids both false positives and
            // false negatives caused by platform-specific Type-2 classification.
            try { ul.close(); ul.connect(); }
            catch (IOException | RuntimeException ignored) { return -1; }
            if (probeUltralightCHandshake(ul)) {
                try { ul.close(); ul.connect(); }
                catch (IOException | RuntimeException ignored) { return -1; }
                return 39;
            }
            // Android TYPE_ULTRALIGHT denotes the original 16-page MIFARE Ultralight family.
            if (ul.getType() == MifareUltralight.TYPE_ULTRALIGHT) return 15;
        } catch (IOException | RuntimeException ignored) {
            // Unknown model: never infer a writable page range from a coarse C classification.
        } finally {
            if (connected) try { ul.close(); } catch (IOException ignored) { }
        }
        return -1;
    }

    /**
     * Determines whether Ultralight C's AUTH0 threshold covers user pages 4..39.
     * A write probe on page 39 is deliberately not used: page 39 may be permanently
     * locked even when the rest of user memory is writable. Per-page erase handles
     * those one-way locks and reports any pages that remain non-zero.
     */
    private static byte[] ensureUltralightCUserWriteAccess(MifareUltralight ul, String userPassword)
            throws IOException {
        byte[] config = null;
        byte[] key = null;
        try {
            boolean userAreaProtected = false;
            try {
                config = readPages(ul, 40); // pages 40..43; AUTH0 is byte 0 of page 42.
                int auth0 = config[8] & 0xFF;
                userAreaProtected = auth0 <= 0x27;
            } catch (IOException configReadFailure) {
                // CFG pages can themselves be protected when AUTH0 starts at page 40 or
                // later. In that case user pages may still be public. Do not abort a wipe
                // because a configuration-only page cannot be read; let the actual user
                // page writes determine which pages are writable.
                // A NAK can return the PICC to IDLE, so reselect before any next command.
                try { ul.close(); } catch (IOException ignored) { }
                ul.connect();
                if (userPassword == null || userPassword.trim().isEmpty()) return null;
                userAreaProtected = true;
            }
            if (!userAreaProtected) return null;

            key = userPassword == null || userPassword.trim().isEmpty()
                    ? Arrays.copyOf(DEFAULT_ULTRALIGHT_C_KEY, DEFAULT_ULTRALIGHT_C_KEY.length)
                    : parseHex(userPassword, 16, R.string.nfc_security_ultralight_c_key_label);
            try {
                authenticateUltralightC(ul, key);
            } catch (IOException | RuntimeException authFailure) {
                throw new LocalizedErrors.IOException(
                        R.string.nfc_security_ultralight_c_auth_required, authFailure, 39, 0);
            }
            byte[] authenticatedKey = key;
            key = null;
            return authenticatedKey;
        } finally {
            wipe(config);
            wipe(key);
        }
    }

    /** Performs NXP MIFARE Ultralight C mutual 2-key 3DES authentication. */
    private static void authenticateUltralightC(MifareUltralight ul, byte[] key) throws IOException {
        if (key == null || key.length != 16)
            throw new LocalizedErrors.IOException(R.string.nfc_security_ultralight_c_key_format);
        byte[] expandedKey = new byte[24];
        byte[] zeroIv = new byte[8];
        byte[] challengeResponse = null;
        byte[] rndB = null;
        byte[] rndA = new byte[8];
        byte[] rotatedRndB = new byte[8];
        byte[] rotatedRndA = new byte[8];
        byte[] plain = new byte[16];
        byte[] encrypted = null;
        byte[] command = null;
        byte[] finalResponse = null;
        byte[] verifiedRndA = null;
        byte[] responseCiphertext = null;
        byte[] iv = null;
        try {
            System.arraycopy(key, 0, expandedKey, 0, 16);
            System.arraycopy(key, 0, expandedKey, 16, 8); // K1 || K2 || K1
            Cipher cipher = Cipher.getInstance("DESede/CBC/NoPadding");
            SecretKeySpec secretKey = new SecretKeySpec(expandedKey, "DESede");

            challengeResponse = ul.transceive(new byte[] {0x1A, 0x00});
            if (challengeResponse == null || challengeResponse.length != 9
                    || (challengeResponse[0] & 0xFF) != 0xAF) {
                throw new LocalizedErrors.IOException(R.string.nfc_security_ultralight_c_auth_failed);
            }
            iv = Arrays.copyOfRange(challengeResponse, 1, 9);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(zeroIv));
            rndB = cipher.doFinal(iv);
            PACK_RANDOM.nextBytes(rndA);
            rotateLeftOneByte(rndB, rotatedRndB);
            System.arraycopy(rndA, 0, plain, 0, 8);
            System.arraycopy(rotatedRndB, 0, plain, 8, 8);

            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
            encrypted = cipher.doFinal(plain);
            command = new byte[17];
            command[0] = (byte) 0xAF;
            System.arraycopy(encrypted, 0, command, 1, 16);
            finalResponse = ul.transceive(command);
            if (finalResponse == null || finalResponse.length != 9
                    || (finalResponse[0] & 0xFF) != 0x00) {
                throw new LocalizedErrors.IOException(R.string.nfc_security_ultralight_c_auth_failed);
            }
            rotateLeftOneByte(rndA, rotatedRndA);
            byte[] lastCipherBlock = Arrays.copyOfRange(encrypted, 8, 16);
            try {
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(lastCipherBlock));
                responseCiphertext = Arrays.copyOfRange(finalResponse, 1, 9);
                verifiedRndA = cipher.doFinal(responseCiphertext);
            } finally {
                wipe(lastCipherBlock);
            }
            if (!MessageDigest.isEqual(rotatedRndA, verifiedRndA)) {
                throw new LocalizedErrors.IOException(R.string.nfc_security_ultralight_c_auth_failed);
            }
        } catch (LocalizedErrors.IOException e) {
            throw e;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new LocalizedErrors.IOException(R.string.nfc_security_ultralight_c_auth_failed, e);
        } finally {
            wipe(expandedKey); wipe(zeroIv); wipe(challengeResponse); wipe(rndB); wipe(rndA);
            wipe(rotatedRndA); wipe(rotatedRndB); wipe(plain); wipe(encrypted); wipe(command);
            wipe(finalResponse); wipe(verifiedRndA); wipe(responseCiphertext); wipe(iv);
        }
    }

    private static void rotateLeftOneByte(byte[] source, byte[] destination) {
        for (int i = 0; i < 7; i++) destination[i] = source[i + 1];
        destination[7] = source[0];
    }

    private static Type2EraseSummary eraseType2UserPages(
            MifareUltralight ul, int lastUserPage, String userPassword) throws IOException {
        if (lastUserPage < 4) throw new LocalizedErrors.IOException(R.string.nfc_hardware_unsupported_tag_model);
        boolean connected = false;
        byte[] password = null;
        byte[] ultralightCKey = null;
        try {
            ul.connect();
            connected = true;
            NtagLayout layout = null;
            try { layout = identifyNtagLayout(ul); } catch (IOException | RuntimeException ignored) { }
            boolean ultralightC = false;
            if (layout == null && (lastUserPage > 15
                    || ul.getType() == MifareUltralight.TYPE_ULTRALIGHT_C)) {
                // GET_VERSION may leave the PICC in IDLE. Re-select before probing Ultralight C.
                ul.close();
                connected = false;
                ul.connect();
                connected = true;
                ultralightC = probeUltralightCHandshake(ul);
                if (ultralightC) {
                    ul.close();
                    connected = false;
                    ul.connect();
                    connected = true;
                }
            }
            if (layout == null && !ultralightC && lastUserPage > 15) {
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_unsupported_tag_model);
            }

            if (layout != null) {
                byte[][] config = null;
                try {
                    config = readNtagConfig(ul, layout);
                } catch (IOException configReadFailure) {
                    // Do not silently continue when the configuration page cannot be read:
                    // this may mean the tag protects user pages from AUTH0. Request the
                    // credential before attempting any destructive write.
                    if (userPassword == null || userPassword.trim().isEmpty()) {
                        throw new LocalizedErrors.IOException(
                                R.string.nfc_hardware_password_required, configReadFailure);
                    }
                    // A protected READ may return NAK and leave the PICC in IDLE. Re-select
                    // before PWD_AUTH instead of authenticating on a stale RF session.
                    try { ul.close(); } catch (IOException ignored) { }
                    connected = false;
                    ul.connect();
                    connected = true;
                    password = passwordBytesFromUser(userPassword, 4, "type2");
                    authenticate(ul, password);
                }
                if (config != null) {
                    try {
                        NtagConfig state = new NtagConfig(layout, config[0], config[1]);
                        if (state.isUserMemoryWriteProtected()) {
                            if (userPassword == null || userPassword.trim().isEmpty())
                                throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_required);
                            password = passwordBytesFromUser(userPassword, 4, "type2");
                            authenticate(ul, password);
                        }
                    } finally {
                        wipe(config[0]);
                        wipe(config[1]);
                    }
                }
            } else if (ultralightC) {
                // Ultralight C uses 2-key 3DES mutual authentication, not PWD_AUTH.
                ultralightCKey = ensureUltralightCUserWriteAccess(ul, userPassword);
            }

            // Specialist NFC tools (for example, NtagToolkit and NFC Tools) distinguish
            // page-level writes from NDEF formatting. Never preflight by writing every page
            // back unchanged: one permanently locked page would abort the entire erase.
            // Attempt each documented user page independently, then verify the actual memory.
            byte[] zero = new byte[4];
            int completedWriteAttempts = 0;
            try {
                int firstPage = ultralightC ? lastUserPage : 4;
                int endPage = ultralightC ? 4 : lastUserPage;
                int step = ultralightC ? -1 : 1;
                for (int page = firstPage; step > 0 ? page <= endPage : page >= endPage; page += step) {
                    try {
                        writePage(ul, page, zero);
                        completedWriteAttempts++;
                    } catch (IOException | RuntimeException pageNotWritable) {
                        // A NAK may leave the PICC in IDLE. Re-select before continuing and
                        // restore the authentication session when the user area is protected.
                        try {
                            try { ul.close(); } catch (IOException ignored) { }
                            connected = false;
                            ul.connect();
                            connected = true;
                            if (password != null) authenticate(ul, password);
                            if (ultralightCKey != null) authenticateUltralightC(ul, ultralightCKey);
                        } catch (IOException | RuntimeException reselectFailure) {
                            reselectFailure.addSuppressed(pageNotWritable);
                            if (reselectFailure instanceof IOException) throw (IOException) reselectFailure;
                            throw new LocalizedErrors.IOException(
                                    R.string.nfc_security_erase_page_write_failed,
                                    reselectFailure, page, completedWriteAttempts);
                        }
                        // Lock bits and access conditions are page-specific. Continue so one
                        // protected page cannot prevent erasing every other writable page.
                    }
                }
            } finally {
                wipe(zero);
            }

            byte[] verify = layout != null
                    ? readType2UserMemory(ul, layout)
                    : readMemory(ul, lastUserPage + 1);
            try {
                int verifiedErased = 0;
                int firstFailedPage = -1;
                for (int page = 4; page <= lastUserPage; page++) {
                    boolean pageZero = true;
                    for (int j = 0; j < 4; j++) {
                        if (verify[page * 4 + j] != 0) {
                            pageZero = false;
                            break;
                        }
                    }
                    if (pageZero) {
                        verifiedErased++;
                    } else if (firstFailedPage < 0) {
                        firstFailedPage = page;
                    }
                }
                return new Type2EraseSummary(verifiedErased, lastUserPage - 3, firstFailedPage);
            } finally {
                wipe(verify);
            }
        } finally {
            if (connected) try { ul.close(); } catch (IOException ignored) { }
            wipe(password);
            wipe(ultralightCKey);
        }
    }

    /**
     * Restores the writable Ultralight C authentication configuration and 3DES key to the
     * NXP delivery values when their one-way lock bits permit it. The UID, OTP, counter and
     * permanent lock bits are deliberately never written.
     */
    private static boolean restoreUltralightCFactorySettings(MifareUltralight ul, String userPassword) {
        if (ul == null) return false;
        byte[] key = null;
        byte[] lockAndConfig = null;
        byte[] auth0Default = new byte[] {0x30, 0, 0, 0};
        byte[] auth1Default = new byte[] {0, 0, 0, 0};
        boolean connected = false;
        boolean success = false;
        try {
            key = userPassword == null || userPassword.trim().isEmpty()
                    ? Arrays.copyOf(DEFAULT_ULTRALIGHT_C_KEY, DEFAULT_ULTRALIGHT_C_KEY.length)
                    : parseHex(userPassword, 16, R.string.nfc_security_ultralight_c_key_label);
            ul.connect();
            connected = true;
            if (!probeUltralightCHandshake(ul)) return false;
            // The handshake probe leaves the PICC awaiting the next 3DES frame; reselect
            // before the page-40 read or the mutual-authentication routine.
            ul.close();
            connected = false;
            ul.connect();
            connected = true;

            // READ page 40 returns pages 40..43: lock byte 2/3, AUTH0 and AUTH1.
            try {
                lockAndConfig = readPages(ul, 40);
            } catch (IOException protectedConfiguration) {
                // A protected READ can return a NAK and return the PICC to IDLE. Reselect first.
                ul.close();
                ul.connect();
                authenticateUltralightC(ul, key);
                lockAndConfig = readPages(ul, 40);
            }

            // Page 40 (0x28) stores lock byte 2 at byte offset 0 and lock byte 3
            // at byte offset 1. Byte offset 3 is the fixed BDh value on this chip.
            int lockByte3 = lockAndConfig[1] & 0xFF;
            int currentAuth0 = lockAndConfig[8] & 0xFF;
            int currentAuth1 = lockAndConfig[12] & 0xFF;
            boolean auth0Locked = (lockByte3 & 0x20) != 0;
            boolean auth1Locked = (lockByte3 & 0x40) != 0;
            boolean keyLocked = (lockByte3 & 0x80) != 0;
            boolean auth0AlreadyDefault = currentAuth0 == 0x30;
            boolean auth1AlreadyDefault = currentAuth1 == 0x00;

            // The configured authentication threshold covers the configuration/key pages.
            // Authenticate before attempting any write protected by AUTH0.
            boolean authenticationRequired = currentAuth0 >= 0x03 && currentAuth0 < 0x30
                    && currentAuth0 <= 0x2F;
            if (authenticationRequired) authenticateUltralightC(ul, key);

            boolean auth0Restored = auth0AlreadyDefault;
            if (!auth0Locked && !auth0AlreadyDefault) {
                writePage(ul, 42, auth0Default);
                auth0Restored = true;
            }

            boolean auth1Restored = auth1AlreadyDefault;
            if (!auth1Locked && !auth1AlreadyDefault) {
                writePage(ul, 43, auth1Default);
                auth1Restored = true;
            }

            // Do not replace the key if a locked AUTH0 leaves the chip permanently configured
            // to require authentication; that would silently change the credential while
            // preserving an irreversible protected state.
            boolean authAreaCanBeRestored = auth0Restored;
            boolean keyWrittenOrAlreadyDefault = false;
            if (authAreaCanBeRestored && !keyLocked) {
                for (int page = 44; page <= 47; page++) {
                    byte[] part = Arrays.copyOfRange(DEFAULT_ULTRALIGHT_C_KEY,
                            (page - 44) * 4, (page - 43) * 4);
                    try { writePage(ul, page, part); }
                    finally { wipe(part); }
                }
                keyWrittenOrAlreadyDefault = true;
            }

            ul.close();
            connected = false;
            // Re-activation is required before a re-programmed 3DES key becomes active.
            ul.connect();
            connected = true;
            byte[] verify = readPages(ul, 40);
            try {
                boolean verifyAuth0 = (verify[8] & 0xFF) == 0x30;
                boolean verifyAuth1 = (verify[12] & 0xFF) == 0x00;
                if (!verifyAuth0 || !verifyAuth1 || !auth1Restored) return false;
                // Key pages are write-only. The only safe verification is a fresh mutual
                // authentication with the delivery-state key.
                authenticateUltralightC(ul, DEFAULT_ULTRALIGHT_C_KEY);
                success = keyWrittenOrAlreadyDefault || keyLocked;
            } finally {
                wipe(verify);
            }
            return success;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (connected) try { ul.close(); } catch (IOException ignored) { }
            wipe(key);
            wipe(lockAndConfig);
            wipe(auth0Default);
            wipe(auth1Default);
        }
    }

    private static NtagLayout layoutForType2Reset(MifareUltralight ul) {
        if (ul == null) return null;
        boolean connected = false;
        try {
            ul.connect();
            connected = true;
            return identifyNtagLayout(ul);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connected) try { ul.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean restoreType2SecurityDefaults(MifareUltralight ul, NtagLayout layout, String userPassword) {
        byte[] password = null;
        try {
            // The caller has just completed a separate user-memory pass; open a fresh session.
            ul.connect();
            boolean authenticated = false;
            if (userPassword != null && !userPassword.isEmpty()) {
                password = passwordBytesFromUser(userPassword, 4, "type2");
            }
            byte[][] pages;
            try {
                pages = readNtagConfig(ul, layout);
            } catch (IOException protectedConfig) {
                if (password == null) return false;
                // A denied READ may have de-selected the Type-2 PICC. Start a fresh
                // activation before sending the PWD_AUTH command.
                try { ul.close(); } catch (IOException ignored) { }
                ul.connect();
                authenticate(ul, password);
                authenticated = true;
                pages = readNtagConfig(ul, layout);
            }
            byte[] config0 = pages[0];
            byte[] access = pages[1];
            try {
                boolean configLocked = (access[0] & 0x40) != 0; // CFGLCK permanently locks CFG0/CFG1 only.
                NtagConfig state = new NtagConfig(layout, config0, access);
                if (state.isProtectionActive() && !authenticated) {
                    if (password == null) return false;
                    authenticate(ul, password);
                    authenticated = true;
                }
                byte[] defaultConfig = new byte[] {(byte) layout.defaultModByte, 0, 0, (byte) 0xFF};
                byte[] defaultAccess = new byte[] {0, (byte) layout.defaultVctid, 0, 0};
                byte[] defaultPassword = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
                byte[] defaultPack = new byte[] {0, 0, 0, 0};
                try {
                    // NXP allows PWD/PACK to be written even when CFGLOCK is set.
                    // Restore those independently, but never claim full factory config if
                    // the one-way CFGLOCK prevents restoring CFG0/CFG1.
                    if (!configLocked) writePage(ul, layout.accessPage, defaultAccess);
                    writePage(ul, layout.passwordPage, defaultPassword);
                    writePage(ul, layout.packPage, defaultPack);
                    if (!configLocked) writePage(ul, layout.config0Page, defaultConfig); // AUTH0 disabled last.
                    byte[][] verify = readNtagConfig(ul, layout);
                    try {
                        boolean configVerified = configLocked
                                ? Arrays.equals(verify[0], config0) && Arrays.equals(verify[1], access)
                                : Arrays.equals(verify[0], defaultConfig) && Arrays.equals(verify[1], defaultAccess);
                        if (!configVerified) return false;
                        authenticateExpectedPack(ul, defaultPassword, new byte[] {0, 0});
                        return !configLocked;
                    } finally { wipe(verify[0]); wipe(verify[1]); }
                } finally {
                    wipe(defaultConfig); wipe(defaultAccess); wipe(defaultPassword); wipe(defaultPack);
                }
            } finally { wipe(config0); wipe(access); }
        } catch (IOException | RuntimeException ignored) {
            return false;
        } finally {
            try { ul.close(); } catch (IOException ignored) { }
            wipe(password);
        }
    }

    private static final class ClassicSweep {
        final int completedSectors;
        final int totalSectors;
        ClassicSweep(int completedSectors, int totalSectors) {
            this.completedSectors = completedSectors;
            this.totalSectors = totalSectors;
        }
    }

    private static final class Type2EraseSummary {
        final int erasedPages;
        final int totalPages;
        final int firstFailedPage;
        Type2EraseSummary(int erasedPages, int totalPages, int firstFailedPage) {
            this.erasedPages = erasedPages;
            this.totalPages = totalPages;
            this.firstFailedPage = firstFailedPage;
        }
        boolean isComplete() { return erasedPages == totalPages; }
    }

    private static byte[] classicMaintenanceKeyFromUser(String value) throws IOException {
        String normalized = value == null ? "" : value.trim().replace(" ", "").replace(":", "").replace("-", "");
        if (normalized.matches("(?i)[0-9a-f]{12}")) {
            try { return Hex.decodeStrict(normalized); }
            catch (DecoderException e) {
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_hex_chars_invalid,
                        LocalizedErrors.resource(R.string.nfc_hardware_password_label));
            }
        }
        return classicKeyFromUser(value);
    }

    private static ClassicSweep sweepClassicUserMemory(
            MifareClassic classic, String userPassword, boolean resetTrailers) throws IOException {
        byte[] userKey = null;
        byte[] defaultKey = Arrays.copyOf(MifareClassic.KEY_DEFAULT, 6);
        byte[] forumKey = Arrays.copyOf(MifareClassic.KEY_NFC_FORUM, 6);
        byte[] zeroBlock = new byte[16];
        byte[] factoryTrailer = new byte[] {
                (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,
                (byte)0xFF,0x07,(byte)0x80,0x69,
                (byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF,(byte)0xFF
        };
        int completed = 0;
        int total = classic.getSectorCount();
        try {
            if (userPassword != null && !userPassword.isEmpty()) userKey = classicMaintenanceKeyFromUser(userPassword);
            classic.connect();
            for (int sector = 0; sector < total; sector++) {
                int firstBlock = classic.sectorToBlock(sector);
                int blocksInSector = classic.getBlockCountInSector(sector);
                int trailerBlock = firstBlock + blocksInSector - 1;
                byte[][] candidates = userKey == null
                        ? new byte[][] {forumKey, defaultKey}
                        : new byte[][] {userKey, forumKey, defaultKey};

                // MIFARE Classic permissions are defined per block, not as a single
                // all-or-nothing sector write flag. Follow MIFARE Classic Tool's model:
                // authenticate sector, attempt each data block, verify it, and retain a
                // partial result when an individual block's access bits reject the write.
                boolean[] blockCleared = new boolean[trailerBlock - firstBlock];
                for (byte[] candidate : candidates) {
                    for (int keyType = 0; keyType < 2; keyType++) {
                        boolean authenticated = keyType == 0
                                ? classic.authenticateSectorWithKeyA(sector, candidate)
                                : classic.authenticateSectorWithKeyB(sector, candidate);
                        if (!authenticated) continue;

                        for (int block = firstBlock; block < trailerBlock; block++) {
                            if (sector == 0 && block == 0) continue;
                            int offset = block - firstBlock;
                            if (blockCleared[offset]) continue;
                            try {
                                // A block may be write-only under its access conditions.
                                // Try the actual zero write rather than requiring a same-value
                                // read/write preflight that can reject an otherwise erasable block.
                                classic.writeBlock(block, zeroBlock);
                                byte[] verify = classic.readBlock(block);
                                try {
                                    if (verify != null && verify.length == 16
                                            && Arrays.equals(verify, zeroBlock)) {
                                        blockCleared[offset] = true;
                                    }
                                } finally {
                                    wipe(verify);
                                }
                            } catch (TagLostException tagLost) {
                                throw tagLost;
                            } catch (IOException | RuntimeException blockFailure) {
                                // Continue with the next block and the other key role/candidate.
                            }
                        }
                        boolean allCleared = true;
                        for (int block = firstBlock; block < trailerBlock; block++) {
                            if (sector == 0 && block == 0) continue;
                            if (!blockCleared[block - firstBlock]) {
                                allCleared = false;
                                break;
                            }
                        }
                        if (allCleared) break;
                    }
                    boolean allCleared = true;
                    for (int block = firstBlock; block < trailerBlock; block++) {
                        if (sector == 0 && block == 0) continue;
                        if (!blockCleared[block - firstBlock]) {
                            allCleared = false;
                            break;
                        }
                    }
                    if (allCleared) break;
                }

                boolean allDataCleared = true;
                for (int block = firstBlock; block < trailerBlock; block++) {
                    if (sector == 0 && block == 0) continue;
                    if (!blockCleared[block - firstBlock]) {
                        allDataCleared = false;
                        break;
                    }
                }

                boolean sectorOk = allDataCleared;
                if (sectorOk && resetTrailers) {
                    boolean trailerRestored = false;
                    // Access rights for the sector trailer may differ from data-block rights.
                    // Try every credential/role independently; never infer trailer access from
                    // a successful data-block write.
                    for (byte[] candidate : candidates) {
                        for (int keyType = 0; keyType < 2 && !trailerRestored; keyType++) {
                            boolean authenticated = keyType == 0
                                    ? classic.authenticateSectorWithKeyA(sector, candidate)
                                    : classic.authenticateSectorWithKeyB(sector, candidate);
                            if (!authenticated) continue;
                            try {
                                classic.writeBlock(trailerBlock, factoryTrailer);
                                boolean defaultAuth = classic.authenticateSectorWithKeyA(sector, defaultKey);
                                if (!defaultAuth) continue;
                                byte[] verifiedTrailer = classic.readBlock(trailerBlock);
                                try {
                                    trailerRestored = verifiedTrailer != null && verifiedTrailer.length == 16
                                            && verifiedTrailer[6] == factoryTrailer[6]
                                            && verifiedTrailer[7] == factoryTrailer[7]
                                            && verifiedTrailer[8] == factoryTrailer[8]
                                            && verifiedTrailer[9] == factoryTrailer[9];
                                } finally {
                                    wipe(verifiedTrailer);
                                }
                            } catch (TagLostException tagLost) {
                                throw tagLost;
                            } catch (IOException | RuntimeException trailerFailure) {
                                // This sector's trailer cannot be restored with this key/role.
                            }
                        }
                        if (trailerRestored) break;
                    }
                    sectorOk = trailerRestored;
                }
                if (sectorOk) completed++;
            }
            return new ClassicSweep(completed, total);
        } finally {
            try { classic.close(); } catch (IOException ignored) { }
            wipe(userKey); wipe(defaultKey); wipe(forumKey); wipe(zeroBlock); wipe(factoryTrailer);
        }
    }

    /** Returns the complete public user-memory region after authenticating a protected Type-2 tag. */
    public static byte[] readProtectedType2Memory(Tag tag, String userPassword) throws IOException {
        if (tag == null) throw new LocalizedErrors.IOException(R.string.error_nfc_missing_tag);
        byte[] password = passwordBytesFromUser(userPassword, 4, "type2");
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) {
            wipe(password);
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_ultralight_commands_missing);
        }
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_unsupported_tag_model);
            authenticate(ul, password);
            return readType2UserMemory(ul, layout);
        } finally {
            try {
                ul.close();
            } catch (IOException ignored) { }
            wipe(password);
        }
    }

    private static byte[] readProtectedType2Ndef(Tag tag, String passwordHex) throws IOException {
        byte[] password = parseHex(passwordHex, 4, R.string.nfc_hardware_password_label);
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) {
            wipe(password);
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_ultralight_commands_missing);
        }
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_unsupported_tag_model);
            authenticate(ul, password);
            byte[] memory = readType2UserMemory(ul, layout);
            try {
                return parseNdefTlv(memory, 16, (layout.lastUserPage + 1) * 4);
            }
            finally {
                Arrays.fill(memory, (byte) 0);
            }
        } finally {
            try {
                ul.close();
            } catch (IOException ignored) { }
            wipe(password);
        }
    }

    /** Raw authenticated Type-2 NDEF write for supported password-protected Type 2 chips. Only a plain NDEF TLV at the normal
     * page-4 user-memory start is modified;
     unknown/private TLV layouts are refused. */
    private static void writeProtectedType2Ndef(Tag tag, String passwordHex, byte[] ndefMessageBytes) throws IOException {
        byte[] password = parseHex(passwordHex, 4, R.string.nfc_hardware_password_label);
        if (ndefMessageBytes == null || ndefMessageBytes.length == 0) {
            wipe(password);
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_empty);
        }
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) {
            wipe(password);
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_ultralight_commands_missing);
        }
        try {
            ul.connect();
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout == null) throw new LocalizedErrors.IOException(R.string.nfc_hardware_unsupported_tag_model);
            authenticate(ul, password);
            int memoryEnd = (layout.lastUserPage + 1) * 4;
            byte[] memory = readType2UserMemory(ul, layout);
            try {
                int tlvStart = findNdefTlvStart(memory, 16, memoryEnd);
                if (tlvStart < 16) throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_layout_unsafe);
                byte[] tlv = buildNdefTlv(ndefMessageBytes);
                int end = tlvStart + tlv.length;
                if (end >= memoryEnd) throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_user_memory_too_small);
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
                byte[] verifyMemory = readType2UserMemory(ul, layout);
                byte[] verified;
                try {
                    verified = parseNdefTlv(verifyMemory, 16, memoryEnd);
                } finally {
                    Arrays.fill(verifyMemory, (byte) 0);
                }
                try {
                    if (!Arrays.equals(ndefMessageBytes, verified)) {
                        throw new LocalizedErrors.IOException(R.string.nfc_hardware_write_verify_failed);
                    }
                } finally {
                    Arrays.fill(verified, (byte) 0);
                }
            } finally {
                Arrays.fill(memory, (byte) 0);
            }
        } finally {
            try {
                ul.close();
            } catch (IOException ignored) { }
            wipe(password);
        }
    }

    /** Clears the NDEF payload through the driver's authenticated memory path. */
    public static void clearProtectedNdef(Tag tag, String userPassword) throws IOException {
        NdefRecord emptyRecord = new NdefRecord(NdefRecord.TNF_EMPTY, null, null, new byte[0]);
        writeProtectedNdef(tag, userPassword, new NdefMessage(emptyRecord).toByteArray());
    }


    /**
     * Returns the last page belonging to public user memory for supported Type-2 chips.
     * Must be called while the supplied Ultralight connection is open. OTP, lock bytes,
     * counters, authentication configuration and secret-key pages are deliberately excluded.
     * Unknown chips return -1 rather than guessing a writable range.
     */
    public static int getType2LastUserPage(MifareUltralight ul) {
        if (ul == null) return -1;
        try {
            // Caller keeps the same MifareUltralight connection open for GET_VERSION and READ.
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout != null) return layout.lastUserPage;
        } catch (IOException | RuntimeException ignored) {
            // Legacy Ultralight C and original Ultralight do not return the NXP version signature.
        }
        {
            // Re-select after a rejected GET_VERSION before sending Ultralight C AUTHENTICATE.
            try { ul.close(); ul.connect(); }
            catch (IOException | RuntimeException ignored) { return -1; }
            if (probeUltralightCHandshake(ul)) {
                // Probe starts the Ultralight C authentication state. Reselect before the caller
                // begins its page-reading loop so no ordinary READ is sent mid-handshake.
                try {
                    ul.close();
                    ul.connect();
                } catch (IOException | RuntimeException ignored) {
                    return -1;
                }
                return 39;
            }
        }
        if (ul.getType() == MifareUltralight.TYPE_ULTRALIGHT) return 15;
        return -1;
    }

    /** Must be called with an active MifareUltralight connection. */
    public static boolean isUltralightCOnConnection(MifareUltralight ul) {
        if (ul == null) return false;
        try {
            if (identifyNtagLayout(ul) != null) return false;
        } catch (IOException | RuntimeException ignored) {
            // The real Ultralight C command set has no NTAG/EV1 GET_VERSION response.
        }
        try { ul.close(); ul.connect(); }
        catch (IOException | RuntimeException ignored) { return false; }
        return probeUltralightCHandshake(ul);
    }

    public static boolean isUltralightC(Tag tag) {
        return tag != null && identify(tag).family == Family.MIFARE_ULTRALIGHT_C;
    }

    /**
     * Positively identifies an Ultralight C via the first step of NXP's 3DES AUTHENTICATE
     * protocol. A valid response is AFh followed by the eight-byte encrypted RndB challenge.
     * This leaves the PICC in an authentication exchange, so callers must reselect it before
     * sending any unrelated command.
     */
    private static boolean probeUltralightCHandshake(MifareUltralight ul) {
        if (ul == null) return false;
        byte[] response = null;
        try {
            response = ul.transceive(new byte[] {0x1A, 0x00});
            return response != null && response.length == 9 && (response[0] & 0xFF) == 0xAF;
        } catch (IOException | RuntimeException ignored) {
            return false;
        } finally {
            wipe(response);
        }
    }

    /** Opens a temporary connection and confirms the Ultralight C challenge, never the Android enum alone. */
    private static boolean probeUltralightCHandshake(Tag tag) {
        MifareUltralight ul = MifareUltralight.get(tag);
        if (ul == null) return false;
        boolean connected = false;
        try {
            ul.connect();
            connected = true;
            NtagLayout layout = identifyNtagLayout(ul);
            if (layout != null) return false;
            // A rejected GET_VERSION may put the PICC back in IDLE. Re-activate before
            // issuing the Ultralight C authentication command.
            ul.close();
            connected = false;
            ul.connect();
            connected = true;
            return probeUltralightCHandshake(ul);
        } catch (IOException | RuntimeException ignored) {
            return false;
        } finally {
            if (connected) try { ul.close(); } catch (IOException ignored) { }
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
            if (wasConnected) try {
                ul.close();
            } catch (IOException ignored) { }
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
            // GET_VERSION subtype 01h is the 17 pF variant; 02h is the 50 pF
            // high-capacitance variant. NXP defines STRG_MOD_EN's delivery value as
            // 0 for MF0ULx1 and 1 for MF0ULHx1 (bit 2 of MOD).
            int defaultMod = subtype == 0x02 ? 0x04 : 0x00;
            if (size == 0x0B) {
                return new NtagLayout(Family.MIFARE_ULTRALIGHT_EV1_48, 0x10, 0x0F, defaultMod, 0x05);
            }
            if (size == 0x0E) {
                return new NtagLayout(Family.MIFARE_ULTRALIGHT_EV1_128, 0x25, 0x23, defaultMod, 0x05);
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
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_type2_configuration_unreadable);
        }
        byte[] config0 = Arrays.copyOfRange(response, 0, 4);
        byte[] access = Arrays.copyOfRange(response, 4, 8);
        Arrays.fill(response, (byte) 0);
        return new byte[][] { config0, access };
    }

    private static byte[] readPages(MifareUltralight ul, int page) throws IOException {
        byte[] response = ul.transceive(new byte[] { CMD_READ, (byte) page });
        if (response == null || response.length != 16) throw new LocalizedErrors.IOException(R.string.nfc_hardware_page_read_failed, page);
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

    /**
     * Reads only the documented user-memory range for a detected NTAG/Ultralight EV1.
     * READ always returns four pages, so the last READ on NTAG215/216 would spill into
     * the dynamic-lock/configuration pages (0x82/0x83 and 0xE2/0xE3). When those pages are
     * protected, that unrelated tail can NAK the entire READ even though user pages are
     * writable. FAST_READ is used only for the final short tail on the two affected layouts.
     */
    private static byte[] readType2UserMemory(MifareUltralight ul, NtagLayout layout) throws IOException {
        if (ul == null || layout == null || layout.lastUserPage < 4) {
            throw new LocalizedErrors.IOException(R.string.nfc_hardware_unsupported_tag_model);
        }
        int pageCount = layout.lastUserPage + 1;
        byte[] memory = new byte[pageCount * 4];
        int page = 0;
        try {
            // Use ordinary READ only for complete four-page groups contained in user memory.
            for (; page + 3 <= layout.lastUserPage; page += 4) {
                byte[] response = readPages(ul, page);
                try {
                    System.arraycopy(response, 0, memory, page * 4, 16);
                } finally {
                    wipe(response);
                }
            }

            // NTAG215/216 end their user area two pages into a four-page READ group.
            // FAST_READ returns exactly the requested pages and never touches lock/config pages.
            if (page <= layout.lastUserPage) {
                boolean fastReadSupported = layout.family == Family.NTAG215
                        || layout.family == Family.NTAG216;
                if (!fastReadSupported) {
                    throw new LocalizedErrors.IOException(R.string.nfc_hardware_page_read_failed, page);
                }
                byte[] response = ul.transceive(new byte[] {
                        CMD_FAST_READ, (byte) page, (byte) layout.lastUserPage
                });
                int expectedLength = (layout.lastUserPage - page + 1) * 4;
                if (response == null || response.length != expectedLength) {
                    wipe(response);
                    throw new LocalizedErrors.IOException(R.string.nfc_hardware_page_read_failed, page);
                }
                try {
                    System.arraycopy(response, 0, memory, page * 4, expectedLength);
                } finally {
                    wipe(response);
                }
            }
            return memory;
        } catch (IOException | RuntimeException e) {
            wipe(memory);
            throw e;
        }
    }

    private static void writePage(MifareUltralight ul, int page, byte[] data) throws IOException {
        if (data == null || data.length != 4) throw new LocalizedErrors.IOException(R.string.nfc_hardware_type2_page_invalid);
        byte[] copy = Arrays.copyOf(data, 4);
        try {
            ul.writePage(page, copy);
        }
        finally {
            Arrays.fill(copy, (byte) 0);
        }
    }

    private static void authenticate(MifareUltralight ul, byte[] password) throws IOException {
        byte[] command = new byte[5];
        byte[] response = null;
        try {
            command[0] = CMD_PWD_AUTH;
            System.arraycopy(password, 0, command, 1, 4);
            response = ul.transceive(command);
            if (response == null || response.length != 2) {
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_check_failed);
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
                throw new LocalizedErrors.IOException(R.string.nfc_hardware_password_pack_verification_failed);
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
            if (type == 0x00) {
                pos++;
                continue;
            }
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
            if (pos + 1 >= end) throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_tlv_incomplete);
            int length = memory[pos + 1] & 0xFF;
            int header = 2;
            if (length == 0xFF) {
                if (pos + 3 >= end) throw new LocalizedErrors.IOException(R.string.nfc_hardware_extended_ndef_tlv_incomplete);
                length = ((memory[pos + 2] & 0xFF) << 8) | (memory[pos + 3] & 0xFF);
                header = 4;
            }
            if (length == 0) return new byte[0];
            if (length < 0 || pos + header + length > end) throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_tlv_exceeds_memory);
            return Arrays.copyOfRange(memory, pos + header, pos + header + length);
        }
        throw new LocalizedErrors.IOException(R.string.nfc_hardware_readable_ndef_tlv_missing);
    }

    private static int findNdefTlvStart(byte[] memory, int start, int end) throws IOException {
        int pos = start;
        while (pos < end) {
            int type = memory[pos] & 0xFF;
            if (type == 0x00) {
                pos++;
                continue;
            }
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
        if (ndef.length > 0xFFFF) throw new LocalizedErrors.IOException(R.string.nfc_hardware_ndef_message_too_large);
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

    private static byte[] parseHex(String value, int byteCount, int labelResource) throws IOException {
        String hex = value == null ? "" : value.trim().replace(" ", "").replace(":", "").replace("-", "");
        if (hex.length() != byteCount * 2) {
            throw new LocalizedErrors.IOException(
                    R.string.nfc_hardware_hex_chars_length,
                    LocalizedErrors.resource(labelResource),
                    byteCount * 2);
        }
        try {
            return Hex.decodeStrict(hex);
        } catch (DecoderException error) {
            throw new LocalizedErrors.IOException(
                    R.string.nfc_hardware_hex_chars_invalid,
                    LocalizedErrors.resource(labelResource));
        }
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
