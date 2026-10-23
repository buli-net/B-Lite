package wallet.node;

import android.content.Context;

import org.bitcoinj.base.BitcoinNetwork;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import java.net.HttpURLConnection;
import java.net.URL;

/** Local-loopback RPC client. It never falls back to a public or third-party API. */
public final class BitcoinCoreRpcClient {
    private final File cookieFile;
    private final int port;

    public BitcoinCoreRpcClient(Context context, BitcoinNetwork network) {
        cookieFile = BitcoinCoreConfig.cookieFile(context, network);
        port = BitcoinCoreConfig.rpcPort(network);
    }

    public BitcoinCoreStatus fetchStatus() throws IOException, JSONException {
        JSONObject blockchain = call("getblockchaininfo", new JSONArray());
        JSONObject network = call("getnetworkinfo", new JSONArray());
        JSONObject mempool = call("getmempoolinfo", new JSONArray());

        return new BitcoinCoreStatus(
                blockchain.optString("chain", "unknown"),
                blockchain.optString("bestblockhash", ""),
                blockchain.optInt("blocks", 0),
                blockchain.optInt("headers", 0),
                network.optInt("connections", 0),
                mempool.optInt("size", 0),
                blockchain.optDouble("verificationprogress", 0.0d),
                blockchain.optBoolean("initialblockdownload", true),
                blockchain.optBoolean("pruned", false),
                blockchain.optInt("pruneheight", 0),
                blockchain.optLong("size_on_disk", 0L),
                blockchain.optLong("prune_target_size", 0L));
    }

    public JSONObject call(String method, JSONArray params) throws IOException, JSONException {
        if (!cookieFile.isFile()) {
            throw new IOException("Bitcoin Core RPC cookie is not available yet.");
        }

        String cookie = readCookie(cookieFile);
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "1.0");
        request.put("id", "blite-node-status");
        request.put("method", method);
        request.put("params", params == null ? new JSONArray() : params);

        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(1800);
            connection.setReadTimeout(3500);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            String authorization = Base64.getEncoder().encodeToString(
                    cookie.getBytes(StandardCharsets.UTF_8));
            connection.setRequestProperty("Authorization", "Basic " + authorization);
            byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }

            int responseCode = connection.getResponseCode();
            InputStream responseStream = responseCode >= 200 && responseCode < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String responseBody = readAll(responseStream);
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Bitcoin Core RPC returned HTTP " + responseCode + ".");
            }
            JSONObject envelope = new JSONObject(responseBody);
            JSONObject error = envelope.optJSONObject("error");
            if (error != null && !error.isNull("code")) {
                throw new IOException("Bitcoin Core RPC error " + error.optInt("code") + ": "
                        + error.optString("message", "Unknown RPC error"));
            }
            JSONObject result = envelope.optJSONObject("result");
            if (result == null) {
                throw new IOException("Bitcoin Core RPC response has no result object.");
            }
            return result;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readCookie(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(Math.max(file.length(), 1L), 8192L)];
            int count = input.read(buffer);
            if (count <= 0) throw new IOException("Bitcoin Core RPC cookie is empty.");
            return new String(buffer, 0, count, StandardCharsets.UTF_8).trim();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }
}
