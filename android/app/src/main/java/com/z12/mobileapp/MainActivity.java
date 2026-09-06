package com.z12.mobileapp;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The app is a thin wrapper: the WebView loads https://www.z12challenge.com/ (see
 * capacitor.config.json -> server.url), so none of our own JavaScript ever runs in it.
 * Push registration therefore happens here, natively — this is "Option B" from plan.md.
 * www/push.js holds the equivalent JS for the day the website itself opts in.
 */
public class MainActivity extends BridgeActivity {

    private static final String TAG = "Z12Push";

    /** Channel used by FCM when a notification arrives while the app is backgrounded. */
    private static final String CHANNEL_ID = "z12_default";

    private static final int REQ_POST_NOTIFICATIONS = 4711;

    /**
     * Backend endpoint that stores device tokens. Leave empty to only log the token
     * (which is all the local smoke test needs). Set it once the endpoint exists.
     */
    private static final String TOKEN_ENDPOINT = "";

    /** Pushes may only deep-link into the site itself, never an arbitrary URL. */
    private static final String ALLOWED_HOST = "z12challenge.com";

    /**
     * Event announcements are broadcast to this topic rather than to individual tokens —
     * the shell has no idea who is signed in on the website it wraps, so there is nobody
     * to address a token to. See onEventCreate/eventClosingReminder in Z12website-functions.
     */
    private static final String EVENTS_TOPIC = "events";

    private String pendingUrl;
    private boolean webViewReady;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Null when the device has no usable WebView; BridgeActivity already handled that.
        if (getBridge() != null) {
            getBridge().addWebViewListener(new WebViewListener() {
                @Override
                public void onPageLoaded(WebView webView) {
                    webViewReady = true;
                    if (pendingUrl != null) {
                        String url = pendingUrl;
                        pendingUrl = null;
                        webView.loadUrl(url);
                    }
                }
            });
        }

        createNotificationChannel();
        requestNotificationPermission();
        fetchAndReportToken();
        subscribeToEventsTopic();
    }

    /**
     * Also invoked with the launch intent from BridgeActivity.load(), so a cold start
     * from a notification tap comes through here too.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleNotificationIntent(intent);
    }

    // --- Registration ---------------------------------------------------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return; // Pre-Android 13 the manifest permission is enough.
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                new String[] { Manifest.permission.POST_NOTIFICATIONS },
                REQ_POST_NOTIFICATIONS
            );
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            getString(R.string.default_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(getString(R.string.default_notification_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * The token is per-install and can be rotated by FCM at any time; MessagingService
     * (from @capacitor/push-notifications) is what receives messages, we only need the
     * token here so the backend can address this device.
     */
    private void fetchAndReportToken() {
        FirebaseMessaging.getInstance()
            .getToken()
            .addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    Log.e(TAG, "Push registration error", task.getException());
                    return;
                }
                String token = task.getResult();
                Log.i(TAG, "Push registration token: " + token);
                postTokenToBackend(token);
            });
    }

    private void subscribeToEventsTopic() {
        FirebaseMessaging.getInstance()
            .subscribeToTopic(EVENTS_TOPIC)
            .addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    Log.i(TAG, "Subscribed to topic: " + EVENTS_TOPIC);
                } else {
                    Log.e(TAG, "Failed to subscribe to topic " + EVENTS_TOPIC, task.getException());
                }
            });
    }

    private void postTokenToBackend(String token) {
        if (TOKEN_ENDPOINT.isEmpty()) {
            return;
        }
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject body = new JSONObject();
                body.put("token", token);
                body.put("platform", "android");

                connection = (HttpURLConnection) new URL(TOKEN_ENDPOINT).openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                Log.i(TAG, "Token registration responded " + connection.getResponseCode());
            } catch (Exception e) {
                Log.e(TAG, "Failed to send token to backend", e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    // --- Notification taps ----------------------------------------------------

    /**
     * FCM delivers the message's data payload as intent extras when the user taps the
     * notification, so a "url" data field lands the user on the right page.
     */
    private void handleNotificationIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String target = intent.getStringExtra("url");
        if (target == null || target.isEmpty()) {
            return;
        }
        if (!isAllowedUrl(target)) {
            Log.w(TAG, "Ignoring push deep link outside " + ALLOWED_HOST + ": " + target);
            return;
        }
        if (webViewReady && getBridge() != null) {
            getBridge().getWebView().loadUrl(target);
        } else {
            pendingUrl = target;
        }
    }

    private boolean isAllowedUrl(String target) {
        Uri uri = Uri.parse(target);
        String host = uri.getHost();
        return "https".equals(uri.getScheme())
            && host != null
            && (host.equals(ALLOWED_HOST) || host.endsWith("." + ALLOWED_HOST));
    }
}
