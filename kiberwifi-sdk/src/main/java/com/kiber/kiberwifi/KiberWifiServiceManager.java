package com.kiber.kiberwifi;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class KiberWifiServiceManager {
    public static final int START_RESULT_OK = com.kiber.sdk.KiberWifiServiceManager.START_RESULT_OK;
    public static final int START_RESULT_SUSPENDED = com.kiber.sdk.KiberWifiServiceManager.START_RESULT_SUSPENDED;
    private KiberWifiServiceManager() {
    }

    public enum KiberStatus {
        IDLING,
        SCANNING,
        MONITORING,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    public interface KiberEventListener {
        void onKiberEvent(@NonNull KiberStatus status, @NonNull String message);
    }

    public static int ensureRunning(@NonNull Context context) {
        return com.kiber.sdk.KiberWifiServiceManager.ensureRunning(context);
    }

    public static void ensurePermissions(@NonNull Activity activity) {
        com.kiber.sdk.KiberWifiServiceManager.ensurePermissions(activity);
    }

    public static void setLanguage(@NonNull String languageCode) {
        com.kiber.sdk.KiberWifiServiceManager.setLanguage(languageCode);
    }

    public static void resetSessionState() {
        com.kiber.sdk.KiberWifiServiceManager.resetSessionState();
    }

    public static int start(@NonNull Context context, @NonNull String contentText, boolean connected) {
        return com.kiber.sdk.KiberWifiServiceManager.start(context, contentText, connected);
    }

    public static int start(@NonNull Activity activity, @NonNull String deviceSerial, boolean autoConnect) {
        return com.kiber.sdk.KiberWifiServiceManager.start(activity, deviceSerial, autoConnect);
    }

    public static int startManaged(@NonNull Activity activity, @NonNull String contentText, boolean connected) {
        return com.kiber.sdk.KiberWifiServiceManager.startManaged(activity, contentText, connected);
    }

    public static void stop(@NonNull Context context) {
        com.kiber.sdk.KiberWifiServiceManager.stop(context);
    }

    public static void enableConnect(@NonNull Context context) {
        com.kiber.sdk.KiberWifiServiceManager.enableConnect(context);
    }

    public static void enableConnect(@NonNull Activity activity) {
        com.kiber.sdk.KiberWifiServiceManager.enableConnect(activity);
    }

    public static void disableConnect(@NonNull Context context) {
        com.kiber.sdk.KiberWifiServiceManager.disableConnect(context);
    }

    public static void clearLearnedBleFilters(@NonNull Context context) {
        com.kiber.sdk.KiberWifiServiceManager.clearLearnedBleFilters(context);
    }

    public static void changeDeviceSerial(@NonNull Context context, @NonNull String deviceSerial) {
        com.kiber.sdk.KiberWifiServiceManager.changeDeviceSerial(context, deviceSerial);
    }

    public static void setHostAppInForeground(@NonNull Context context, boolean inForeground) {
        com.kiber.sdk.KiberWifiServiceManager.setHostAppInForeground(context, inForeground);
    }

    @NonNull
    public static KiberStatus getStatus() {
        return mapStatus(com.kiber.sdk.KiberWifiServiceManager.getStatus());
    }

    public static boolean isTargetPresent() {
        return com.kiber.sdk.KiberWifiServiceManager.isTargetPresent();
    }

    public static void setListener(@Nullable KiberEventListener listener) {
        if (listener == null) {
            com.kiber.sdk.KiberWifiServiceManager.setListener(null);
            return;
        }
        com.kiber.sdk.KiberWifiServiceManager.setListener((status, message) ->
                listener.onKiberEvent(mapStatus(status), message));
    }

    @NonNull
    private static KiberStatus mapStatus(@NonNull com.kiber.sdk.KiberWifiServiceManager.KiberStatus status) {
        switch (status) {
            case IDLING:
                return KiberStatus.IDLING;
            case SCANNING:
                return KiberStatus.SCANNING;
            case MONITORING:
                return KiberStatus.MONITORING;
            case CONNECTING:
                return KiberStatus.CONNECTING;
            case CONNECTED:
                return KiberStatus.CONNECTED;
            case DISCONNECTED:
                return KiberStatus.DISCONNECTED;
            case ERROR:
            default:
                return KiberStatus.ERROR;
        }
    }
}
