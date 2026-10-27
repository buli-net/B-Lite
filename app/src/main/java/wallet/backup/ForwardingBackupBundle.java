package wallet.backup;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import wallet.network.NetworkConfig;
import wallet.main.R;
import wallet.ui.LocalizedErrors;
import wallet.contacts.AddressBookStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Packages the wallet and user-selectable, restorable application data. */
public final class ForwardingBackupBundle {
    private static final String WALLET_ENTRY = "wallet.wallet";
    private static final String LEGACY_FORWARDING_ENTRY = "forwarding.json";
    private static final String APP_DATA_ENTRY = "appdata.json";
    private static final String CONTACT_AVATAR_ENTRY_PREFIX = "contact-avatars/";
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int FORMAT_VERSION = 2;
    private static final long MAX_WALLET_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_PACKAGE_UNCOMPRESSED_BYTES = 96L * 1024L * 1024L;
    private static final int MAX_APP_DATA_JSON_BYTES = 24 * 1024 * 1024;
    private static final int MAX_SINGLE_AVATAR_BYTES = 2 * 1024 * 1024;
    private static final long MAX_TOTAL_AVATAR_BYTES = 24L * 1024L * 1024L;
    private static final String[] IMPORTED_PREFIXES = {"name.", "type.", "bip38."};
    private static final String[] REQUEST_PREFIXES = {"request_nested_pubkey_"};

    private ForwardingBackupBundle() { }

    /** Selection for the optional preference-backed data categories. */
    public static final class Options {
        public boolean forwarding = true;
        public boolean contacts = true;
        public boolean importedWalletMetadata = true;
        public boolean requestMetadata = true;
        public boolean appSettings = true;

        public boolean hasOptionalData() {
            return forwarding || contacts || importedWalletMetadata || requestMetadata || appSettings;
        }
    }

    /** Result of reading a backup, including any staged contact-photo files. */
    public static final class RestorePayload {
        public final JSONObject appDataSnapshot;
        private final File extractedAvatarDirectory;

        private RestorePayload(JSONObject appDataSnapshot, File extractedAvatarDirectory) {
            this.appDataSnapshot = appDataSnapshot;
            this.extractedAvatarDirectory = extractedAvatarDirectory;
        }

        /** Removes staged avatar files after restore completes or fails. */
        public void cleanup() {
            deleteRecursively(extractedAvatarDirectory);
        }
    }

    public static void createArchive(Context context, File walletFile, File archiveFile, Options options)
            throws IOException, JSONException {
        if (context == null || walletFile == null || !walletFile.isFile() || walletFile.length() <= 0L) {
            throw new LocalizedErrors.IOException(R.string.wallet_file_missing);
        }
        if (options == null) options = new Options();
        File parent = archiveFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new LocalizedErrors.IOException(R.string.backup_storage_directory_failed);
        }

        String network = NetworkConfig.storageName(NetworkConfig.get(context));
        JSONObject snapshot;
        try {
            snapshot = options.hasOptionalData() ? createAppDataSnapshot(context, network, options) : null;
        } catch (IOException | JSONException error) {
            if (archiveFile.exists()) archiveFile.delete();
            throw error;
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(archiveFile))) {
            zip.putNextEntry(new ZipEntry(WALLET_ENTRY));
            copy(walletFile, zip, MAX_WALLET_BYTES);
            zip.closeEntry();

            if (snapshot != null) {
                byte[] bytes = snapshot.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_APP_DATA_JSON_BYTES) {
                    throw new LocalizedErrors.IOException(R.string.backup_archive_too_large);
                }
                zip.putNextEntry(new ZipEntry(APP_DATA_ENTRY));
                zip.write(bytes);
                zip.closeEntry();
                writeContactAvatarEntries(context, snapshot, zip, walletFile.length() + bytes.length);
            }
            zip.finish();
        } catch (IOException error) {
            if (archiveFile.exists()) archiveFile.delete();
            throw error;
        }
    }

    /**
     * Extracts wallet.wallet, optional app data, and staged contact avatars. Legacy raw .wallet files
     * and old ZIPs containing forwarding.json remain supported.
     */
    public static RestorePayload extractWalletFile(File inputFile, File walletOutput) throws IOException {
        if (inputFile == null || !inputFile.isFile() || inputFile.length() <= 0L) {
            throw new LocalizedErrors.IOException(R.string.restore_empty);
        }
        boolean zipFile;
        try (InputStream input = new FileInputStream(inputFile)) {
            int first = input.read(), second = input.read(), third = input.read(), fourth = input.read();
            zipFile = first == 'P' && second == 'K'
                    && ((third == 3 && fourth == 4) || (third == 5 && fourth == 6)
                    || (third == 7 && fourth == 8));
        }
        if (!zipFile) {
            copy(inputFile, walletOutput, MAX_WALLET_BYTES);
            return new RestorePayload(null, null);
        }

        boolean walletFound = false;
        JSONObject appData = null;
        JSONObject legacyForwarding = null;
        File avatarDirectory = new File(walletOutput.getAbsoluteFile().getParentFile(), walletOutput.getName() + ".avatars");
        Set<String> extractedAvatarNames = new HashSet<>();
        long totalBytes = 0L;
        long totalAvatarBytes = 0L;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(inputFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                if (WALLET_ENTRY.equals(name)) {
                    if (walletFound) throw new LocalizedErrors.IOException(R.string.backup_archive_invalid);
                    walletFound = true;
                    try (OutputStream output = new FileOutputStream(walletOutput)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            totalBytes += count;
                            if (totalBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                                throw new LocalizedErrors.IOException(R.string.restore_too_large);
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                } else if (name.startsWith(CONTACT_AVATAR_ENTRY_PREFIX)) {
                    String avatarName = name.substring(CONTACT_AVATAR_ENTRY_PREFIX.length());
                    if (!isSafeAvatarFileName(avatarName) || !extractedAvatarNames.add(avatarName)) {
                        throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_invalid_or_duplicate);
                    }
                    if (!avatarDirectory.isDirectory() && !avatarDirectory.mkdirs()) {
                        throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_restore_prepare_failed);
                    }
                    File avatarFile = new File(avatarDirectory, avatarName);
                    long thisAvatarBytes = 0L;
                    try (OutputStream output = new FileOutputStream(avatarFile)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            thisAvatarBytes += count;
                            totalAvatarBytes += count;
                            totalBytes += count;
                            if (thisAvatarBytes > MAX_SINGLE_AVATAR_BYTES
                                    || totalAvatarBytes > MAX_TOTAL_AVATAR_BYTES
                                    || totalBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_archive_size_limit);
                            }
                            output.write(buffer, 0, count);
                        }
                    } catch (IOException error) {
                        if (avatarFile.exists()) avatarFile.delete();
                        throw error;
                    }
                    if (thisAvatarBytes == 0L || !isJpegFile(avatarFile)) {
                        throw new LocalizedErrors.IOException(R.string.backup_contact_jpeg_invalid);
                    }
                } else if (APP_DATA_ENTRY.equals(name) || LEGACY_FORWARDING_ENTRY.equals(name)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        totalBytes += count;
                        if (bytes.size() + count > MAX_APP_DATA_JSON_BYTES
                                || totalBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                            throw new LocalizedErrors.IOException(R.string.backup_archive_too_large);
                        }
                        bytes.write(buffer, 0, count);
                    }
                    try {
                        JSONObject parsed = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                        if (APP_DATA_ENTRY.equals(name)) {
                            if (appData != null) throw new LocalizedErrors.IOException(R.string.backup_archive_invalid);
                            validateAppDataSnapshot(parsed);
                            appData = parsed;
                        } else {
                            if (legacyForwarding != null) throw new LocalizedErrors.IOException(R.string.backup_archive_invalid);
                            validateLegacySnapshot(parsed);
                            legacyForwarding = parsed;
                        }
                    } catch (JSONException invalidSnapshot) {
                        throw new LocalizedErrors.IOException(R.string.backup_archive_invalid, invalidSnapshot);
                    }
                } else {
                    // Never extract arbitrary ZIP paths. Unknown entries are safely skipped.
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        totalBytes += count;
                        if (totalBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                            throw new LocalizedErrors.IOException(R.string.restore_too_large);
                        }
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException error) {
            if (walletOutput.exists()) walletOutput.delete();
            deleteRecursively(avatarDirectory);
            throw error;
        }
        if (!walletFound || !walletOutput.isFile() || walletOutput.length() <= 0L) {
            if (walletOutput.exists()) walletOutput.delete();
            deleteRecursively(avatarDirectory);
            throw new LocalizedErrors.IOException(R.string.backup_archive_invalid);
        }
        // Prefer v2 if present; otherwise migrate the legacy single-category snapshot in memory.
        JSONObject snapshot = appData != null ? appData : legacyForwarding;
        try {
            if (appData != null) validateExtractedAvatarSet(appData, extractedAvatarNames);
            else if (!extractedAvatarNames.isEmpty()) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_metadata_missing);
            }
        } catch (IOException | JSONException error) {
            deleteRecursively(avatarDirectory);
            if (walletOutput.exists()) walletOutput.delete();
            throw error instanceof IOException ? (IOException) error
                    : new LocalizedErrors.IOException(R.string.backup_archive_invalid, error);
        }
        return new RestorePayload(snapshot, extractedAvatarNames.isEmpty() ? null : avatarDirectory);
    }

    /** Restores the optional data categories included in a current or legacy backup. */
    public static void restoreAppDataSnapshot(Context context, RestorePayload payload) throws IOException {
        if (context == null || payload == null || payload.appDataSnapshot == null) return;
        JSONObject snapshot = payload.appDataSnapshot;
        try {
            if (snapshot.optInt("formatVersion", -1) == LEGACY_FORMAT_VERSION) {
                restoreLegacyForwardingSnapshot(context, snapshot);
                return;
            }
            validateAppDataSnapshot(snapshot);
            String network = NetworkConfig.storageName(NetworkConfig.get(context));
            if (!network.equals(snapshot.getString("network"))) {
                throw new LocalizedErrors.IOException(R.string.restore_network_mismatch);
            }
            JSONObject categories = snapshot.getJSONObject("categories");
            if (categories.has("forwarding")) {
                restoreWholePreferences(context, "forwarding_service_" + network,
                        categories.getJSONObject("forwarding").getJSONObject("values"));
            }
            if (categories.has("contacts")) {
                JSONObject contacts = categories.getJSONObject("contacts");
                // Restore image files before restoring contact records that reference them.
                // Older backup files do not contain the optional "avatars" object.
                if (contacts.has("avatars")) {
                    restoreContactAvatars(context, contacts.getJSONObject("avatars"),
                            payload.extractedAvatarDirectory);
                }
                restoreWholePreferences(context, networkPrefsName("address_book", network),
                        contacts.getJSONObject("values"));
            }
            if (categories.has("importedWalletMetadata")) {
                restoreFilteredPreferences(context, networkPrefsName("imported_wif_wallets", network),
                        categories.getJSONObject("importedWalletMetadata").getJSONObject("values"),
                        ForwardingBackupBundle::isImportedMetadataKey, true);
            }
            if (categories.has("requestMetadata")) {
                restoreFilteredPreferences(context, networkPrefsName("wallet_selection", network),
                        categories.getJSONObject("requestMetadata").getJSONObject("values"),
                        ForwardingBackupBundle::isRequestMetadataKey, true);
            }
            if (categories.has("appSettings")) {
                JSONObject settings = categories.getJSONObject("appSettings");
                if (settings.has("moneyDisplay")) {
                    restoreWholePreferences(context, "money_display_preferences",
                            settings.getJSONObject("moneyDisplay").getJSONObject("values"));
                }
                if (settings.has("syncMode")) {
                    String syncKey = network.equals("signet") ? "sync_mode_signet" : "sync_mode_mainnet";
                    JSONObject values = settings.getJSONObject("syncMode").getJSONObject("values");
                    if (values.has(syncKey)) {
                        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(
                                "bitcoin_network", Context.MODE_PRIVATE);
                        Object value = decodeValue(values.getJSONObject(syncKey));
                        if (!(value instanceof String)
                                || !("LITE".equals(value) || "FULL".equals(value))) {
                            throw new LocalizedErrors.IOException(R.string.backup_app_data_invalid);
                        }
                        if (!prefs.edit().putString(syncKey, (String) value).commit()) {
                            throw new LocalizedErrors.IOException(R.string.backup_app_data_restore_failed);
                        }
                    }
                }
            }
        } catch (JSONException | NumberFormatException error) {
            throw new LocalizedErrors.IOException(R.string.backup_archive_invalid, error);
        }
    }

    private static JSONObject createAppDataSnapshot(Context context, String network, Options options)
            throws JSONException, IOException {
        JSONObject root = new JSONObject();
        root.put("formatVersion", FORMAT_VERSION);
        root.put("network", network);
        JSONObject categories = new JSONObject();
        if (options.forwarding) {
            categories.put("forwarding", createPreferencesSnapshot(context,
                    "forwarding_service_" + network, key -> true));
        }
        if (options.contacts) {
            JSONObject contacts = createPreferencesSnapshot(context,
                    networkPrefsName("address_book", network), key -> "entries".equals(key));
            contacts.put("avatars", createContactAvatarsSnapshot(context));
            categories.put("contacts", contacts);
        }
        if (options.importedWalletMetadata) {
            categories.put("importedWalletMetadata", createPreferencesSnapshot(context,
                    networkPrefsName("imported_wif_wallets", network),
                    ForwardingBackupBundle::isImportedMetadataKey));
        }
        if (options.requestMetadata) {
            categories.put("requestMetadata", createPreferencesSnapshot(context,
                    networkPrefsName("wallet_selection", network),
                    ForwardingBackupBundle::isRequestMetadataKey));
        }
        if (options.appSettings) {
            JSONObject settings = new JSONObject();
            settings.put("moneyDisplay", createPreferencesSnapshot(context,
                    "money_display_preferences", key -> true));
            String syncKey = network.equals("signet") ? "sync_mode_signet" : "sync_mode_mainnet";
            settings.put("syncMode", createPreferencesSnapshot(context, "bitcoin_network",
                    key -> syncKey.equals(key)));
            categories.put("appSettings", settings);
        }
        root.put("categories", categories);
        return root;
    }

    private static JSONObject createPreferencesSnapshot(Context context, String preferencesName,
                                                         KeyFilter filter)
            throws JSONException, IOException {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        JSONObject root = new JSONObject();
        JSONObject values = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (key == null || !filter.accept(key)) continue;
            values.put(key, encodeValue(entry.getValue()));
        }
        root.put("values", values);
        return root;
    }

    private static JSONObject encodeValue(Object value) throws JSONException, IOException {
        JSONObject typed = new JSONObject();
        if (value instanceof String) {
            typed.put("type", "string"); typed.put("value", value);
        } else if (value instanceof Boolean) {
            typed.put("type", "boolean"); typed.put("value", value.toString());
        } else if (value instanceof Integer) {
            typed.put("type", "int"); typed.put("value", value.toString());
        } else if (value instanceof Long) {
            typed.put("type", "long"); typed.put("value", value.toString());
        } else if (value instanceof Float) {
            typed.put("type", "float"); typed.put("value", value.toString());
        } else if (value instanceof Set<?>) {
            JSONArray array = new JSONArray();
            for (Object member : (Set<?>) value) {
                if (!(member instanceof String)) throw new LocalizedErrors.IOException(R.string.backup_app_data_restore_failed);
                array.put(member);
            }
            typed.put("type", "string_set"); typed.put("value", array);
        } else {
            throw new LocalizedErrors.IOException(R.string.backup_app_data_restore_failed);
        }
        return typed;
    }

    /** Records only safe avatar filenames in JSON; image bytes are separate ZIP entries. */
    private static JSONObject createContactAvatarsSnapshot(Context context)
            throws IOException, JSONException {
        JSONObject avatars = new JSONObject();
        Set<String> included = new HashSet<>();
        long totalBytes = 0L;
        for (AddressBookStore.Entry contact : AddressBookStore.load(context)) {
            String fileName = contact.avatarFileName;
            if (fileName == null || fileName.isEmpty() || !included.add(fileName)) continue;
            if (!isSafeAvatarFileName(fileName)) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_filename_invalid);
            }
            File avatar = AddressBookStore.getAvatarFile(context, fileName);
            if (avatar == null || !avatar.isFile() || avatar.length() <= 0L) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_missing_create_failed);
            }
            if (avatar.length() > MAX_SINGLE_AVATAR_BYTES) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_size_limit);
            }
            totalBytes += avatar.length();
            if (totalBytes > MAX_TOTAL_AVATAR_BYTES) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatars_total_size_limit);
            }
            if (!isJpegFile(avatar)) throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_jpeg_invalid);
            avatars.put(fileName, true);
        }
        return avatars;
    }

    private static void writeContactAvatarEntries(Context context, JSONObject snapshot, ZipOutputStream zip,
                                                  long packageBytesAlreadyWritten) throws IOException {
        JSONObject categories = snapshot.optJSONObject("categories");
        JSONObject contacts = categories == null ? null : categories.optJSONObject("contacts");
        JSONObject avatars = contacts == null ? null : contacts.optJSONObject("avatars");
        if (avatars == null) return;
        long totalAvatarBytes = 0L;
        long packageBytes = packageBytesAlreadyWritten;
        java.util.Iterator<String> names = avatars.keys();
        while (names.hasNext()) {
            String fileName = names.next();
            if (!isSafeAvatarFileName(fileName)) throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_filename_invalid);
            File avatar = AddressBookStore.getAvatarFile(context, fileName);
            if (avatar == null || !avatar.isFile() || avatar.length() <= 0L
                    || avatar.length() > MAX_SINGLE_AVATAR_BYTES || !isJpegFile(avatar)) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_missing_or_invalid);
            }
            totalAvatarBytes += avatar.length();
            packageBytes += avatar.length();
            if (totalAvatarBytes > MAX_TOTAL_AVATAR_BYTES) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatars_total_size_limit);
            }
            if (packageBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                throw new LocalizedErrors.IOException(R.string.backup_archive_package_size_limit);
            }
            zip.putNextEntry(new ZipEntry(CONTACT_AVATAR_ENTRY_PREFIX + fileName));
            copy(avatar, zip, MAX_SINGLE_AVATAR_BYTES);
            zip.closeEntry();
        }
    }

    private static void validateContactAvatars(JSONObject avatars) throws IOException, JSONException {
        java.util.Iterator<String> names = avatars.keys();
        while (names.hasNext()) {
            String fileName = names.next();
            if (!isSafeAvatarFileName(fileName) || !avatars.optBoolean(fileName, false)) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_metadata_invalid);
            }
        }
    }

    private static void validateExtractedAvatarSet(JSONObject snapshot, Set<String> extractedNames)
            throws IOException, JSONException {
        JSONObject categories = snapshot.optJSONObject("categories");
        JSONObject contacts = categories == null ? null : categories.optJSONObject("contacts");
        JSONObject avatars = contacts == null ? null : contacts.optJSONObject("avatars");
        Set<String> expected = new HashSet<>();
        if (avatars != null) {
            validateContactAvatars(avatars);
            java.util.Iterator<String> keys = avatars.keys();
            while (keys.hasNext()) expected.add(keys.next());
        }
        if (!expected.equals(extractedNames)) {
            throw new LocalizedErrors.IOException(R.string.backup_contact_metadata_missing);
        }
    }

    private static void restoreContactAvatars(Context context, JSONObject avatars, File extractedDirectory)
            throws IOException, JSONException {
        validateContactAvatars(avatars);
        if (avatars.length() == 0) return;
        if (extractedDirectory == null || !extractedDirectory.isDirectory()) {
            throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_files_missing);
        }
        File directory = AddressBookStore.getAvatarDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_directory_failed);
        }
        java.util.Iterator<String> names = avatars.keys();
        while (names.hasNext()) {
            String fileName = names.next();
            File source = new File(extractedDirectory, fileName);
            File target = AddressBookStore.getAvatarFile(context, fileName);
            if (target == null || !source.isFile() || source.length() <= 0L
                    || source.length() > MAX_SINGLE_AVATAR_BYTES || !isJpegFile(source)) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_restore_file_invalid);
            }
            File temporary = new File(directory, fileName + ".restore.tmp");
            File previous = new File(directory, fileName + ".restore.bak");
            if (temporary.exists() && !temporary.delete()) {
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_restore_prepare_failed);
            }
            copy(source, temporary, MAX_SINGLE_AVATAR_BYTES);
            if (previous.exists() && !previous.delete()) {
                temporary.delete();
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_previous_replace_failed);
            }
            boolean hadTarget = target.exists();
            if (hadTarget && !target.renameTo(previous)) {
                temporary.delete();
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_preserve_failed);
            }
            if (!temporary.renameTo(target)) {
                if (hadTarget && previous.exists()) previous.renameTo(target);
                temporary.delete();
                throw new LocalizedErrors.IOException(R.string.backup_contact_avatar_restore_failed);
            }
            if (previous.exists()) previous.delete();
        }
    }

    private static boolean isSafeAvatarFileName(String fileName) {
        return fileName != null && fileName.matches("avatar_[a-f0-9]{32}\\.jpg");
    }

    private static boolean isJpegFile(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 4L) return false;
        try (InputStream input = new FileInputStream(file)) {
            int first = input.read();
            int second = input.read();
            if (first != 0xff || second != 0xd8) return false;
        }
        try (InputStream input = new FileInputStream(file)) {
            long skip = file.length() - 2L;
            while (skip > 0L) {
                long count = input.skip(skip);
                if (count <= 0L) return false;
                skip -= count;
            }
            return input.read() == 0xff && input.read() == 0xd9;
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void restoreLegacyForwardingSnapshot(Context context, JSONObject snapshot)
            throws IOException, JSONException {
        validateLegacySnapshot(snapshot);
        String network = NetworkConfig.storageName(NetworkConfig.get(context));
        if (!network.equals(snapshot.getString("network"))) {
            throw new LocalizedErrors.IOException(R.string.restore_network_mismatch);
        }
        restoreWholePreferences(context, "forwarding_service_" + network,
                snapshot.getJSONObject("values"));
    }

    private static void restoreWholePreferences(Context context, String name, JSONObject values)
            throws IOException, JSONException {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(name, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit().clear();
        putValues(editor, values, key -> true);
        if (!editor.commit()) throw new LocalizedErrors.IOException(R.string.backup_app_data_restore_failed);
    }

    private static void restoreFilteredPreferences(Context context, String name, JSONObject values,
                                                   KeyFilter filter, boolean replaceMatching)
            throws IOException, JSONException {
        SharedPreferences preferences = context.getApplicationContext()
                .getSharedPreferences(name, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        if (replaceMatching) {
            for (String key : preferences.getAll().keySet()) {
                if (filter.accept(key)) editor.remove(key);
            }
        }
        putValues(editor, values, filter);
        if (!editor.commit()) throw new LocalizedErrors.IOException(R.string.backup_app_data_restore_failed);
    }

    private static void putValues(SharedPreferences.Editor editor, JSONObject values, KeyFilter filter)
            throws IOException, JSONException {
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null || key.isEmpty() || !filter.accept(key)) continue;
            Object value = decodeValue(values.getJSONObject(key));
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set<?>) {
                @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                editor.putStringSet(key, new HashSet<>(strings));
            } else throw new LocalizedErrors.IOException(R.string.backup_app_data_restore_failed);
        }
    }

    private static Object decodeValue(JSONObject typed) throws IOException, JSONException {
        String type = typed.getString("type");
        String value = typed.optString("value", "");
        switch (type) {
            case "string": return value;
            case "boolean": return Boolean.parseBoolean(value);
            case "int": return Integer.parseInt(value);
            case "long": return Long.parseLong(value);
            case "float": return Float.parseFloat(value);
            case "string_set":
                JSONArray array = typed.getJSONArray("value");
                Set<String> strings = new HashSet<>();
                for (int i = 0; i < array.length(); i++) strings.add(array.getString(i));
                return strings;
            default: throw new LocalizedErrors.IOException(R.string.backup_app_data_restore_failed);
        }
    }

    private static String networkPrefsName(String base, String network) {
        return "mainnet".equals(network) ? base : base + "_" + network;
    }

    private static boolean isImportedMetadataKey(String key) {
        if ("addresses".equals(key) || "address_list_v2".equals(key)) return true;
        for (String prefix : IMPORTED_PREFIXES) if (key.startsWith(prefix)) return true;
        return false;
    }

    private static boolean isRequestMetadataKey(String key) {
        if ("selected_watch_address".equals(key) || "selected_imported_address".equals(key)) return true;
        for (String prefix : REQUEST_PREFIXES) if (key.startsWith(prefix)) return true;
        return false;
    }

    private static void validateAppDataSnapshot(JSONObject snapshot) throws IOException, JSONException {
        String network = snapshot.optString("network", "");
        if (snapshot.optInt("formatVersion", -1) != FORMAT_VERSION
                || !("mainnet".equals(network) || "signet".equals(network))
                || !(snapshot.opt("categories") instanceof JSONObject)) {
            throw new LocalizedErrors.IOException(R.string.backup_archive_invalid);
        }
        JSONObject categories = snapshot.getJSONObject("categories");
        java.util.Iterator<String> keys = categories.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"forwarding".equals(key) && !"contacts".equals(key)
                    && !"importedWalletMetadata".equals(key) && !"requestMetadata".equals(key)
                    && !"appSettings".equals(key)) {
                throw new LocalizedErrors.IOException(R.string.backup_archive_invalid);
            }
            JSONObject category = categories.getJSONObject(key);
            if ("appSettings".equals(key)) {
                if (category.has("moneyDisplay")) {
                    validateValues(category.getJSONObject("moneyDisplay").getJSONObject("values"), name -> true);
                }
                if (category.has("syncMode")) {
                    String syncKey = "signet".equals(network) ? "sync_mode_signet" : "sync_mode_mainnet";
                    validateValues(category.getJSONObject("syncMode").getJSONObject("values"),
                            name -> syncKey.equals(name));
                }
            } else {
                JSONObject values = category.getJSONObject("values");
                KeyFilter filter;
                switch (key) {
                    case "contacts": filter = name -> "entries".equals(name); break;
                    case "importedWalletMetadata": filter = ForwardingBackupBundle::isImportedMetadataKey; break;
                    case "requestMetadata": filter = ForwardingBackupBundle::isRequestMetadataKey; break;
                    default: filter = name -> true; break;
                }
                validateValues(values, filter);
                if ("contacts".equals(key) && category.has("avatars")) {
                    validateContactAvatars(category.getJSONObject("avatars"));
                }
            }
        }
    }

    private static void validateValues(JSONObject values, KeyFilter filter)
            throws IOException, JSONException {
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null || !filter.accept(key)) {
                throw new LocalizedErrors.IOException(R.string.backup_archive_invalid);
            }
            decodeValue(values.getJSONObject(key));
        }
    }

    private static void validateLegacySnapshot(JSONObject snapshot) throws IOException, JSONException {
        if (snapshot.optInt("formatVersion", -1) != LEGACY_FORMAT_VERSION
                || snapshot.optString("network", "").isEmpty()
                || !(snapshot.opt("values") instanceof JSONObject)) {
            throw new LocalizedErrors.IOException(R.string.backup_archive_invalid);
        }
    }

    private interface KeyFilter { boolean accept(String key); }

    private static void copy(File inputFile, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0L;
        try (InputStream input = new FileInputStream(inputFile)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > limit) throw new LocalizedErrors.IOException(R.string.restore_too_large);
                output.write(buffer, 0, count);
            }
        }
    }

    private static void copy(File inputFile, File outputFile, long limit) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new LocalizedErrors.IOException(R.string.backup_storage_directory_failed);
        }
        try (OutputStream output = new FileOutputStream(outputFile)) {
            copy(inputFile, output, limit);
        } catch (IOException error) {
            if (outputFile.exists()) outputFile.delete();
            throw error;
        }
    }
}
