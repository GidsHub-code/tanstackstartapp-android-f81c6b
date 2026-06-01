package app.git2app.dev.workers.nnadigideon20.tanstackstartapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private SwipeRefreshLayout refresh;
    private ValueCallback<Uri[]> fileChooserCallback;
    private ActivityResultLauncher<Intent> fileChooserLauncher;
    private PermissionRequest pendingWebPermissionRequest;

    private static final String START_URL = "https://tanstack-start-app.nnadigideon20.workers.dev";
    private static final String HOST = "tanstack-start-app.nnadigideon20.workers.dev";
    private static final int REQ_PERMISSIONS = 4242;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Swap from splash theme back to the normal app theme before drawing the WebView.
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        // Force status bar and navigation bar to solid black on every generated app.
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        // Use light (white) icons on the black bars.
        try {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(flags);
        } catch (Throwable ignored) {}
        setContentView(R.layout.activity_main);
        refresh = findViewById(R.id.refresh);
        webView = findViewById(R.id.webview);

        createNotificationChannel();
        requestRuntimePermissions();
        registerFileChooser();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " SignalMeApp/2.2");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    pendingWebPermissionRequest = request;
                    request.grant(request.getResources());
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = filePathCallback;
                Intent intent = params.createIntent();
                try {
                    fileChooserLauncher.launch(intent);
                    return true;
                } catch (Exception e) {
                    fileChooserCallback = null;
                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri uri = req.getUrl();
                String scheme = uri.getScheme();
                if ("tel".equals(scheme) || "mailto".equals(scheme) || "sms".equals(scheme) || "geo".equals(scheme) || "intent".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                String host = uri.getHost();
                if (host != null && (host.equals(HOST) || host.endsWith("." + HOST))) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) { refresh.setRefreshing(true); }
            @Override
            public void onPageFinished(WebView view, String url) {
                refresh.setRefreshing(false);
                String saved = getSharedPreferences("fcm", MODE_PRIVATE).getString("token", null);
                if (saved != null) {
                    String js = "window.__FCM_TOKEN__ = '" + saved + "';"
                        + "if(window.onFcmToken) window.onFcmToken('" + saved + "');";
                    view.evaluateJavascript(js, null);
                }
            }
        });

        // Listen for new FCM tokens and push them into the WebView live
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
            .registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context ctx, android.content.Intent intent) {
                    String token = intent.getStringExtra("token");
                    if (token != null && webView != null) {
                        String js = "window.__FCM_TOKEN__ = '" + token + "';"
                            + "if(window.onFcmToken) window.onFcmToken('" + token + "');";
                        runOnUiThread(() -> webView.evaluateJavascript(js, null));
                    }
                }
            }, new android.content.IntentFilter("FCM_TOKEN"));

        refresh.setOnRefreshListener(() -> webView.reload());
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            String initialUrl = extractSafeUrl(getIntent());
            webView.loadUrl(initialUrl != null ? initialUrl : START_URL);
        }
    }

    /**
     * Cross-App Scripting hardening: validate any URL coming from an Intent
     * before loading it into the WebView. Only allow http(s) URLs whose host
     * matches the app's own origin (HOST or a subdomain of it). Rejects
     * javascript:, data:, file:, content:, and any third-party origin.
     */
    private String extractSafeUrl(Intent intent) {
        if (intent == null) return null;
        String candidate = intent.getStringExtra("open_url");
        if (candidate == null || candidate.isEmpty()) {
            Uri data = intent.getData();
            if (data != null) candidate = data.toString();
        }
        if (candidate == null || candidate.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(candidate);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            String host = uri.getHost();
            if (host == null) return null;
            host = host.toLowerCase(java.util.Locale.ROOT);
            String allowed = HOST.toLowerCase(java.util.Locale.ROOT);
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return uri.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void registerFileChooser() {
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (fileChooserCallback == null) return;
                Uri[] uris = WebChromeClient.FileChooserParams.parseResult(result.getResultCode(), result.getData());
                fileChooserCallback.onReceiveValue(uris);
                fileChooserCallback = null;
            }
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                getString(R.string.default_notification_channel_id),
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Push notifications");
            channel.enableLights(true);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 250, 250, 250});
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void requestRuntimePermissions() {
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        String[] candidates;
        if (Build.VERSION.SDK_INT >= 33) {
            candidates = new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            candidates = new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
        for (String p : candidates) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String url = extractSafeUrl(intent);
        if (url != null && webView != null) {
            webView.loadUrl(url);
        }
    }
}
