package wallet.browser;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.os.Message;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

import wallet.main.R;
import wallet.ui.BaseActivity;

/** Native WebView browser with local history, bookmarks, downloads, tabs and background audio support. */
public final class BrowserActivity extends BaseActivity {
    private static final int REQUEST_FILE = 8401;
    private static final int REQUEST_WEB_PERMISSION = 8402;
    private static final int REQUEST_GEO_PERMISSION = 8403;
    private static final int REQUEST_POST_NOTIFICATIONS = 8404;
    private static final int MAX_HISTORY = 100;
    private static final String PREFS = "browser_storage";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_BOOKMARKS = "bookmarks";

    private EditText address;
    private Button backButton;
    private Button forwardButton;
    private Button privateButton;
    private Button backgroundButton;
    private ProgressBar progress;
    private FrameLayout webContainer;
    private TextView status;
    private final ArrayList<BrowserTab> tabs = new ArrayList<>();
    private int activeTab = -1;
    private boolean privateMode;
    private boolean backgroundSupport;
    private BrowserTab mediaOwnerTab;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingPermissionRequest;
    private String[] pendingWebResources;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private BroadcastReceiver mediaCommandReceiver;
    private View browserRoot;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int savedSystemUiVisibility;
    private final Handler mediaStateHandler = new Handler(Looper.getMainLooper());
    private boolean lastObservedMediaPlaying;
    private final Runnable mediaStatePoll = new Runnable() {
        @Override public void run() {
            if (!backgroundSupport || isFinishing()) return;
            WebView web = mediaOwnerTab == null ? currentWebView() : mediaOwnerTab.webView;
            if (web == null) {
                publishMediaState(false);
                mediaStateHandler.postDelayed(this, 5000L);
                return;
            }
            try {
                web.evaluateJavascript("(function(){var m=Array.prototype.slice.call(document.querySelectorAll('video,audio'));return m.some(function(x){return !x.paused && !x.ended;})?'1':'0';})()",
                        value -> {
                            if (!backgroundSupport || isFinishing()) return;
                            boolean playingNow = "1".equals(value) || "\"1\"".equals(value);
                            publishMediaState(playingNow);
                            mediaStateHandler.postDelayed(mediaStatePoll, 5000L);
                        });
            } catch (RuntimeException ignored) {
                mediaStateHandler.postDelayed(this, 5000L);
            }
        }
    };

    private static final class BrowserTab {
        final WebView webView;
        String title;
        String url;
        BrowserTab(WebView webView) { this.webView = webView; }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_browser);
        browserRoot = findViewById(R.id.browserRoot);
        setTitle(R.string.browser_title);
        address = findViewById(R.id.browserAddress);
        backButton = findViewById(R.id.browserBack);
        forwardButton = findViewById(R.id.browserForward);
        privateButton = findViewById(R.id.browserPrivate);
        backgroundButton = findViewById(R.id.browserBackground);
        progress = findViewById(R.id.browserProgress);
        webContainer = findViewById(R.id.browserWebContainer);
        status = findViewById(R.id.browserStatus);

        findViewById(R.id.browserGo).setOnClickListener(v -> navigateFromAddress());
        findViewById(R.id.browserHome).setOnClickListener(v -> loadHome());
        backButton.setOnClickListener(v -> { if (currentWebView() != null && currentWebView().canGoBack()) currentWebView().goBack(); });
        forwardButton.setOnClickListener(v -> { if (currentWebView() != null && currentWebView().canGoForward()) currentWebView().goForward(); });
        findViewById(R.id.browserRefresh).setOnClickListener(v -> { if (currentWebView() != null) currentWebView().reload(); });
        findViewById(R.id.browserTabs).setOnClickListener(v -> showTabs());
        findViewById(R.id.browserBookmark).setOnClickListener(v -> toggleBookmark());
        findViewById(R.id.browserHistory).setOnClickListener(v -> showStoredList(false));
        findViewById(R.id.browserFind).setOnClickListener(v -> showFindDialog());
        findViewById(R.id.browserShare).setOnClickListener(v -> shareCurrentPage());
        privateButton.setOnClickListener(v -> togglePrivateMode());
        backgroundButton.setOnClickListener(v -> toggleBackgroundSupport());
        findViewById(R.id.browserDownloads).setOnClickListener(v -> showDownloads());
        findViewById(R.id.browserClearData).setOnClickListener(v -> confirmClearData());
        address.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                navigateFromAddress();
                return true;
            }
            return false;
        });

        registerMediaReceiver();
        createTab(null, true);
        String initialUrl = getIntent() == null ? null : getIntent().getStringExtra("browser_url");
        if (initialUrl != null && !initialUrl.trim().isEmpty()) {
            navigate(initialUrl);
        } else {
            loadHome();
        }
        updateBrowserControls();
    }

    private void registerMediaReceiver() {
        mediaCommandReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent == null ? null : intent.getAction();
                String script;
                if (BrowserPlaybackService.ACTION_MEDIA_PLAY.equals(action)) {
                    script = "document.querySelectorAll('video,audio').forEach(function(m){try{m.play().catch(function(){});}catch(e){}});";
                } else if (BrowserPlaybackService.ACTION_MEDIA_PAUSE.equals(action)
                        || BrowserPlaybackService.ACTION_MEDIA_STOP.equals(action)) {
                    script = "document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});";
                } else {
                    return;
                }
                BrowserTab target = mediaOwnerTab == null ? currentTab() : mediaOwnerTab;
                if (target != null) {
                    try { target.webView.evaluateJavascript(script, null); } catch (RuntimeException ignored) { }
                }
                if (BrowserPlaybackService.ACTION_MEDIA_STOP.equals(action)) {
                    backgroundSupport = false;
                    lastObservedMediaPlaying = false;
                    mediaStateHandler.removeCallbacks(mediaStatePoll);
                    mediaOwnerTab = null;
                    updateBrowserControls();
                } else if (BrowserPlaybackService.ACTION_MEDIA_PLAY.equals(action)) {
                    lastObservedMediaPlaying = true;
                } else if (BrowserPlaybackService.ACTION_MEDIA_PAUSE.equals(action)) {
                    lastObservedMediaPlaying = false;
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BrowserPlaybackService.ACTION_MEDIA_PLAY);
        filter.addAction(BrowserPlaybackService.ACTION_MEDIA_PAUSE);
        filter.addAction(BrowserPlaybackService.ACTION_MEDIA_STOP);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mediaCommandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mediaCommandReceiver, filter);
        }
    }

    private void createTab(String url, boolean activate) {
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !privateMode);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setGeolocationEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setDefaultTextEncodingName("UTF-8");

        BrowserTab tab = new BrowserTab(webView);
        tabs.add(tab);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl(), request.isForMainFrame());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(Uri.parse(url), true);
            }
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                tab.url = url;
                if (currentTab() == tab) {
                    address.setText(url);
                    progress.setProgress(0);
                    progress.setVisibility(View.VISIBLE);
                    status.setText(R.string.browser_loading);
                    updateBrowserControls();
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                tab.url = url;
                String title = view.getTitle();
                tab.title = title == null || title.trim().isEmpty() ? url : title;
                if (tabs.get(activeTab < 0 ? 0 : activeTab) == tab) {
                    address.setText(url);
                    status.setText(tab.title);
                }
                if (!privateMode && isWebUrl(url)) saveHistory(tab.title, url);
                if (backgroundSupport) updatePlaybackServicePage();
                updateBrowserControls();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler,
                    android.net.http.SslError error) {
                handler.cancel();
                runOnUiThread(() -> Toast.makeText(BrowserActivity.this,
                        R.string.browser_security_warning, Toast.LENGTH_LONG).show());
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request.isForMainFrame() && currentTab() == tab) status.setText(error.getDescription());
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                if (tabs.get(activeTab < 0 ? 0 : activeTab) == tab) {
                    progress.setProgress(newProgress);
                    progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                tab.title = title;
                if (tabs.get(activeTab < 0 ? 0 : activeTab) == tab) {
                    setTitle(title == null || title.trim().isEmpty() ? getString(R.string.browser_title) : title);
                    status.setText(title == null ? "" : title);
                    if (backgroundSupport) updatePlaybackServicePage();
                }
            }
            @Override public boolean onShowFileChooser(WebView webView,
                    ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (fileChooserParams != null && fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(intent, getString(R.string.browser_title)), REQUEST_FILE);
                } catch (ActivityNotFoundException ex) {
                    fileChooserCallback = null;
                    filePathCallback.onReceiveValue(null);
                    return false;
                }
                return true;
            }
            @Override public boolean onCreateWindow(WebView view, boolean isDialog,
                    boolean isUserGesture, Message resultMsg) {
                if (!isUserGesture) return false;
                createTab(null, true);
                BrowserTab newTab = tabs.get(tabs.size() - 1);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(newTab.webView);
                resultMsg.sendToTarget();
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> requestWebPermission(request));
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin,
                    GeolocationPermissions.Callback callback) {
                runOnUiThread(() -> requestGeolocation(origin, callback));
            }
            @Override public void onGeolocationPermissionsHidePrompt() {
                pendingGeoCallback = null;
                pendingGeoOrigin = null;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreen(view, callback);
            }
            @Override public void onHideCustomView() {
                exitFullscreen();
            }
        });
        webView.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) ->
                downloadFile(tab.webView, downloadUrl, userAgent, contentDisposition, mimeType));
        if (activate) switchToTab(tabs.size() - 1);
        if (url != null && isWebUrl(url)) webView.loadUrl(url);
    }

    private boolean routeUrl(Uri uri, boolean mainFrame) {
        if (uri == null || uri.getScheme() == null) return true;
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if ("http".equals(scheme) || "https".equals(scheme)) return false;
        if ("about".equals(scheme) && "blank".equalsIgnoreCase(uri.getLastPathSegment())) return false;
        if (!mainFrame) return true;
        if ("bitcoin".equals(scheme)) {
            try {
                Intent send = new Intent(Intent.ACTION_VIEW, uri);
                send.setClassName(this, "wallet.send.SendActivity");
                startActivity(send);
            } catch (RuntimeException ex) {
                Toast.makeText(this, R.string.browser_external_open_failed, Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if ("intent".equals(scheme) || "javascript".equals(scheme) || "file".equals(scheme)
                || "content".equals(scheme) || "data".equals(scheme) || "blob".equals(scheme)) {
            return true;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, R.string.browser_external_open_failed, Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private static boolean isWebUrl(String raw) {
        if (raw == null) return false;
        String value = raw.toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://");
    }

    private WebView currentWebView() {
        if (activeTab < 0 || activeTab >= tabs.size()) return null;
        return tabs.get(activeTab).webView;
    }

    private BrowserTab currentTab() {
        if (activeTab < 0 || activeTab >= tabs.size()) return null;
        return tabs.get(activeTab);
    }

    private void switchToTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        webContainer.removeAllViews();
        activeTab = index;
        BrowserTab tab = tabs.get(index);
        if (tab.webView.getParent() instanceof ViewGroup) {
            ((ViewGroup) tab.webView.getParent()).removeView(tab.webView);
        }
        webContainer.addView(tab.webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        address.setText(tab.url == null ? "" : tab.url);
        setTitle(tab.title == null ? getString(R.string.browser_title) : tab.title);
        status.setText(tab.title == null ? getString(R.string.browser_ready) : tab.title);
        updateBrowserControls();
        if (backgroundSupport && mediaOwnerTab == null) mediaOwnerTab = tab;
        if (backgroundSupport) updatePlaybackServicePage();
    }

    private void navigateFromAddress() {
        String input = address.getText() == null ? "" : address.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show();
            return;
        }
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(), 0);
        address.clearFocus();
        navigate(input);
    }

    private void navigate(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return;
        String url;
        Uri parsed = Uri.parse(value);
        if (value.matches("(?i)^[a-z][a-z0-9+.-]*:.*") && parsed.getScheme() != null) {
            String scheme = parsed.getScheme().toLowerCase(Locale.ROOT);
            if ("http".equals(scheme) || "https".equals(scheme)) url = value;
            else if ("bitcoin".equals(scheme)) { routeUrl(parsed, true); return; }
            else if (scheme.equals("javascript") || scheme.equals("file") || scheme.equals("content")
                    || scheme.equals("data") || scheme.equals("intent") || scheme.equals("blob")) {
                Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show();
                return;
            } else { routeUrl(parsed, true); return; }
        } else if (value.contains(" ") || !value.contains(".")) {
            try { url = "https://www.google.com/search?q=" + URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
            catch (Exception ex) { Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show(); return; }
        } else {
            url = "https://" + value;
        }
        if (!isWebUrl(url)) {
            Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show();
            return;
        }
        WebView web = currentWebView();
        if (web == null) { createTab(url, true); return; }
        address.setText(url);
        web.loadUrl(url);
    }

    private void loadHome() {
        navigate("https://www.google.com/");
    }

    private void updateBrowserControls() {
        WebView web = currentWebView();
        backButton.setEnabled(web != null && web.canGoBack());
        forwardButton.setEnabled(web != null && web.canGoForward());
        privateButton.setText(privateMode ? R.string.browser_private_active : R.string.browser_private);
        backgroundButton.setText(backgroundSupport ? R.string.browser_background_stop : R.string.browser_background_play);
    }

    private void showTabs() {
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) {
            BrowserTab tab = tabs.get(i);
            labels.add((i == activeTab ? "● " : "") + (tab.title == null ? getString(R.string.browser_new_tab) : tab.title)
                    + "\n" + (tab.url == null ? "about:blank" : tab.url));
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.browser_tabs_title))
                .setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels),
                        (d, which) -> switchToTab(which))
                .setNeutralButton(R.string.browser_new_tab, (d, which) -> createTab(null, true))
                .setNegativeButton(R.string.browser_close_tab, (d, which) -> closeCurrentTab())
                .setPositiveButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
            if (tabs.size() > 1) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.browser_close_tab)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.browser_close_tab, (d, w) -> closeTabAt(position))
                        .show();
            }
            return true;
        });
    }

    private void closeCurrentTab() { closeTabAt(activeTab); }

    private void closeTabAt(int index) {
        if (index < 0 || index >= tabs.size()) return;
        BrowserTab removed = tabs.get(index);
        if (backgroundSupport && removed == mediaOwnerTab) {
            try { removed.webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){try{m.pause();}catch(e){}});", null); }
            catch (RuntimeException ignored) { }
            mediaStateHandler.removeCallbacks(mediaStatePoll);
            try { startService(new Intent(this, BrowserPlaybackService.class).setAction(BrowserPlaybackService.ACTION_STOP)); }
            catch (RuntimeException ignored) { }
            backgroundSupport = false;
            lastObservedMediaPlaying = false;
            mediaOwnerTab = null;
        }
        tabs.remove(index);
        if (removed.webView.getParent() instanceof ViewGroup) ((ViewGroup) removed.webView.getParent()).removeView(removed.webView);
        removed.webView.stopLoading();
        removed.webView.setWebChromeClient(null);
        removed.webView.setWebViewClient(null);
        removed.webView.destroy();
        if (tabs.isEmpty()) {
            createTab(null, true);
            loadHome();
        } else {
            int next = Math.min(index, tabs.size() - 1);
            switchToTab(next);
        }
    }

    private void toggleBookmark() {
        BrowserTab tab = currentTab();
        if (tab == null || !isWebUrl(tab.url)) {
            Toast.makeText(this, R.string.browser_invalid_url, Toast.LENGTH_SHORT).show();
            return;
        }
        JSONArray bookmarks = readArray(KEY_BOOKMARKS);
        int found = findByUrl(bookmarks, tab.url);
        if (found >= 0) {
            bookmarks.remove(found);
            writeArray(KEY_BOOKMARKS, bookmarks);
            Toast.makeText(this, R.string.browser_bookmark_removed, Toast.LENGTH_SHORT).show();
        } else {
            JSONObject item = new JSONObject();
            try { item.put("title", tab.title == null ? tab.url : tab.title); item.put("url", tab.url); item.put("time", System.currentTimeMillis()); bookmarks.put(item); }
            catch (JSONException ignored) { }
            writeArray(KEY_BOOKMARKS, bookmarks);
            Toast.makeText(this, R.string.browser_bookmark_saved, Toast.LENGTH_SHORT).show();
        }
    }

    private int findByUrl(JSONArray array, String url) {
        for (int i = 0; i < array.length(); i++) {
            JSONObject row = array.optJSONObject(i);
            if (row != null && url.equals(row.optString("url"))) return i;
        }
        return -1;
    }

    private void saveHistory(String title, String url) {
        JSONArray old = readArray(KEY_HISTORY);
        JSONArray next = new JSONArray();
        JSONObject entry = new JSONObject();
        try { entry.put("title", title == null ? url : title); entry.put("url", url); entry.put("time", System.currentTimeMillis()); next.put(entry); }
        catch (JSONException ignored) { }
        for (int i = 0; i < old.length() && next.length() < MAX_HISTORY; i++) {
            JSONObject row = old.optJSONObject(i);
            if (row != null && !url.equals(row.optString("url"))) next.put(row);
        }
        writeArray(KEY_HISTORY, next);
    }

    private JSONArray readArray(String key) {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "[]");
        try { return new JSONArray(raw); } catch (JSONException ex) { return new JSONArray(); }
    }

    private void writeArray(String key, JSONArray value) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, value.toString()).apply();
    }

    private void showStoredList(boolean bookmarks) {
        String key = bookmarks ? KEY_BOOKMARKS : KEY_HISTORY;
        JSONArray rows = readArray(key);
        ArrayList<JSONObject> items = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null) {
                items.add(row);
                labels.add(row.optString("title", row.optString("url")) + "\n" + row.optString("url"));
            }
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(bookmarks ? R.string.browser_bookmarks_title : R.string.browser_history_title)
                .setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                        labels.isEmpty() ? new String[]{getString(R.string.browser_empty)} : labels.toArray(new String[0])),
                        (d, which) -> {
                            if (which >= 0 && which < items.size()) navigate(items.get(which).optString("url"));
                        })
                .setPositiveButton(android.R.string.cancel, null)
                .setNegativeButton(bookmarks ? android.R.string.cancel : R.string.browser_clear_history,
                        (d, which) -> { if (!bookmarks) clearHistoryOnly(); })
                .create();
        dialog.show();
        if (dialog.getListView() != null) {
            dialog.getListView().setOnItemLongClickListener((parent, view, position, id) -> {
                if (position >= 0 && position < items.size() && bookmarks) {
                    JSONObject item = items.get(position);
                    JSONArray latest = readArray(KEY_BOOKMARKS);
                    int remove = findByUrl(latest, item.optString("url"));
                    if (remove >= 0) latest.remove(remove);
                    writeArray(KEY_BOOKMARKS, latest);
                    dialog.dismiss();
                    Toast.makeText(this, R.string.browser_bookmark_removed, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
    }

    private void clearHistoryOnly() {
        writeArray(KEY_HISTORY, new JSONArray());
        Toast.makeText(this, R.string.browser_cleared, Toast.LENGTH_SHORT).show();
    }

    private void showFindDialog() {
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setHint(R.string.browser_find_hint);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.browser_find)
                .setView(query)
                .setNegativeButton(R.string.browser_find_previous, (d, w) -> {
                    WebView web = currentWebView();
                    if (web != null) web.findNext(false);
                })
                .setPositiveButton(R.string.browser_find_next, (d, w) -> {
                    WebView web = currentWebView();
                    String text = query.getText().toString();
                    if (web != null && !text.isEmpty()) {
                        web.findAllAsync(text);
                        web.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
                            if (isDoneCounting && numberOfMatches == 0) Toast.makeText(this, R.string.browser_no_match, Toast.LENGTH_SHORT).show();
                        });
                    }
                }).create();
        dialog.setOnShowListener(d -> query.requestFocus());
        dialog.show();
    }

    private void shareCurrentPage() {
        BrowserTab tab = currentTab();
        if (tab == null || tab.url == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, tab.url)
                .putExtra(Intent.EXTRA_SUBJECT, tab.title == null ? tab.url : tab.title);
        startActivity(Intent.createChooser(intent, getString(R.string.browser_share)));
    }

    private void togglePrivateMode() {
        if (!privateMode) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.browser_private_title)
                    .setMessage(R.string.browser_private_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        privateMode = true;
                        for (BrowserTab tab : tabs) CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, false);
                        clearWebStorage(false);
                        updateBrowserControls();
                        Toast.makeText(this, R.string.browser_private_enabled, Toast.LENGTH_LONG).show();
                    }).show();
        } else {
            privateMode = false;
            for (BrowserTab tab : tabs) CookieManager.getInstance().setAcceptThirdPartyCookies(tab.webView, true);
            clearWebStorage(false);
            updateBrowserControls();
            Toast.makeText(this, R.string.browser_private_disabled, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.browser_clear_data_title)
                .setMessage(R.string.browser_clear_data_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    clearWebStorage(true);
                    Toast.makeText(this, R.string.browser_cleared, Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void clearWebStorage(boolean clearHistory) {
        CookieManager cookies = CookieManager.getInstance();
        cookies.removeAllCookies(null);
        cookies.flush();
        for (BrowserTab tab : tabs) {
            tab.webView.clearCache(true);
            tab.webView.clearFormData();
            if (clearHistory) tab.webView.clearHistory();
        }
        if (clearHistory) writeArray(KEY_HISTORY, new JSONArray());
        try { android.webkit.WebStorage.getInstance().deleteAllData(); }
        catch (RuntimeException ignored) { }
    }

    private void toggleBackgroundSupport() {
        if (backgroundSupport) {
            mediaStateHandler.removeCallbacks(mediaStatePoll);
            try { startService(new Intent(this, BrowserPlaybackService.class).setAction(BrowserPlaybackService.ACTION_STOP)); }
            catch (RuntimeException ignored) { }
            backgroundSupport = false;
            lastObservedMediaPlaying = false;
            updateBrowserControls();
            Toast.makeText(this, R.string.browser_background_stopped, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            return;
        }
        WebView web = currentWebView();
        if (web == null) {
            startBackgroundService(false);
            return;
        }
        web.evaluateJavascript("(function(){var m=Array.prototype.slice.call(document.querySelectorAll('video,audio'));return m.some(function(x){return !x.paused && !x.ended;})?'1':'0';})()",
                value -> startBackgroundService("1".equals(value) || "\"1\"".equals(value)));
    }

    private void startBackgroundService(boolean mediaPlaying) {
        BrowserTab tab = currentTab();
        mediaOwnerTab = tab;
        Intent service = new Intent(this, BrowserPlaybackService.class)
                .setAction(BrowserPlaybackService.ACTION_START)
                .putExtra(BrowserPlaybackService.EXTRA_TITLE, tab == null ? getString(R.string.browser_title) : tab.title)
                .putExtra(BrowserPlaybackService.EXTRA_URL, tab == null ? "" : tab.url)
                .putExtra(BrowserPlaybackService.EXTRA_PLAYING, mediaPlaying);
        try {
            ContextCompat.startForegroundService(this, service);
            backgroundSupport = true;
            lastObservedMediaPlaying = mediaPlaying;
            updateBrowserControls();
            mediaStateHandler.removeCallbacks(mediaStatePoll);
            mediaStateHandler.postDelayed(mediaStatePoll, 5000L);
            Toast.makeText(this, R.string.browser_background_started, Toast.LENGTH_LONG).show();
        } catch (RuntimeException ex) {
            backgroundSupport = false;
            Toast.makeText(this, R.string.browser_download_failed, Toast.LENGTH_LONG).show();
            updateBrowserControls();
        }
    }

    private void publishMediaState(boolean isPlaying) {
        if (lastObservedMediaPlaying == isPlaying) return;
        lastObservedMediaPlaying = isPlaying;
        Intent state = new Intent(this, BrowserPlaybackService.class)
                .setAction(BrowserPlaybackService.ACTION_STATE)
                .putExtra(BrowserPlaybackService.EXTRA_PLAYING, isPlaying);
        try { startService(state); } catch (RuntimeException ignored) { }
    }

    private void updatePlaybackServicePage() {
        BrowserTab tab = mediaOwnerTab == null ? currentTab() : mediaOwnerTab;
        if (tab == null || !backgroundSupport) return;
        Intent service = new Intent(this, BrowserPlaybackService.class)
                .setAction(BrowserPlaybackService.ACTION_PAGE)
                .putExtra(BrowserPlaybackService.EXTRA_TITLE, tab.title)
                .putExtra(BrowserPlaybackService.EXTRA_URL, tab.url);
        try { startService(service); } catch (RuntimeException ignored) { }
    }

    private void showDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, R.string.browser_external_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadFile(WebView webView, String url, String userAgent,
            String contentDisposition, String mimeType) {
        if (url == null || (!url.startsWith("https://") && !url.startsWith("http://"))) {
            Toast.makeText(this, R.string.browser_download_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimeType);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            if (userAgent != null) request.addRequestHeader("User-Agent", userAgent);
            request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType));
            request.setDescription(url);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType));
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("Download service unavailable");
            manager.enqueue(request);
            Toast.makeText(this, R.string.browser_download_started, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ex) {
            Toast.makeText(this, R.string.browser_download_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void requestWebPermission(PermissionRequest request) {
        String[] resources = request.getResources();
        ArrayList<String> permissions = new ArrayList<>();
        for (String resource : resources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) permissions.add(Manifest.permission.CAMERA);
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) permissions.add(Manifest.permission.RECORD_AUDIO);
            else { request.deny(); return; }
        }
        boolean allGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) allGranted = false;
        }
        if (allGranted) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.browser_permission_title)
                    .setMessage(R.string.browser_permission_message)
                    .setNegativeButton(android.R.string.cancel, (d, w) -> request.deny())
                    .setPositiveButton(android.R.string.ok, (d, w) -> request.grant(resources))
                    .show();
            return;
        }
        pendingPermissionRequest = request;
        pendingWebResources = resources;
        requestPermissions(permissions.toArray(new String[0]), REQUEST_WEB_PERMISSION);
    }

    private void requestGeolocation(String origin, GeolocationPermissions.Callback callback) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.browser_permission_title)
                .setMessage(R.string.browser_permission_message + "\n" + origin)
                .setNegativeButton(android.R.string.cancel, (d, w) -> callback.invoke(origin, false, false))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED;
                    boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED;
                    if (fine || coarse) {
                        callback.invoke(origin, true, false);
                    } else {
                        pendingGeoCallback = callback;
                        pendingGeoOrigin = origin;
                        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_GEO_PERMISSION);
                    }
                }).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WEB_PERMISSION) {
            boolean granted = grantResults.length > 0;
            for (int result : grantResults) granted &= result == PackageManager.PERMISSION_GRANTED;
            if (pendingPermissionRequest != null) {
                if (granted) pendingPermissionRequest.grant(pendingWebResources);
                else pendingPermissionRequest.deny();
            }
            pendingPermissionRequest = null;
            pendingWebResources = null;
        } else if (requestCode == REQUEST_GEO_PERMISSION) {
            boolean granted = false;
            for (int result : grantResults) granted |= result == PackageManager.PERMISSION_GRANTED;
            if (pendingGeoCallback != null) pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        } else if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) toggleBackgroundSupport();
            else Toast.makeText(this, R.string.browser_background_note, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE && fileChooserCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) results[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) results = new Uri[]{data.getData()};
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
        }
    }

    private void enterFullscreen(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden();
            return;
        }
        fullscreenView = view;
        fullscreenCallback = callback;
        savedSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
        browserRoot.setVisibility(View.GONE);
        addContentView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        try {
            ViewGroup parent = (ViewGroup) fullscreenView.getParent();
            if (parent != null) parent.removeView(fullscreenView);
        } catch (RuntimeException ignored) { }
        fullscreenView = null;
        browserRoot.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(savedSystemUiVisibility);
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            exitFullscreen();
            return;
        }
        WebView web = currentWebView();
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else if (tabs.size() > 1) {
            closeCurrentTab();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        mediaStateHandler.removeCallbacks(mediaStatePoll);
        if (mediaCommandReceiver != null) {
            try { unregisterReceiver(mediaCommandReceiver); } catch (RuntimeException ignored) { }
        }
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (pendingPermissionRequest != null) {
            try { pendingPermissionRequest.deny(); } catch (RuntimeException ignored) { }
            pendingPermissionRequest = null;
        }
        if (privateMode) clearWebStorage(false);
        if (isFinishing() && backgroundSupport) {
            try { startService(new Intent(this, BrowserPlaybackService.class).setAction(BrowserPlaybackService.ACTION_STOP)); }
            catch (RuntimeException ignored) { }
        }
        exitFullscreen();
        for (BrowserTab tab : tabs) {
            try {
                if (tab.webView.getParent() instanceof ViewGroup) ((ViewGroup) tab.webView.getParent()).removeView(tab.webView);
                tab.webView.stopLoading();
                tab.webView.destroy();
            } catch (RuntimeException ignored) { }
        }
        tabs.clear();
        super.onDestroy();
    }
}
