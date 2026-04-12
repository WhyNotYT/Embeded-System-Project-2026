package eu.berdosi.app.heartbeat;

import android.graphics.Bitmap;
import android.view.TextureView;

/**
 * Detects whether a finger is covering the camera lens.
 *
 * Strategy: with no flash active, a covered lens produces an almost-black frame.
 * We sample a small centre crop of the TextureView bitmap and check the mean
 * brightness against a tunable threshold.
 *
 * Works on Android 7 (API 24) — no Camera2 metadata required.
 */
class FingerDetector {

    /**
     * Mean pixel brightness (0-255) below which we consider the lens covered.
     * Tune upward if detection is too slow, downward if you get false positives
     * in dark rooms.
     */
    private static final float DARKNESS_THRESHOLD = 32f;

    /**
     * Side-length of the centre-crop square we examine (pixels in bitmap space).
     * Keeping it small makes sampling fast.
     */
    private static final int SAMPLE_SIZE = 64;

    /**
     * Returns true when the camera appears to be covered by a finger.
     *
     * @param textureView The live-preview TextureView (flash must be OFF when calling this).
     */
    static boolean isFingerPresent(TextureView textureView) {
        Bitmap bmp = textureView.getBitmap();
        if (bmp == null) return false;

        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        if (bw < 2 || bh < 2) return false;

        // Centre crop — avoid edge artefacts from the lens barrel.
        int cropSize = Math.min(SAMPLE_SIZE, Math.min(bw, bh));
        int x0 = (bw - cropSize) / 2;
        int y0 = (bh - cropSize) / 2;

        int[] pixels = new int[cropSize * cropSize];
        bmp.getPixels(pixels, 0, cropSize, x0, y0, cropSize, cropSize);

        long sum = 0;
        for (int p : pixels) {
            // Extract R, G, B and compute rough luminance.
            int r = (p >> 16) & 0xff;
            int g = (p >>  8) & 0xff;
            int b =  p        & 0xff;
            sum += (r + g + b) / 3;
        }

        float mean = (float) sum / pixels.length;
        return mean < DARKNESS_THRESHOLD;
    }
}