package eu.berdosi.app.heartbeat;

import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Sends heartbeat status and thumbnail frames to the Python Flask server.
 *
 * All network calls run on a single background thread so they never block
 * the camera / UI threads.  If the server is unreachable the error is logged
 * and silently dropped — the Android app keeps working regardless.
 *
 * POST /api/device_update   — JSON status + optional JPEG thumbnail (multipart)
 *
 * Key fix vs. original: the ExecutorService is never permanently shut down.
 * The old stop() called executor.shutdown() which permanently killed the thread
 * pool — any report() after onPause()/onResume() would silently drop all jobs.
 * Now stop() just sets a flag and the executor is recreated in ensureExecutor().
 */
class ServerReporter {

    private static final String TAG = "ServerReporter";

    /** Thumbnail side length sent to server (keeps bandwidth low). */
    private static final int THUMB_SIZE = 128;
    /** JPEG quality for the thumbnail (0-100). */
    private static final int THUMB_QUALITY = 60;
    /** HTTP connect + read timeout in milliseconds. */
    private static final int TIMEOUT_MS = 2000;

    // ── State shared with background thread ──────────────────────────────────

    /** Latest status snapshot. Replaced atomically; old value dropped if not yet sent. */
    private final AtomicReference<StatusSnapshot> pendingStatus = new AtomicReference<>();

    /** Latest thumbnail. Replaced atomically; only the most-recent frame is kept. */
    private final AtomicReference<Bitmap> pendingThumb = new AtomicReference<>();

    /** Set to true by stop(); cleared and executor recreated by ensureExecutor(). */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private ExecutorService executor;
    private final String serverBaseUrl;
    private final int playerId;

    ServerReporter(String serverBaseUrl, int playerId) {
        // Normalise: strip trailing slash
        this.serverBaseUrl = serverBaseUrl.endsWith("/")
                ? serverBaseUrl.substring(0, serverBaseUrl.length() - 1)
                : serverBaseUrl;
        this.playerId = playerId;
        executor = Executors.newSingleThreadExecutor();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Queue a status update.  The thumbnail is optional; pass null to skip it.
     * Non-blocking — returns immediately.
     *
     * Safe to call after stop() — the executor is transparently recreated.
     */
    void report(Phase phase, boolean fingerPresent, float currentBpm,
                float measurementProgress, Bitmap thumbSource) {

        ensureExecutor();

        StatusSnapshot snap = new StatusSnapshot(
                playerId, phase, fingerPresent, currentBpm, measurementProgress,
                System.currentTimeMillis());
        pendingStatus.set(snap);

        if (thumbSource != null && !thumbSource.isRecycled()) {
            Bitmap scaled = scaleBitmap(thumbSource, THUMB_SIZE);
            Bitmap old = pendingThumb.getAndSet(scaled);
            if (old != null && !old.isRecycled()) old.recycle();
        }

        executor.execute(this::flush);
    }

    /**
     * Signal that the reporter should pause sending (e.g. app going to background).
     * Does NOT permanently destroy the executor — report() recreates it if needed.
     */
    void stop() {
        stopped.set(true);
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * If stop() was called previously, recreate the executor so new report()
     * calls can proceed.
     */
    private synchronized void ensureExecutor() {
        if (stopped.getAndSet(false) || executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadExecutor();
            Log.d(TAG, "Executor recreated");
        }
    }

    private void flush() {
        StatusSnapshot snap = pendingStatus.getAndSet(null);
        if (snap == null) return;

        Bitmap thumb = pendingThumb.getAndSet(null);

        try {
            if (thumb != null) {
                postMultipart(snap, thumb);
            } else {
                postJson(snap);
            }
        } catch (Exception e) {
            Log.w(TAG, "Report failed (server unreachable?): " + e.getMessage());
        } finally {
            if (thumb != null && !thumb.isRecycled()) thumb.recycle();
        }
    }

    /** Send status only (no frame). */
    private void postJson(StatusSnapshot snap) throws Exception {
        byte[] body = snap.toJson().getBytes(StandardCharsets.UTF_8);

        URL url = new URL(serverBaseUrl + "/api/device_update");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "HTTP " + code + " from server");
            } else {
                Log.d(TAG, "Reported phase=" + snap.phase + " bpm=" + snap.currentBpm);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Send status + JPEG thumbnail as multipart/form-data. */
    private void postMultipart(StatusSnapshot snap, Bitmap thumb) throws Exception {
        ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
        thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, jpegOut);
        byte[] jpegBytes = jpegOut.toByteArray();

        String boundary = "----HeartbeatBoundary7MA4YWxkTrZu0gW";
        String jsonPart = snap.toJson();

        URL url = new URL(serverBaseUrl + "/api/device_update");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();

            // --- JSON part ---
            String jsonHeader = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"status\"\r\n"
                    + "Content-Type: application/json\r\n\r\n";
            os.write(jsonHeader.getBytes(StandardCharsets.UTF_8));
            os.write(jsonPart.getBytes(StandardCharsets.UTF_8));
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));

            // --- JPEG part ---
            String jpegHeader = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"frame\"; filename=\"frame.jpg\"\r\n"
                    + "Content-Type: image/jpeg\r\n\r\n";
            os.write(jpegHeader.getBytes(StandardCharsets.UTF_8));
            os.write(jpegBytes);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));

            // --- End ---
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.d(TAG, "HTTP " + code + " from server (multipart)");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        float scale = (float) maxSide / Math.max(w, h);
        if (scale >= 1f) return src.copy(src.getConfig(), false);
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        return Bitmap.createScaledBitmap(src, nw, nh, false);
    }

    // ── Data classes ──────────────────────────────────────────────────────────

    enum Phase {
        IDLE,           // waiting for finger
        FINGER_DETECTED,// finger seen, about to start
        MEASURING,      // actively measuring
        RESULT          // measurement finished, showing BPM
    }

    static class StatusSnapshot {
        final int playerId;
        final Phase phase;
        final boolean fingerPresent;
        final float currentBpm;
        final float measurementProgress; // 0.0 – 1.0
        final long timestampMs;

        StatusSnapshot(int playerId, Phase phase, boolean fingerPresent,
                       float currentBpm, float measurementProgress, long timestampMs) {
            this.playerId = playerId;
            this.phase = phase;
            this.fingerPresent = fingerPresent;
            this.currentBpm = currentBpm;
            this.measurementProgress = measurementProgress;
            this.timestampMs = timestampMs;
        }

        String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("player_id", playerId);
                o.put("phase", phase.name().toLowerCase());
                o.put("finger_present", fingerPresent);
                o.put("current_bpm", (double) currentBpm);
                o.put("measurement_progress", (double) measurementProgress);
                o.put("timestamp_ms", timestampMs);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }
    }
}