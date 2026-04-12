package eu.berdosi.app.heartbeat;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Drop this Activity into your project temporarily.
 * Add to AndroidManifest.xml inside <application>:
 *
 *   <activity android:name=".NetworkDiagActivity" android:exported="true"/>
 *
 * Launch it from adb:
 *   adb shell am start -n eu.berdosi.app.heartbeat/.NetworkDiagActivity
 *
 * Or add a temporary button in MainActivity that starts this Activity.
 *
 * It tests: DNS → TCP ping → HTTP GET → HTTP POST → multipart POST
 * Each step prints pass/fail + the exact error so you know exactly where it breaks.
 */
public class NetworkDiagActivity extends Activity {

    private static final String TAG = "NetworkDiag";

    // ← Change this to match your server
    private static final String DEFAULT_URL = "http://192.168.1.100:5000";

    private TextView logView;
    private ScrollView scroll;
    private EditText urlField;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build UI programmatically — no layout XML needed.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 48, 24, 24);

        urlField = new EditText(this);
        urlField.setText(DEFAULT_URL);
        urlField.setHint("Server URL");
        root.addView(urlField);

        Button runBtn = new Button(this);
        runBtn.setText("Run Diagnostics");
        runBtn.setOnClickListener(v -> runDiagnostics(urlField.getText().toString().trim()));
        root.addView(runBtn);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setPadding(8, 8, 8, 8);
        scroll.addView(logView);
        root.addView(scroll);

        setContentView(root);
    }

    private void log(String msg) {
        Log.d(TAG, msg);
        ui.post(() -> {
            logView.append(msg + "\n");
            scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void runDiagnostics(String baseUrl) {
        logView.setText("");
        log("=== Network Diagnostics ===");
        log("Target: " + baseUrl);
        log("");

        new Thread(() -> {
            try {
                URL parsed = new URL(baseUrl);
                String host = parsed.getHost();
                int port = parsed.getPort() == -1 ? parsed.getDefaultPort() : parsed.getPort();

                // ── Step 1: DNS / IP reachability ─────────────────────────────
                log("── Step 1: DNS / Ping ──");
                try {
                    InetAddress addr = InetAddress.getByName(host);
                    log("  DNS OK → " + addr.getHostAddress());

                    // isReachable uses ICMP or TCP port 7 — often blocked; low timeout is fine
                    boolean reachable = addr.isReachable(2000);
                    log("  isReachable(2s): " + (reachable ? "YES ✓" : "NO (ICMP may be blocked — not fatal)"));
                } catch (Exception e) {
                    log("  FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    log("  → Check that the IP in SERVER_URL is correct and the phone");
                    log("    is on the same WiFi network as the server.");
                    log("");
                    log("Stopping — fix DNS/IP first.");
                    return;
                }

                // ── Step 2: TCP connection to server port ─────────────────────
                log("");
                log("── Step 2: TCP connect to " + host + ":" + port + " ──");
                try {
                    java.net.Socket sock = new java.net.Socket();
                    sock.connect(new java.net.InetSocketAddress(host, port), 3000);
                    sock.close();
                    log("  TCP connect OK ✓");
                } catch (Exception e) {
                    log("  FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    log("  → Flask may not be running, or a firewall is blocking port " + port + ".");
                    log("    On the server machine run:  python app.py");
                    log("    Check firewall:  sudo ufw allow " + port);
                    log("");
                    log("Stopping — fix TCP first.");
                    return;
                }

                // ── Step 3: HTTP GET / ────────────────────────────────────────
                log("");
                log("── Step 3: HTTP GET " + baseUrl + "/ ──");
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    c.setRequestMethod("GET");
                    int code = c.getResponseCode();
                    log("  HTTP " + code + (code < 400 ? " ✓" : " (unexpected — but TCP works)"));
                    c.disconnect();
                } catch (Exception e) {
                    log("  FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    if (e.getMessage() != null && e.getMessage().contains("CLEARTEXT")) {
                        log("");
                        log("  *** CLEARTEXT BLOCKED ***");
                        log("  AndroidManifest.xml is missing:");
                        log("    android:usesCleartextTraffic=\"true\"");
                        log("  in the <application> tag.");
                        log("  This is the most common cause of silent network failures.");
                    }
                    return;
                }

                // ── Step 4: POST JSON to /api/device_update ───────────────────
                log("");
                log("── Step 4: POST JSON → /api/device_update ──");
                try {
                    String json = "{\"player_id\":99,\"phase\":\"idle\","
                            + "\"finger_present\":false,\"current_bpm\":0.0,"
                            + "\"measurement_progress\":0.0,\"timestamp_ms\":"
                            + System.currentTimeMillis() + "}";
                    byte[] body = json.getBytes(StandardCharsets.UTF_8);

                    HttpURLConnection c = (HttpURLConnection)
                            new URL(baseUrl + "/api/device_update").openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setDoOutput(true);
                    c.getOutputStream().write(body);
                    c.getOutputStream().flush();

                    int code = c.getResponseCode();
                    String resp = readResponse(c);
                    log("  HTTP " + code + "  body: " + resp + (code == 200 ? " ✓" : " ✗"));
                    c.disconnect();

                    if (code == 200) {
                        log("  → Check the server terminal — you should see:");
                        log("    [DEVICE] Player 99 registered");
                    }
                } catch (Exception e) {
                    log("  FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }

                // ── Step 5: Multipart POST ────────────────────────────────────
                log("");
                log("── Step 5: Multipart POST → /api/device_update ──");
                try {
                    String boundary = "----DiagBoundary";
                    String json = "{\"player_id\":99,\"phase\":\"idle\","
                            + "\"finger_present\":false,\"current_bpm\":0.0,"
                            + "\"measurement_progress\":0.0,\"timestamp_ms\":"
                            + System.currentTimeMillis() + "}";

                    StringBuilder sb = new StringBuilder();
                    sb.append("--").append(boundary).append("\r\n");
                    sb.append("Content-Disposition: form-data; name=\"status\"\r\n");
                    sb.append("Content-Type: application/json\r\n\r\n");
                    sb.append(json).append("\r\n");
                    sb.append("--").append(boundary).append("--\r\n");
                    byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);

                    HttpURLConnection c = (HttpURLConnection)
                            new URL(baseUrl + "/api/device_update").openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type",
                            "multipart/form-data; boundary=" + boundary);
                    c.setDoOutput(true);
                    c.getOutputStream().write(body);
                    c.getOutputStream().flush();

                    int code = c.getResponseCode();
                    String resp = readResponse(c);
                    log("  HTTP " + code + "  body: " + resp + (code == 200 ? " ✓" : " ✗"));
                    c.disconnect();
                } catch (Exception e) {
                    log("  FAIL: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }

                log("");
                log("=== Done ===");

            } catch (Exception e) {
                log("Unexpected error: " + e);
            }
        }).start();
    }

    private String readResponse(HttpURLConnection c) {
        try {
            InputStream is = c.getResponseCode() < 400
                    ? c.getInputStream() : c.getErrorStream();
            if (is == null) return "(empty)";
            byte[] buf = new byte[512];
            int n = is.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "(empty)";
        } catch (Exception e) {
            return "(read error: " + e.getMessage() + ")";
        }
    }
}