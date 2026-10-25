package wallet.contacts;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.bitcoinj.base.BitcoinNetwork;

import wallet.network.NetworkConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local contact records. Only public contact details and Bitcoin addresses are stored. */
public final class AddressBookStore {
    private static final String PREFS = "address_book";
    private static final String KEY_ENTRIES = "entries";
    private static final String SEP = "\t";

    private AddressBookStore() {}

    private static SharedPreferences prefs(Context context) {
        BitcoinNetwork network = NetworkConfig.get(context);
        String name = network == BitcoinNetwork.MAINNET
                ? PREFS
                : PREFS + "_" + NetworkConfig.storageName(network);
        return context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public static List<Entry> load(Context context) {
        Set<String> raw = prefs(context).getStringSet(KEY_ENTRIES, Collections.<String>emptySet());
        List<Entry> result = new ArrayList<>();
        for (String item : raw) {
            Entry entry = decode(item);
            if (entry != null) result.add(entry);
        }
        Collections.sort(result, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    public static Entry find(Context context, String address) {
        if (address == null) return null;
        for (Entry entry : load(context)) {
            if (entry.address.equals(address)) return entry;
        }
        return null;
    }

    public static boolean add(Context context, String name, String address) {
        return add(context, new Entry(name, address));
    }

    public static boolean add(Context context, Entry entry) {
        entry = normalized(entry);
        if (entry.name.isEmpty() || entry.address.isEmpty()) return false;
        Set<String> entries = new HashSet<>(prefs(context)
                .getStringSet(KEY_ENTRIES, Collections.<String>emptySet()));
        for (String raw : entries) {
            Entry saved = decode(raw);
            if (saved != null && saved.address.equals(entry.address)) return false;
        }
        entries.add(encode(entry));
        return save(context, entries);
    }

    /** Updates one contact, preserving uniqueness by Bitcoin address. */
    public static boolean update(Context context, String oldAddress, Entry replacement) {
        replacement = normalized(replacement);
        if (replacement.name.isEmpty() || replacement.address.isEmpty()
                || oldAddress == null || oldAddress.isEmpty()) return false;
        SharedPreferences preferences = prefs(context);
        Set<String> entries = new HashSet<>(preferences
                .getStringSet(KEY_ENTRIES, Collections.<String>emptySet()));
        boolean found = false;
        for (String raw : new HashSet<>(entries)) {
            Entry saved = decode(raw);
            if (saved == null) continue;
            if (saved.address.equals(replacement.address) && !saved.address.equals(oldAddress)) {
                return false;
            }
            if (saved.address.equals(oldAddress)) {
                entries.remove(raw);
                found = true;
            }
        }
        if (!found) return false;
        entries.add(encode(replacement));
        return save(context, entries);
    }

    public static boolean remove(Context context, String address) {
        SharedPreferences preferences = prefs(context);
        Set<String> entries = new HashSet<>(preferences.getStringSet(KEY_ENTRIES, Collections.<String>emptySet()));
        boolean changed = false;
        for (String raw : new HashSet<>(entries)) {
            Entry entry = decode(raw);
            if (entry != null && entry.address.equals(address)) {
                entries.remove(raw);
                changed = true;
            }
        }
        return !changed || save(context, entries);
    }

    private static Entry normalized(Entry entry) {
        return new Entry(clean(entry.name), clean(entry.address), clean(entry.organization),
                clean(entry.phone), clean(entry.email), clean(entry.website), clean(entry.note));
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }

    private static boolean save(Context context, Set<String> entries) {
        return prefs(context).edit().putStringSet(KEY_ENTRIES, entries).commit();
    }

    private static String encode(Entry entry) {
        return b64(entry.name) + SEP + b64(entry.address) + SEP + b64(entry.organization)
                + SEP + b64(entry.phone) + SEP + b64(entry.email) + SEP + b64(entry.website)
                + SEP + b64(entry.note);
    }

    private static Entry decode(String raw) {
        if (raw == null) return null;
        String[] fields = raw.split(SEP, -1);
        if (fields.length < 2) return null;
        try {
            String name = fromB64(fields[0]);
            String address = fromB64(fields[1]);
            if (name.isEmpty() || address.isEmpty()) return null;
            return new Entry(name, address, field(fields, 2), field(fields, 3), field(fields, 4),
                    field(fields, 5), field(fields, 6));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String field(String[] fields, int index) {
        return index < fields.length && !fields[index].isEmpty() ? fromB64(fields[index]) : "";
    }

    private static String b64(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String fromB64(String value) {
        return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    public static final class Entry {
        public final String name;
        public final String address;
        public final String organization;
        public final String phone;
        public final String email;
        public final String website;
        public final String note;

        public Entry(String name, String address) {
            this(name, address, "", "", "", "", "");
        }

        public Entry(String name, String address, String organization, String phone,
                     String email, String website, String note) {
            this.name = name == null ? "" : name;
            this.address = address == null ? "" : address;
            this.organization = organization == null ? "" : organization;
            this.phone = phone == null ? "" : phone;
            this.email = email == null ? "" : email;
            this.website = website == null ? "" : website;
            this.note = note == null ? "" : note;
        }
    }
}
