package com.example.directwifi2;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.util.Log;

public class BeepHelper {
    private static final String TAG = "BeepHelper";
    private static ToneGenerator toneGen;
    private static int toneStream = -1;
    private static long lastBeepTimeMs = 0;
    private static Boolean lastBeepState = null;

    public static synchronized void playBeep(Context context, boolean connected) {
        long now = SystemClock.elapsedRealtime();

        // Evitiamo doppi bip identici entro 2 secondi
        if (lastBeepState != null && lastBeepState == connected && (now - lastBeepTimeMs < 2000)) {
            Log.d(TAG, "Beep skipped (debounce)");
            return;
        }

        lastBeepState = connected;
        lastBeepTimeMs = now;

        try {
            AudioManager audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
            int bestStream = chooseAudibleStream(audioManager);
            if (bestStream == -1) {
                Log.w(TAG, "No audible audio stream available for beep");
                return;
            }

            if (toneGen == null || toneStream != bestStream) {
                if (toneGen != null) {
                    toneGen.release();
                }
                toneGen = new ToneGenerator(bestStream, 100);
                toneStream = bestStream;
            }

            boolean started;
            if (connected) {
                Log.d(TAG, "Playing CONNECT beep");
                started = toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            } else {
                Log.d(TAG, "Playing DISCONNECT beep");
                started = toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
            }

            if (!started) {
                Log.w(TAG, "ToneGenerator.startTone returned false");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error playing beep", e);
        }
    }

    private static int chooseAudibleStream(AudioManager audioManager) {
        if (audioManager == null) {
            return AudioManager.STREAM_NOTIFICATION;
        }

        if (audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL
                && audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0) {
            return AudioManager.STREAM_NOTIFICATION;
        }

        if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) > 0) {
            return AudioManager.STREAM_ALARM;
        }

        if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            return AudioManager.STREAM_MUSIC;
        }

        return -1;
    }
}
