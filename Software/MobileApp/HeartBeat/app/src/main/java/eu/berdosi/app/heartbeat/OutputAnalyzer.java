package eu.berdosi.app.heartbeat;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.TextureView;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

class OutputAnalyzer {
    private final MainActivity activity;

    private final ChartDrawer chartDrawer;

    private MeasureStore store;

    private final int measurementInterval = 45;
    private final int measurementLength = 15000; // ensure the number of data points is the power of two
    private final int clipLength = 3500;

    /**
     * How long (ms) to display the result before auto-restarting finger detection.
     */
    private static final int RESULT_DISPLAY_MS = 4000;

    /**
     * How often (in ms) we poll for a finger while idle.
     */
    private static final int FINGER_POLL_INTERVAL_MS = 150;

    private int detectedValleys = 0;
    private int ticksPassed = 0;

    private final CopyOnWriteArrayList<Long> valleys = new CopyOnWriteArrayList<>();

    private CountDownTimer timer;
    private CountDownTimer fingerPollTimer;

    private final Handler mainHandler;

    /** Reporter is set by MainActivity after it knows the server URL & player id. */
    private ServerReporter serverReporter;

    OutputAnalyzer(MainActivity activity, TextureView graphTextureView, Handler mainHandler) {
        this.activity = activity;
        this.chartDrawer = new ChartDrawer(graphTextureView);
        this.mainHandler = mainHandler;
    }

    void setServerReporter(ServerReporter reporter) {
        this.serverReporter = reporter;
    }

    // ── Finger detection ─────────────────────────────────────────────────────

    /**
     * Start the idle finger-detection loop.
     *
     * When a finger is detected the loop stops itself, turns on the flash via
     * {@link CameraService} and begins the pulse measurement.
     */
    void waitForFingerThenMeasure(TextureView cameraTextureView,
                                  TextureView graphTextureView,
                                  CameraService cameraService) {

        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME,
                activity.getString(R.string.waiting_for_finger));
        sendMessage(MainActivity.MESSAGE_APPEND_LOG, "Camera initialising…");

        report(ServerReporter.Phase.IDLE, false, 0f, 0f, null);

        // Wait 1 second before starting finger detection so the camera sensor
        // has time to settle.
        mainHandler.postDelayed(() -> {
            sendMessage(MainActivity.MESSAGE_APPEND_LOG, "Ready — place finger on lens");
            startFingerPollLoop(cameraTextureView, graphTextureView, cameraService);
        }, 1000);
    }
    private int fingerPresentTicks = 0; // Add this as a class-level variable

    private void startFingerPollLoop(TextureView cameraTextureView,
                                     TextureView graphTextureView,
                                     CameraService cameraService) {
        // Cancel any existing poll loop before starting a new one.
        if (fingerPollTimer != null) {
            fingerPollTimer.cancel();
            fingerPollTimer = null;
        }

        fingerPresentTicks = 0; // Reset on start

        fingerPollTimer = new CountDownTimer(Long.MAX_VALUE, FINGER_POLL_INTERVAL_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
                boolean fingerPresent = FingerDetector.isFingerPresent(cameraTextureView);

                // Grab the frame for continuous streaming
                Bitmap currentFrame = cameraTextureView.getBitmap();

                if (fingerPresent) {
                    fingerPresentTicks++;
                } else {
                    fingerPresentTicks = 0; // Reset if the finger is removed or flashes light
                }

                // Send status AND the current frame to the server every 150ms
                report(ServerReporter.Phase.IDLE, fingerPresent, 0f, 0f, currentFrame);

                // 150ms * 7 ticks = ~1.05 seconds of continuous darkness required
                if (fingerPresentTicks >= 7) {
                    cancel();

                    cameraService.enableTorch();

                    sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME,
                            activity.getString(R.string.finger_detected));
                    sendMessage(MainActivity.MESSAGE_APPEND_LOG, "Finger detected — torch on");

                    // Pass the final pre-torch frame to transition
                    report(ServerReporter.Phase.FINGER_DETECTED, true, 0f, 0f, currentFrame);

                    measurePulse(cameraTextureView, graphTextureView, cameraService);
                }
            }

            @Override
            public void onFinish() { /* Long.MAX_VALUE — never reached */ }
        };

        fingerPollTimer.start();
    }
    // ── Valley detection ─────────────────────────────────────────────────────

    private boolean detectValley() {
        final int valleyDetectionWindowSize = 13;
        CopyOnWriteArrayList<Measurement<Integer>> subList =
                store.getLastStdValues(valleyDetectionWindowSize);
        if (subList.size() < valleyDetectionWindowSize) {
            return false;
        } else {
            Integer referenceValue =
                    subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f)).measurement;

            for (Measurement<Integer> measurement : subList) {
                if (measurement.measurement < referenceValue) return false;
            }

            return (!subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f)).measurement.equals(
                    subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f) - 1).measurement));
        }
    }

    // ── Pulse measurement ─────────────────────────────────────────────────────

    /**
     * @param cameraTextureView  live camera preview surface
     * @param graphTextureView   chart surface — needed so onFinish() can restart the full loop
     * @param cameraService      needed to toggle torch and restart loop
     */
    void measurePulse(TextureView cameraTextureView,
                      TextureView graphTextureView,
                      CameraService cameraService) {

        store = new MeasureStore();
        detectedValleys = 0;
        ticksPassed = 0;
        valleys.clear(); // clear valley list for fresh measurement

        sendMessage(MainActivity.MESSAGE_APPEND_LOG,
                "Measuring… (stabilising for " + (clipLength / 1000) + "s)");

        timer = new CountDownTimer(measurementLength, measurementInterval) {

            private boolean clipLogged = false;

            @Override
            public void onTick(long millisUntilFinished) {
                if (clipLength > (++ticksPassed * measurementInterval)) return;

                if (!clipLogged) {
                    clipLogged = true;
                    sendMessage(MainActivity.MESSAGE_APPEND_LOG, "Signal stable — collecting data");
                }

                Thread thread = new Thread(() -> {
                    Bitmap currentBitmap = cameraTextureView.getBitmap();
                    if (currentBitmap == null) return;

                    int pixelCount = cameraTextureView.getWidth() * cameraTextureView.getHeight();
                    int measurement = 0;
                    int[] pixels = new int[pixelCount];

                    currentBitmap.getPixels(pixels, 0, cameraTextureView.getWidth(), 0, 0,
                            cameraTextureView.getWidth(), cameraTextureView.getHeight());

                    for (int pixelIndex = 0; pixelIndex < pixelCount; pixelIndex++) {
                        measurement += (pixels[pixelIndex] >> 16) & 0xff;
                    }

                    store.add(measurement);

                    if (detectValley()) {
                        detectedValleys = detectedValleys + 1;
                        valleys.add(store.getLastTimestamp().getTime());

                        String currentValue = String.format(
                                Locale.getDefault(),
                                activity.getResources().getQuantityString(
                                        R.plurals.measurement_output_template, detectedValleys),
                                (valleys.size() == 1)
                                        ? (60f * (detectedValleys) / (Math.max(1,
                                        (measurementLength - millisUntilFinished - clipLength) / 1000f)))
                                        : (60f * (detectedValleys - 1) / (Math.max(1,
                                        (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f))),
                                detectedValleys,
                                1f * (measurementLength - millisUntilFinished - clipLength) / 1000f);

                        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);

                        float liveBpm = (valleys.size() == 1)
                                ? (60f * detectedValleys / (Math.max(1,
                                (measurementLength - millisUntilFinished - clipLength) / 1000f)))
                                : (60f * (detectedValleys - 1) / (Math.max(1,
                                (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f)));
                        sendMessage(MainActivity.MESSAGE_APPEND_LOG,
                                String.format(Locale.getDefault(),
                                        "Peak #%d detected — ~%.0f BPM", detectedValleys, liveBpm));

                        float progress = 1f - (millisUntilFinished / (float) measurementLength);

                        Bitmap thumb = (detectedValleys % 2 == 0) ? currentBitmap : null;
                        report(ServerReporter.Phase.MEASURING, true, liveBpm, progress, thumb);
                    }

                    Thread chartDrawerThread = new Thread(
                            () -> chartDrawer.draw(store.getStdValues()));
                    chartDrawerThread.start();
                });
                thread.start();
            }

            @Override
            public void onFinish() {
                if (valleys.size() == 0) {
                    mainHandler.sendMessage(Message.obtain(
                            mainHandler,
                            MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                            "No valleys detected - there may be an issue when accessing the camera."));
                    sendMessage(MainActivity.MESSAGE_APPEND_LOG,
                            "ERROR: no peaks detected — check finger placement");
                    cameraService.disableTorch();
                    report(ServerReporter.Phase.IDLE, false, 0f, 0f, null);
                    // Still restart finger detection even on failure.
                    scheduleRestart(cameraTextureView, graphTextureView, cameraService, 2000);
                    return;
                }

                String currentValue = String.format(
                        Locale.getDefault(),
                        activity.getResources().getQuantityString(
                                R.plurals.measurement_output_template, detectedValleys - 1),
                        60f * (detectedValleys - 1) / (Math.max(1,
                                (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f)),
                        detectedValleys - 1,
                        1f * (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f);

                sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);

                float finalBpm = 60f * (detectedValleys - 1) /
                        (Math.max(1, (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f));

                report(ServerReporter.Phase.RESULT, true, finalBpm, 1f, null);

                // Build a concise human-readable summary for the log area.
                StringBuilder sb = new StringBuilder();
                sb.append("──────────────────\n");
                sb.append(String.format(Locale.getDefault(),
                        "Final BPM:   %.1f\n", finalBpm));
                sb.append(String.format(Locale.getDefault(),
                        "Peaks found: %d\n", detectedValleys - 1));
                sb.append(String.format(Locale.getDefault(),
                        "Window:      %.1f s\n",
                        (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f));
                sb.append("──────────────────\n");
                sb.append("Next measurement starting in " + (RESULT_DISPLAY_MS / 1000) + "s…");

                sendMessage(MainActivity.MESSAGE_UPDATE_FINAL, sb.toString());

                cameraService.disableTorch();
                sendMessage(MainActivity.MESSAGE_APPEND_LOG, "Done — torch off. Restarting soon…");

                // ── Auto-restart: show result briefly, then loop back to finger detection ──
                scheduleRestart(cameraTextureView, graphTextureView, cameraService, RESULT_DISPLAY_MS);
            }
        };

        activity.setViewState(MainActivity.VIEW_STATE.MEASUREMENT);
        timer.start();
    }

    /**
     * Post a delayed restart of the finger-detection loop on the main thread.
     * Using mainHandler ensures it runs on the UI thread (required for TextureView).
     */
    private void scheduleRestart(TextureView cameraTextureView,
                                 TextureView graphTextureView,
                                 CameraService cameraService,
                                 int delayMs) {
        mainHandler.postDelayed(() -> {
            // Reset view state so the UI is ready for a new measurement.
            activity.setViewState(MainActivity.VIEW_STATE.MEASUREMENT);
            waitForFingerThenMeasure(cameraTextureView, graphTextureView, cameraService);
        }, delayMs);
    }

    void stop() {
        if (fingerPollTimer != null) {
            fingerPollTimer.cancel();
            fingerPollTimer = null;
        }
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        // Cancel any pending restart callbacks.
        mainHandler.removeCallbacksAndMessages(null);
    }

    void sendMessage(int what, Object message) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = message;
        mainHandler.sendMessage(msg);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void report(ServerReporter.Phase phase, boolean fingerPresent,
                        float bpm, float progress, Bitmap thumb) {
        if (serverReporter != null) {
            serverReporter.report(phase, fingerPresent, bpm, progress, thumb);
        }
    }
}