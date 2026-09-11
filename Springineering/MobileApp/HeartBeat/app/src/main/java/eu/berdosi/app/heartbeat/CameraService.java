package eu.berdosi.app.heartbeat;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.Collections;
import java.util.Objects;

/**
 * Manages the Camera2 preview session.
 *
 * Changes vs. original:
 *  - Flash (torch) starts OFF so FingerDetector can work in ambient light.
 *  - {@link #enableTorch()} / {@link #disableTorch()} let OutputAnalyzer
 *    toggle the flash during the measurement cycle.
 *  - Internal helper {@link #applyTorch(boolean)} rebuilds the repeating
 *    capture request with the desired flash mode without restarting the session.
 */
class CameraService {
    private static final String TAG = "CameraService";

    private String cameraId;
    private final Activity activity;
    private final Handler handler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession previewSession;
    private CaptureRequest.Builder previewCaptureRequestBuilder;
    private Surface previewSurface;

    /** Tracks the current torch state so we can avoid redundant rebuilds. */
    private boolean torchEnabled = false;

    CameraService(Activity _activity, Handler _handler) {
        activity = _activity;
        handler = _handler;
    }

    void start(Surface surface) {
        this.previewSurface = surface;
        torchEnabled = false; // always start with flash off

        CameraManager cameraManager =
                (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = Objects.requireNonNull(cameraManager).getCameraIdList()[0];
        } catch (CameraAccessException | NullPointerException | ArrayIndexOutOfBoundsException e) {
            Log.e(TAG, "No access to camera", e);
            handler.sendMessage(Message.obtain(handler,
                    MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                    "No access to camera...."));
            return;
        }

        try {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "No permission to take photos");
                handler.sendMessage(Message.obtain(handler,
                        MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                        "No permission to take photos"));
                return;
            }

            if (cameraId == null) return;

            Objects.requireNonNull(cameraManager).openCamera(cameraId,
                    new CameraDevice.StateCallback() {

                        @Override
                        public void onOpened(@NonNull CameraDevice camera) {
                            cameraDevice = camera;

                            CameraCaptureSession.StateCallback stateCallback =
                                    new CameraCaptureSession.StateCallback() {

                                        @Override
                                        public void onConfigured(
                                                @NonNull CameraCaptureSession session) {
                                            previewSession = session;
                                            try {
                                                previewCaptureRequestBuilder =
                                                        cameraDevice.createCaptureRequest(
                                                                CameraDevice.TEMPLATE_PREVIEW);
                                                previewCaptureRequestBuilder.addTarget(previewSurface);

                                                // Flash OFF at startup — finger detection needs
                                                // ambient light to see the darkness of a covered lens.
                                                previewCaptureRequestBuilder.set(
                                                        CaptureRequest.FLASH_MODE,
                                                        CaptureRequest.FLASH_MODE_OFF);

                                                HandlerThread thread =
                                                        new HandlerThread("CameraPreview");
                                                thread.start();

                                                previewSession.setRepeatingRequest(
                                                        previewCaptureRequestBuilder.build(),
                                                        null, null);

                                            } catch (CameraAccessException e) {
                                                if (e.getMessage() != null) {
                                                    Log.e(TAG, e.getMessage());
                                                }
                                            }
                                        }

                                        @Override
                                        public void onConfigureFailed(
                                                @NonNull CameraCaptureSession session) {
                                            Log.e(TAG, "Session configuration failed");
                                        }
                                    };

                            try {
                                //noinspection deprecation
                                camera.createCaptureSession(
                                        Collections.singletonList(previewSurface),
                                        stateCallback, null);
                            } catch (CameraAccessException e) {
                                if (e.getMessage() != null) Log.e(TAG, e.getMessage());
                            }
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice camera) {}

                        @Override
                        public void onError(@NonNull CameraDevice camera, int error) {}
                    }, null);

        } catch (CameraAccessException | SecurityException e) {
            if (e.getMessage() != null) {
                Log.e(TAG, e.getMessage());
                handler.sendMessage(Message.obtain(handler,
                        MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE, e.getMessage()));
            }
        }
    }

    // ── Torch control ─────────────────────────────────────────────────────────

    /**
     * Turn the flash on so it illuminates the fingertip for PPG measurement.
     * Safe to call from any thread.
     */
    void enableTorch() {
        applyTorch(true);
    }

    /**
     * Turn the flash off (e.g. when measurement is complete or app pauses).
     * Safe to call from any thread.
     */
    void disableTorch() {
        applyTorch(false);
    }

    private void applyTorch(boolean enable) {
        if (torchEnabled == enable) return; // nothing to do
        torchEnabled = enable;

        if (previewSession == null || previewCaptureRequestBuilder == null) return;

        try {
            previewCaptureRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                    enable ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            previewSession.setRepeatingRequest(
                    previewCaptureRequestBuilder.build(), null, null);
            Log.d(TAG, "Torch " + (enable ? "ON" : "OFF"));
        } catch (CameraAccessException e) {
            Log.e(TAG, "applyTorch failed: " + e.getMessage());
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    void stop() {
        try {
            applyTorch(false);
            cameraDevice.close();
        } catch (Exception e) {
            Log.e(TAG, "cannot close camera device: " + e.getMessage());
        }
    }
}