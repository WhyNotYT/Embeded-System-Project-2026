package eu.berdosi.app.heartbeat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;

import java.io.DataOutputStream;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class MainActivity extends Activity
        implements ActivityCompat.OnRequestPermissionsResultCallback {

    // ── Message types ─────────────────────────────────────────────────────────

    private final int REQUEST_CODE_CAMERA = 0;
    public static final int MESSAGE_UPDATE_REALTIME = 1;
    public static final int MESSAGE_UPDATE_FINAL    = 2;
    public static final int MESSAGE_CAMERA_NOT_AVAILABLE = 3;
    public static final int MESSAGE_APPEND_LOG      = 4;

    private static final int MENU_INDEX_NEW_MEASUREMENT = 0;
    private static final int MENU_INDEX_EXPORT_RESULT   = 1;
    private static final int MENU_INDEX_EXPORT_DETAILS  = 2;

    public enum VIEW_STATE { MEASUREMENT, SHOW_RESULTS }

    // ── Server configuration ──────────────────────────────────────────────────
    //
    // Change SERVER_URL to match your Python server's address on the LAN.
    // PLAYER_ID identifies this device (1 or 2) in 2-player mode.
    //
    private static final String SERVER_URL = "http://192.168.1.133:5000";
    private static final int    PLAYER_ID  = 2;

    // ── State ─────────────────────────────────────────────────────────────────

    private OutputAnalyzer analyzer;
    private ServerReporter serverReporter;
    private boolean justShared = false;

    /** Keeps the CPU running even when the screen would otherwise sleep. */
    private PowerManager.WakeLock wakeLock;

    // ── Handler ───────────────────────────────────────────────────────────────

    @SuppressLint("HandlerLeak")
    private final Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);

            if (msg.what == MESSAGE_UPDATE_REALTIME) {
                ((TextView) findViewById(R.id.textView)).setText(msg.obj.toString());
            }

            if (msg.what == MESSAGE_UPDATE_FINAL) {
                // Append the final summary to the log.
                appendLog(msg.obj.toString());
                // Note: we do NOT call setViewState(SHOW_RESULTS) here anymore —
                // auto-restart is handled inside OutputAnalyzer.scheduleRestart().
                // If you want the export buttons to appear briefly, uncomment the line below:
                // setViewState(VIEW_STATE.SHOW_RESULTS);
            }

            if (msg.what == MESSAGE_APPEND_LOG) {
                appendLog(msg.obj.toString());
            }

            if (msg.what == MESSAGE_CAMERA_NOT_AVAILABLE) {
                Log.w("camera", msg.obj.toString());
                ((TextView) findViewById(R.id.textView)).setText(R.string.camera_not_found);
                if (analyzer != null) analyzer.stop();

                // OutputAnalyzer.scheduleRestart() already handles re-launching the
                // finger poll loop after failure, so we don't duplicate that here.
            }
        }
    };

    private final CameraService cameraService = new CameraService(this, mainHandler);

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    private void disableProximitySensorRoot() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(p.getOutputStream());
            // Force the proximity sensor off via the sysfs interface
            os.writeBytes("echo 0 > /sys/class/sensors/proximity_sensor/enable\n");
            os.writeBytes("exit\n");
            os.flush();
        } catch (Exception e) {
            Log.e("ROOT", "Could not disable proximity sensor: " + e.getMessage());
        }
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the screen on indefinitely while this Activity is open
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        disableProximitySensorRoot();

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CODE_CAMERA);
        startScreenWatchdog();
    }
    private void startScreenWatchdog() {
        final Handler watchdogHandler = new Handler();
        watchdogHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (!pm.isInteractive()) { // If screen just turned off
                    PowerManager.WakeLock wl = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                                    PowerManager.ACQUIRE_CAUSES_WAKEUP, "Heartbeat:ForceOn");
                    wl.acquire(1000); // Force it on for 1 second
                    Log.d("Watchdog", "Screen was forced off, forcing it back ON");
                }
                watchdogHandler.postDelayed(this, 500); // Check every half-second
            }
        }, 500);
    }
    @Override
    protected void onResume() {
        super.onResume();

        // ── Keep screen on ────────────────────────────────────────────────────
        // FLAG_KEEP_SCREEN_ON prevents the proximity sensor from blanking the
        // display when the phone is placed face-down on a table.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Partial wake lock ensures the CPU keeps running even if the window
        // flag is somehow cleared by a system dialog or overlay.
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
// 0x00000020 is the hex code for PROXIMITY_SCREEN_OFF_WAKE_LOCK
// We combine it with ACQUIRE_CAUSES_WAKEUP and SCREEN_BRIGHT_WAKE_LOCK
        PowerManager.WakeLock wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP, "Heartbeat:SensorLock");

        wakeLock.acquire();

        // ── Server reporter ───────────────────────────────────────────────────
        // Recreate (or restart) the reporter on every resume.
        // ServerReporter.ensureExecutor() handles the case where stop() was
        // called in onPause() — the executor is transparently recreated.
        if (serverReporter != null) serverReporter.stop();
        serverReporter = new ServerReporter(SERVER_URL, PLAYER_ID);

        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler);
        analyzer.setServerReporter(serverReporter);

        TextureView cameraTextureView = findViewById(R.id.textureView2);
        SurfaceTexture previewSurfaceTexture = cameraTextureView.getSurfaceTexture();

        if ((previewSurfaceTexture != null) && !justShared) {
            Surface previewSurface = new Surface(previewSurfaceTexture);

            if (!this.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                Snackbar.make(
                        findViewById(R.id.constraintLayout),
                        getString(R.string.noFlashWarning),
                        Snackbar.LENGTH_LONG
                ).show();
            }

            ((Toolbar) findViewById(R.id.toolbar))
                    .getMenu().getItem(MENU_INDEX_NEW_MEASUREMENT).setVisible(false);

            cameraService.start(previewSurface);
            restartFingerDetection(cameraTextureView);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Release wake lock and screen-on flag.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }

        cameraService.stop();
        if (analyzer != null) analyzer.stop();
        if (serverReporter != null) {
            serverReporter.stop();
            serverReporter = null;
        }
        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_CAMERA) {
            if (!(grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Snackbar.make(
                        findViewById(R.id.constraintLayout),
                        getString(R.string.cameraPermissionRequired),
                        Snackbar.LENGTH_LONG
                ).show();
            }
        }
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return super.onPrepareOptionsMenu(menu);
    }

    // ── View state ────────────────────────────────────────────────────────────

    public void setViewState(VIEW_STATE state) {
        Menu appMenu = ((Toolbar) findViewById(R.id.toolbar)).getMenu();
        switch (state) {
            case MEASUREMENT:
                appMenu.getItem(MENU_INDEX_NEW_MEASUREMENT).setVisible(false);
                appMenu.getItem(MENU_INDEX_EXPORT_RESULT).setVisible(false);
                appMenu.getItem(MENU_INDEX_EXPORT_DETAILS).setVisible(false);
                findViewById(R.id.floatingActionButton).setVisibility(View.INVISIBLE);
                break;
            case SHOW_RESULTS:
                findViewById(R.id.floatingActionButton).setVisibility(View.VISIBLE);
                appMenu.getItem(MENU_INDEX_EXPORT_RESULT).setVisible(true);
                appMenu.getItem(MENU_INDEX_EXPORT_DETAILS).setVisible(true);
                appMenu.getItem(MENU_INDEX_NEW_MEASUREMENT).setVisible(true);
                break;
        }
    }

    // ── New measurement (button / menu) ───────────────────────────────────────

    public void onClickNewMeasurement(MenuItem item) { onClickNewMeasurement(); }
    public void onClickNewMeasurement(View view)     { onClickNewMeasurement(); }

    public void onClickNewMeasurement() {
        if (analyzer != null) analyzer.stop();

        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler);
        analyzer.setServerReporter(serverReporter);

        ((EditText)  findViewById(R.id.editText)).setText("");
        ((TextView)  findViewById(R.id.textView)).setText("");

        setViewState(VIEW_STATE.MEASUREMENT);

        TextureView cameraTextureView = findViewById(R.id.textureView2);
        SurfaceTexture previewSurfaceTexture = cameraTextureView.getSurfaceTexture();

        if (previewSurfaceTexture != null) {
            Surface previewSurface = new Surface(previewSurfaceTexture);
            cameraService.start(previewSurface);
            restartFingerDetection(cameraTextureView);
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    public void onClickExportResult(MenuItem item) {
        final Intent intent =
                getTextIntent((String) ((TextView) findViewById(R.id.textView)).getText());
        justShared = true;
        startActivity(Intent.createChooser(intent, getString(R.string.send_output_to)));
    }

    public void onClickExportDetails(MenuItem item) {
        final Intent intent =
                getTextIntent(((EditText) findViewById(R.id.editText)).getText().toString());
        justShared = true;
        startActivity(Intent.createChooser(intent, getString(R.string.send_output_to)));
    }

    private Intent getTextIntent(String intentText) {
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT,
                String.format(
                        getString(R.string.output_header_template),
                        new SimpleDateFormat(
                                getString(R.string.dateFormat),
                                Locale.getDefault()
                        ).format(new Date())
                ));
        intent.putExtra(Intent.EXTRA_TEXT, intentText);
        return intent;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void appendLog(String line) {
        EditText log = findViewById(R.id.editText);
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date());
        String entry = "[" + timestamp + "] " + line + "\n";
        log.append(entry);
        int scroll = log.getLayout() != null
                ? log.getLayout().getLineTop(log.getLineCount()) - log.getHeight()
                : 0;
        if (scroll > 0) log.scrollTo(0, scroll);
    }

    /**
     * (Re-)start the idle finger-watch loop on the given TextureView.
     */
    private void restartFingerDetection(TextureView cameraTextureView) {
        if (analyzer == null) return;
        TextureView graphView = findViewById(R.id.graphTextureView);
        analyzer.waitForFingerThenMeasure(cameraTextureView, graphView, cameraService);
    }
}