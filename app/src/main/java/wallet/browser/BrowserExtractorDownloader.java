package wallet.browser;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP transport used by the stream extractor. */
final class BrowserExtractorDownloader extends Downloader {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    @Override
    public Response execute(Request request) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(request.url()).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod(request.httpMethod());
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");

            Map<String, List<String>> headers = request.headers();
            if (headers != null) {
                for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    boolean firstValue = true;
                    for (String value : entry.getValue()) {
                        if (value == null) continue;
                        if (firstValue) {
                            connection.setRequestProperty(entry.getKey(), value);
                            firstValue = false;
                        } else {
                            connection.addRequestProperty(entry.getKey(), value);
                        }
                    }
                }
            }

            byte[] payload = request.dataToSend();
            String method = request.httpMethod();
            if (payload != null && payload.length > 0
                    && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }

            int code = connection.getResponseCode();
            String message = connection.getResponseMessage();
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                responseHeaders.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }

            String body = "";
            if (!"HEAD".equalsIgnoreCase(method)) {
                InputStream input = code >= HttpURLConnection.HTTP_BAD_REQUEST
                        ? connection.getErrorStream() : connection.getInputStream();
                if (input != null) {
                    try (InputStream stream = input;
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
                        body = output.toString(StandardCharsets.UTF_8.name());
                    }
                }
            }
            return new Response(code, message, responseHeaders, body, connection.getURL().toString());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
