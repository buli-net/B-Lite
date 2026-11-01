package wallet.network;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.PeerAddress;
import com.google.common.net.HostAndPort;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.wallet.KeyChainGroupStructure;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.SigNetParams;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import wallet.main.R;

/** Central network selection and per-network wallet storage configuration. */
public final class NetworkConfig {
    private static final String PREFS = "bitcoin_network";
    private static final String KEY_ACTIVE_NETWORK = "active_network";
    private static final String KEY_SYNC_MODE_MAINNET = "sync_mode_mainnet";
    private static final String KEY_SYNC_MODE_SIGNET = "sync_mode_signet";
    private static final String KEY_TRUSTED_PEER_MAINNET = "trusted_peer_mainnet";
    private static final String KEY_TRUSTED_PEER_SIGNET = "trusted_peer_signet";
    private static final String KEY_TRUSTED_PEER_ONLY_MAINNET = "trusted_peer_only_mainnet";
    private static final String KEY_TRUSTED_PEER_ONLY_SIGNET = "trusted_peer_only_signet";
    private static final int MAX_TRUSTED_PEERS = 16;
    private static final String NETWORKS_DIR = "networks";

    private NetworkConfig() {
    }

    public static BitcoinNetwork get(Context context) {
        if (context == null) {
            return BitcoinNetwork.MAINNET;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_ACTIVE_NETWORK, BitcoinNetwork.MAINNET.name());
        try {
            BitcoinNetwork network = BitcoinNetwork.valueOf(value);
            return isSupported(network) ? network : BitcoinNetwork.MAINNET;
        } catch (Exception ignored) {
            return BitcoinNetwork.MAINNET;
        }
    }

    public static void set(Context context, BitcoinNetwork network) {
        if (context == null || !isSupported(network)) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_NETWORK, network.name())
                .commit();
    }

    public enum SyncMode {
        LITE,
        FULL
    }

    /**
     * Returns the saved synchronization mode for the given network. Both
     * Mainnet and Signet use the lighter Lite mode by default; Full Block is
     * available as an explicit per-network choice.
     */
    public static SyncMode getSyncMode(Context context, BitcoinNetwork network) {
        if (context == null || !isSupported(network)) {
            return SyncMode.LITE;
        }

        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = syncModeKey(network);
        SyncMode defaultMode = SyncMode.LITE;
        String value = prefs.getString(key, defaultMode.name());
        try {
            return SyncMode.valueOf(value);
        } catch (Exception ignored) {
            return defaultMode;
        }
    }

    public static void setSyncMode(Context context, BitcoinNetwork network, SyncMode mode) {
        if (context == null || !isSupported(network) || mode == null) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(syncModeKey(network), mode.name())
                .commit();
    }

    private static String syncModeKey(BitcoinNetwork network) {
        return network == BitcoinNetwork.SIGNET
                ? KEY_SYNC_MODE_SIGNET
                : KEY_SYNC_MODE_MAINNET;
    }

    /** Returns the configured trusted peer endpoints for one network. */
    public static String getTrustedPeerInput(Context context, BitcoinNetwork network) {
        if (context == null || !isSupported(network)) {
            return "";
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(trustedPeerKey(network), "");
    }

    /** Returns the saved exclusive-peer choice, even if no endpoint is configured. */
    public static boolean getTrustedPeersOnlySetting(Context context, BitcoinNetwork network) {
        if (context == null || !isSupported(network)) {
            return false;
        }
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(trustedPeerOnlyKey(network), false);
    }

    /** Exclusive mode is effective only when a trusted peer was actually configured. */
    public static boolean isTrustedPeersOnly(Context context, BitcoinNetwork network) {
        return getTrustedPeersOnlySetting(context, network)
                && !getTrustedPeerInput(context, network).trim().isEmpty();
    }

    public static void setTrustedPeerSettings(Context context, BitcoinNetwork network,
                                              String endpoints, boolean trustedOnly) {
        if (context == null || !isSupported(network)) {
            return;
        }
        String safeEndpoints = endpoints == null ? "" : endpoints.trim();
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(trustedPeerKey(network), safeEndpoints)
                .putBoolean(trustedPeerOnlyKey(network), trustedOnly && !safeEndpoints.isEmpty())
                .commit();
    }

    private static String trustedPeerKey(BitcoinNetwork network) {
        return network == BitcoinNetwork.SIGNET
                ? KEY_TRUSTED_PEER_SIGNET
                : KEY_TRUSTED_PEER_MAINNET;
    }

    private static String trustedPeerOnlyKey(BitcoinNetwork network) {
        return network == BitcoinNetwork.SIGNET
                ? KEY_TRUSTED_PEER_ONLY_SIGNET
                : KEY_TRUSTED_PEER_ONLY_MAINNET;
    }

    /**
     * Parses a whitespace-, comma-, or semicolon-separated list of host[:port]
     * entries. Hosts without an explicit port use the selected network's P2P port.
     * Hostnames are intentionally not resolved here so settings can be saved offline.
     */
    public static List<HostAndPort> parseTrustedPeerInput(String input, BitcoinNetwork network) {
        if (!isSupported(network)) {
            throw new IllegalArgumentException();
        }
        List<HostAndPort> result = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        if (input == null || input.trim().isEmpty()) {
            return result;
        }

        int defaultPort = parameters(network).getPort();
        String[] entries = input.trim().split("[\\s,;]+");
        for (String rawEntry : entries) {
            if (rawEntry == null || rawEntry.trim().isEmpty()) {
                continue;
            }
            HostAndPort parsed;
            try {
                parsed = HostAndPort.fromString(rawEntry.trim());
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException();
            }
            String host = parsed.getHost();
            if (host == null || host.trim().isEmpty()
                    || !host.matches("[A-Za-z0-9._:%-]+")) {
                throw new IllegalArgumentException();
            }
            int port = parsed.getPortOrDefault(defaultPort);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }
            String identity = host.toLowerCase(Locale.US) + ":" + port;
            if (unique.add(identity)) {
                if (result.size() >= MAX_TRUSTED_PEERS) {
                    throw new IllegalArgumentException();
                }
                result.add(parsed);
            }
        }
        return result;
    }

    /**
     * Resolves configured hostnames to addresses on the sync startup thread. A
     * failed DNS lookup is skipped. In exclusive trusted-peer mode, the caller
     * still configures an empty peer-node list instead of falling back to DNS
     * peer discovery, so the privacy choice is not silently overridden.
     */
    public static List<PeerAddress> resolveTrustedPeerAddresses(Context context, BitcoinNetwork network) {
        List<PeerAddress> result = new ArrayList<>();
        if (context == null || !isSupported(network)) {
            return result;
        }
        String input = getTrustedPeerInput(context, network);
        if (input.trim().isEmpty()) {
            return result;
        }
        final List<HostAndPort> endpoints;
        try {
            endpoints = parseTrustedPeerInput(input, network);
        } catch (IllegalArgumentException invalidConfiguration) {
            return result;
        }
        Set<String> unique = new HashSet<>();
        for (HostAndPort endpoint : endpoints) {
            try {
                InetAddress address = InetAddress.getByName(endpoint.getHost());
                int port = endpoint.getPortOrDefault(parameters(network).getPort());
                InetSocketAddress socketAddress = new InetSocketAddress(address, port);
                String identity = address.getHostAddress() + ":" + port;
                if (unique.add(identity)) {
                    result.add(PeerAddress.simple(socketAddress));
                }
            } catch (Exception ignored) {
                // Keep other configured trusted peers available when one host fails DNS.
            }
        }
        return result;
    }

    public static NetworkParameters parameters(Context context) {
        return parameters(get(context));
    }

    /**
     * Returns the HD wallet structure for the selected network. bitcoinj 0.17.1
     * predates the Signet mapping in KeyChainGroupStructure.coinType(), so its
     * stock BIP43 structure throws "coinType: Unknown network" for Signet.
     * Keep the normal BIP43 structure everywhere else and explicitly use the
     * BIP44/84 test-network coin type (1') for Signet.
     */
    public static KeyChainGroupStructure keyChainGroupStructure(BitcoinNetwork network) {
        if (network != BitcoinNetwork.SIGNET) {
            return KeyChainGroupStructure.BIP43;
        }

        return new KeyChainGroupStructure() {
            @Override
            public HDPath accountPathFor(
                    ScriptType outputScriptType,
                    org.bitcoinj.base.Network selectedNetwork) {
                if (selectedNetwork == BitcoinNetwork.SIGNET) {
                    return KeyChainGroupStructure.purpose(outputScriptType)
                            .extend(ChildNumber.ONE_HARDENED, KeyChainGroupStructure.account(0));
                }
                return KeyChainGroupStructure.BIP43.accountPathFor(outputScriptType, selectedNetwork);
            }
        };
    }

    public static NetworkParameters parameters(BitcoinNetwork network) {
        if (network == BitcoinNetwork.SIGNET) {
            // Resolve Signet directly instead of going through the generic
            // NetworkParameters registry. This is important on Android/R8,
            // where registry-based network resolution can otherwise report
            // Signet as an unknown network at runtime.
            return SigNetParams.get();
        }
        return MainNetParams.get();
    }

    /**
     * Keeps the existing Mainnet wallet in the app's original files directory for
     * backward compatibility. Signet gets its own directory and SPV chain.
     */
    public static File walletDirectory(Context context, BitcoinNetwork network) {
        if (!isSupported(network)) {
            network = BitcoinNetwork.MAINNET;
        }
        File filesDir = context.getApplicationContext().getFilesDir();
        if (network == BitcoinNetwork.MAINNET) {
            return filesDir;
        }
        File dir = new File(filesDir, NETWORKS_DIR + File.separator + storageName(network));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static boolean isSupported(BitcoinNetwork network) {
        return network == BitcoinNetwork.MAINNET || network == BitcoinNetwork.SIGNET;
    }

    public static String storageName(BitcoinNetwork network) {
        if (network == BitcoinNetwork.SIGNET) {
            return "signet";
        }
        if (network == BitcoinNetwork.MAINNET) {
            return "mainnet";
        }
        return network.name().toLowerCase(Locale.US);
    }

    public static String displayName(Context context, BitcoinNetwork network) {
        if (network == BitcoinNetwork.SIGNET) {
            return context.getString(R.string.network_option_signet);
        }
        return context.getString(R.string.network_option_mainnet);
    }

    public static String displayName(Context context) {
        return displayName(context, get(context));
    }
}
