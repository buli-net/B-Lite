package wallet.backup;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import wallet.network.NetworkConfig;

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
    private static final int LEGACY_FORMAT_VERSION = 1;
    private static final int FORMAT_VERSION = 2;
    private static final long MAX_WALLET_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_PACKAGE_UNCOMPRESSED_BYTES = 96L * 1024L * 1024L;
    private static final int MAX_APP_DATA_JSON_BYTES = 24 * 1024 * 1024;
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

    public static void createArchive(Context context, File walletFile, File archiveFile, Options options)
            throws IOException, JSONException {
        if (context == null || walletFile == null || !walletFile.isFile() || walletFile.length() <= 0L) {
            throw new IOException("wallet backup source is missing");
        }
        if (options == null) options = new Options();
        File parent = archiveFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("unable to create backup directory");
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
                    throw new IOException("application backup data is too large");
                }
                zip.putNextEntry(new ZipEntry(APP_DATA_ENTRY));
                zip.write(bytes);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException error) {
            if (archiveFile.exists()) archiveFile.delete();
            throw error;
        }
    }

    /**
     * Extracts wallet.wallet and returns optional app data. Legacy raw .wallet files
     * and old ZIPs containing forwarding.json remain supported.
     */
    public static JSONObject extractWalletFile(File inputFile, File walletOutput) throws IOException {
        if (inputFile == null || !inputFile.isFile() || inputFile.length() <= 0L) {
            throw new IOException("wallet backup is empty");
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
            return null;
        }

        boolean walletFound = false;
        JSONObject appData = null;
        JSONObject legacyForwarding = null;
        long totalBytes = 0L;
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
                    if (walletFound) throw new IOException("duplicate wallet entry in backup");
                    walletFound = true;
                    try (OutputStream output = new FileOutputStream(walletOutput)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            totalBytes += count;
                            if (totalBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                                throw new IOException("wallet backup expands beyond the size limit");
                            }
                            output.write(buffer, 0, count);
                        }
                    }
                } else if (APP_DATA_ENTRY.equals(name) || LEGACY_FORWARDING_ENTRY.equals(name)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        totalBytes += count;
                        if (bytes.size() + count > MAX_APP_DATA_JSON_BYTES
                                || totalBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                            throw new IOException("application backup data is too large");
                        }
                        bytes.write(buffer, 0, count);
                    }
                    try {
                        JSONObject parsed = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                        if (APP_DATA_ENTRY.equals(name)) {
                            if (appData != null) throw new IOException("duplicate app data entry in backup");
                            validateAppDataSnapshot(parsed);
                            appData = parsed;
                        } else {
                            if (legacyForwarding != null) throw new IOException("duplicate forwarding entry in backup");
                            validateLegacySnapshot(parsed);
                            legacyForwarding = parsed;
                        }
                    } catch (JSONException invalidSnapshot) {
                        throw new IOException("application backup data is invalid", invalidSnapshot);
                    }
                } else {
                    // Never extract arbitrary ZIP paths. Unknown entries are safely skipped.
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        totalBytes += count;
                        if (totalBytes > MAX_PACKAGE_UNCOMPRESSED_BYTES) {
                            throw new IOException("wallet backup expands beyond the size limit");
                        }
                    }
                }
                zip.closeEntry();
            }
        } catch (IOException error) {
            if (walletOutput.exists()) walletOutput.delete();
            throw error;
        }
        if (!walletFound || !walletOutput.isFile() || walletOutput.length() <= 0L) {
            if (walletOutput.exists()) walletOutput.delete();
            throw new IOException("backup package is missing wallet data");
        }
        // Prefer v2 if present; otherwise migrate the legacy single-category snapshot in memory.
        if (appData != null) return appData;
        if (legacyForwarding != null) return legacyForwarding;
        return null;
    }

    /** Restores the optional data categories included in a current or legacy backup. */
    public static void restoreAppDataSnapshot(Context context, JSONObject snapshot) throws IOException {
        if (context == null || snapshot == null) return;
        try {
            if (snapshot.optInt("formatVersion", -1) == LEGACY_FORMAT_VERSION) {
                restoreLegacyForwardingSnapshot(context, snapshot);
                return;
            }
            validateAppDataSnapshot(snapshot);
            String network = NetworkConfig.storageName(NetworkConfig.get(context));
            if (!network.equals(snapshot.getString("network"))) {
                throw new IOException("application backup belongs to a different network");
            }
            JSONObject categories = snapshot.getJSONObject("categories");
            if (categories.has("forwarding")) {
                restoreWholePreferences(context, "forwarding_service_" + network,
                        categories.getJSONObject("forwarding").getJSONObject("values"));
            }
            if (categories.has("contacts")) {
                restoreWholePreferences(context, networkPrefsName("address_book", network),
                        categories.getJSONObject("contacts").getJSONObject("values"));
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
                            throw new IOException("invalid saved synchronization setting");
                        }
                        if (!prefs.edit().putString(syncKey, (String) value).commit()) {
                            throw new IOException("unable to restore synchronization setting");
                        }
                    }
                }
            }
        } catch (JSONException | NumberFormatException error) {
            throw new IOException("application backup data is invalid", error);
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
            categories.put("contacts", createPreferencesSnapshot(context,
                    networkPrefsName("address_book", network), key -> "entries".equals(key)));
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
                if (!(member instanceof String)) throw new IOException("unsupported preference set value");
                array.put(member);
            }
            typed.put("type", "string_set"); typed.put("value", array);
        } else {
            throw new IOException("unsupported preference value");
        }
        return typed;
    }

    private static void restoreLegacyForwardingSnapshot(Context context, JSONObject snapshot)
            throws IOException, JSONException {
        validateLegacySnapshot(snapshot);
        String network = NetworkConfig.storageName(NetworkConfig.get(context));
        if (!network.equals(snapshot.getString("network"))) {
            throw new IOException("forwarding backup belongs to a different network");
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
        if (!editor.commit()) throw new IOException("unable to restore preferences");
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
        if (!editor.commit()) throw new IOException("unable to restore selected preferences");
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
            } else throw new IOException("unsupported saved preference value");
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
            default: throw new IOException("unsupported saved preference value type");
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
            throw new IOException("unsupported application backup format");
        }
        JSONObject categories = snapshot.getJSONObject("categories");
        java.util.Iterator<String> keys = categories.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"forwarding".equals(key) && !"contacts".equals(key)
                    && !"importedWalletMetadata".equals(key) && !"requestMetadata".equals(key)
                    && !"appSettings".equals(key)) {
                throw new IOException("unknown backup category");
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
            }
        }
    }

    private static void validateValues(JSONObject values, KeyFilter filter)
            throws IOException, JSONException {
        java.util.Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key == null || !filter.accept(key)) {
                throw new IOException("backup contains an unexpected preference key");
            }
            decodeValue(values.getJSONObject(key));
        }
    }

    private static void validateLegacySnapshot(JSONObject snapshot) throws IOException, JSONException {
        if (snapshot.optInt("formatVersion", -1) != LEGACY_FORMAT_VERSION
                || snapshot.optString("network", "").isEmpty()
                || !(snapshot.opt("values") instanceof JSONObject)) {
            throw new IOException("unsupported forwarding backup format");
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
                if (total > limit) throw new IOException("wallet backup is too large");
                output.write(buffer, 0, count);
            }
        }
    }

    private static void copy(File inputFile, File outputFile, long limit) throws IOException {
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("unable to create restore directory");
        }
        try (OutputStream output = new FileOutputStream(outputFile)) {
            copy(inputFile, output, limit);
        } catch (IOException error) {
            if (outputFile.exists()) outputFile.delete();
            throw error;
        }
    }
}
