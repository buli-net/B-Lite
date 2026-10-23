package wallet.node;

import android.content.Context;

import org.bitcoinj.base.BitcoinNetwork;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import wallet.network.NetworkConfig;

/** Runtime configuration for the optional native Bitcoin Core pruned node. */
public final class BitcoinCoreConfig {
    public static final String BINARY_NAME = "libbitcoind.so";
    public static final int PRUNE_TARGET_MIB = 2048;
    public static final int MAINNET_RPC_PORT = 8332;
    public static final int SIGNET_RPC_PORT = 38332;

    private BitcoinCoreConfig() { }

    public static File binary(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, BINARY_NAME);
    }

    /** A separate Core datadir is used for each chain; wallet files remain managed by bitcoinj. */
    public static File dataDirectory(Context context, BitcoinNetwork network) {
        return new File(new File(context.getFilesDir(), "bitcoin-core"), NetworkConfig.storageName(network));
    }

    public static File cookieFile(Context context, BitcoinNetwork network) {
        return new File(dataDirectory(context, network), "rpc.cookie");
    }

    public static int rpcPort(BitcoinNetwork network) {
        return network == BitcoinNetwork.SIGNET ? SIGNET_RPC_PORT : MAINNET_RPC_PORT;
    }

    public static List<String> command(Context context, BitcoinNetwork network) {
        File dataDirectory = dataDirectory(context, network);
        File cookieFile = cookieFile(context, network);
        List<String> args = new ArrayList<>();
        args.add(binary(context).getAbsolutePath());
        args.add("-datadir=" + dataDirectory.getAbsolutePath());
        args.add("-server=1");
        args.add("-daemon=0");
        args.add("-disablewallet=1");
        args.add("-txindex=0");
        args.add("-prune=" + PRUNE_TARGET_MIB);
        args.add("-dbcache=128");
        args.add("-maxconnections=16");
        args.add("-maxmempool=50");
        args.add("-rpcbind=127.0.0.1");
        args.add("-rpcallowip=127.0.0.1");
        args.add("-rpcport=" + rpcPort(network));
        args.add("-rpccookiefile=" + cookieFile.getAbsolutePath());
        args.add("-listen=1");
        args.add("-discover=0");
        args.add("-upnp=0");
        args.add("-natpmp=0");
        if (network == BitcoinNetwork.SIGNET) {
            args.add("-signet=1");
        }
        return args;
    }

    public static boolean isSupportedNetwork(BitcoinNetwork network) {
        return network == BitcoinNetwork.MAINNET || network == BitcoinNetwork.SIGNET;
    }
}
