package com.kiber.sdk;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LocalServerSocket;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import java.io.IOException;

public class KiberWifiServiceManager extends Service {
    public static final int START_RESULT_OK = 0;
    public static final int START_RESULT_SUSPENDED = 1;
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

    public static final String ACTION_START = "com.kiber.sdk.action.START";
    public static final String ACTION_STOP = "com.kiber.sdk.action.STOP";
    public static final String ACTION_ENABLE_CONNECT = "com.kiber.sdk.action.ENABLE_CONNECT";
    public static final String ACTION_DISABLE_CONNECT = "com.kiber.sdk.action.DISABLE_CONNECT";
    public static final String ACTION_CHANGE_DEVICE = "com.kiber.sdk.action.CHANGE_DEVICE";
    private static final String EXTRA_CONTENT_TEXT = "extra_content_text";
    private static final String EXTRA_CONNECTED = "extra_connected";
    private static final String EXTRA_RADIO_TYPE = "extra_radio_type";
    private static final String EXTRA_DEVICE_SERIAL = "extra_device_serial";
    static final String EXTRA_PENDING_ACTION = "extra_pending_action";
    static final String PENDING_ACTION_NONE = "none";
    static final String PENDING_ACTION_START = "start";
    static final String PENDING_ACTION_ENABLE_CONNECT = "enable_connect";
    private static final String RADIO_TYPE_WIFI = "wifi";
    private static final String RADIO_TYPE_BT = "bt";

    private static final String TAG = "KiberWifiServiceManager";
    private static final String INTER_APP_LOCK_NAME = "com.kiber.sdk.KiberWifiServiceManager.lock";
    private static final String WAKELOCK_TAG = "DirectWifi2:BleScanWakeLock";
    private static final String CHANNEL_ID = "directwifi2_keepalive_channel_v3";
    private static final int NOTIFICATION_ID = 1001;
    
    private static final String SSID_PREFIX = "KIBERSCOPE-";
    private static final String BLE_PREFIX = "KS-";
    private static final int SSID_SUFFIX_LENGTH = 5;
    
    private static final String PREFS_NAME = "directwifi2_prefs";
    private static final String PREF_SSID_SUFFIX = "pref_ssid_suffix";
    private static final String PREF_AUTOCONNECT_ENABLED = "pref_autoconnect_enabled";
    private static final String PREF_CONNECT_ENABLED = "pref_connect_enabled";
    private static final String PREF_LEARNED_DEVICE_ADDRESSES = "pref_learned_device_addresses";
    private static final String PREF_CONNECTION_REFUSED_PENDING = "pref_connection_refused_pending";
    private static final String KIBERSCOPE_PASSPHRASE = "12345678";
    private static final int MAX_MANUAL_RETRY_PER_SESSION = 3;
    private static final Set<String> SUPPORTED_LANGUAGES =
            new LinkedHashSet<>(Arrays.asList("en", "it", "de", "fr", "es", "ru"));
    
    private static final long AUTOCONNECT_RETRY_DELAY_MS = 3_000L;
    private static final long FAST_START_WINDOW_MS = 10_000L;
    private static final long FAST_START_RETRY_DELAY_MS = 1_000L;
    private static final long BLE_SCAN_WINDOW_MS = 5_000L;
    private static final long DEVICE_PRESENT_TTL_MS = 10_000L;
    private static final long CONNECTED_STABILIZATION_MS = 500L;
    private static final long MIN_SCAN_START_INTERVAL_MS = 4_000L;
    private static final long SCAN_FAILED_BACKOFF_MS = 10_000L;
    private static volatile KiberStatus currentStatus = KiberStatus.IDLING;
    private static volatile KiberEventListener eventListener;
    private static volatile boolean targetPresentGlobal = false;
    private static volatile boolean serviceRunning = false;
    private static volatile boolean holdsInterAppLock = false;
    private static volatile LocalServerSocket interAppLockSocket = null;
    private static volatile boolean connectionRefusedDialogActive = false;
    private static volatile boolean retryBlockedForSession = false;
    private static volatile int manualRetryCountForSession = 0;
    private static volatile boolean suspended = false;
    private static volatile String sdkLanguage = "en";
    private static volatile boolean hostAppInForeground = false;
    private static volatile long lastRadioDialogAtMs = 0L;
    private static volatile String lastRadioDialogType = "";

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private ScanCallback bleScanCallback;
    
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean receiverRegistered = false;
    private boolean connectedState = false;
    private boolean connectionInProgress = false;
    private boolean isBleScanning = false;
    private boolean targetDevicePresent = false;
    private boolean scanUsedMacFilters = false;
    private boolean scanMatchedTargetName = false;
    private int scanResultLogBudget = 0;
    private PowerManager.WakeLock bleScanWakeLock;
    private Runnable bleScanTimeoutRunnable;
    
    private ConnectivityManager.NetworkCallback networkCallback;
    private long lastTargetSeenAtMs = 0L;
    private long lastScanStartAttemptAtMs = 0L;
    private long scanBackoffUntilMs = 0L;
    private long serviceCreatedAtMs = 0L;
    private String lastNotificationText = null;
    private boolean lastNotificationConnected = false;
    private final Runnable retryRunnable = this::attemptAutoConnectIfEnabled;
    private Runnable pendingConnectedConfirmRunnable;

    private static int start(Context context, String contentText, boolean connected) {
        if (suspended) {
            Log.i(TAG, "start() ignored: manager is suspended");
            return START_RESULT_SUSPENDED;
        }
        if (isManagedByOtherApp()) {
            Log.i(TAG, "start() ignored: another app is already managing Kiber WiFi");
            return START_RESULT_OK;
        }
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_CONTENT_TEXT, contentText);
        intent.putExtra(EXTRA_CONNECTED, connected);
        ContextCompat.startForegroundService(context, intent);
        return START_RESULT_OK;
    }

    public static int ensureRunning(Context context) {
        if (suspended) {
            Log.i(TAG, "ensureRunning() ignored: manager is suspended");
            return START_RESULT_SUSPENDED;
        }
        return start(context, buildAssociatedNotificationText(context), false);
    }

    public static int start(@NonNull Activity activity, @NonNull String deviceSerial, boolean autoConnect) {
        if (suspended) {
            Log.i(TAG, "start(activity) ignored: manager is suspended");
            return START_RESULT_SUSPENDED;
        }
        if (isManagedByOtherApp()) {
            Log.i(TAG, "start(activity) ignored: another app is already managing Kiber WiFi");
            return START_RESULT_OK;
        }
        String suffix = normalizeTargetSerial(deviceSerial);
        if (suffix == null) {
            throw new IllegalArgumentException("deviceSerial must be XXXXX (or KIBERSCOPE-XXXXX)");
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String previousSuffix = prefs.getString(PREF_SSID_SUFFIX, "");
        boolean serialChanged = previousSuffix != null && !previousSuffix.isEmpty()
                && !suffix.equalsIgnoreCase(previousSuffix);
        SharedPreferences.Editor editor = prefs.edit()
                .putString(PREF_SSID_SUFFIX, suffix)
                .putBoolean(PREF_AUTOCONNECT_ENABLED, autoConnect);
        if (serialChanged) {
            editor.putString(PREF_LEARNED_DEVICE_ADDRESSES, "");
        }
        editor.apply();
        if (serialChanged) {
            Log.d(TAG, "start(activity): serial changed " + previousSuffix + " -> " + suffix + ", clearing learned BLE filters");
        }
        String contentText = buildAssociatedNotificationText(activity);
        boolean connected = false;
        if (hasRuntimePermissions(activity, true) && hasBatteryOptimizationExemption(activity)) {
            return start(activity.getApplicationContext(), contentText, connected);
        }
        launchPermissionProxy(activity, PENDING_ACTION_START, contentText, connected);
        return START_RESULT_OK;
    }

    public static void stop(Context context) {
        Log.i(TAG, "stop() requested: serviceRunning=" + serviceRunning
                + ", holdsInterAppLock=" + holdsInterAppLock
                + ", suspended=" + suspended
                + ", retryBlocked=" + retryBlockedForSession);
        if (isManagedByOtherApp()) {
            Log.i(TAG, "stop() ignored: another app is managing Kiber WiFi");
            return;
        }
        if (!serviceRunning && !holdsInterAppLock) {
            Log.i(TAG, "stop() no-op: manager already idle");
            return;
        }
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_STOP);
        try {
            Log.i(TAG, "stop() dispatching ACTION_STOP via startService");
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "stop(): startService(ACTION_STOP) failed, trying stopService fallback", e);
            try {
                Log.i(TAG, "stop() dispatching stopService fallback");
                context.stopService(new Intent(context, KiberWifiServiceManager.class));
            } catch (Exception suppressed) {
                Log.w(TAG, "stop(): stopService fallback failed", suppressed);
            }
        }
        if (!serviceRunning) {
            releaseInterAppLock();
        }
    }

    private static void enableConnect(@NonNull Context context) {
        if (suspended) {
            Log.i(TAG, "enableConnect() ignored: manager is suspended");
            return;
        }
        if (isManagedByOtherApp()) {
            Log.i(TAG, "enableConnect() ignored: another app is already managing Kiber WiFi");
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CONNECT_ENABLED, true)
                .apply();
        if (!serviceRunning && !holdsInterAppLock) {
            Log.i(TAG, "enableConnect() requested while manager is not running: starting manager");
            start(context.getApplicationContext(), buildAssociatedNotificationText(context), false);
            return;
        }
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_ENABLE_CONNECT);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void enableConnect(@NonNull Activity activity) {
        if (suspended) {
            Log.i(TAG, "enableConnect(activity) ignored: manager is suspended");
            return;
        }
        if (isManagedByOtherApp()) {
            Log.i(TAG, "enableConnect(activity) ignored: another app is already managing Kiber WiFi");
            return;
        }
        if (hasRuntimePermissions(activity, true) && hasBatteryOptimizationExemption(activity)) {
            enableConnect(activity.getApplicationContext());
            return;
        }
        launchPermissionProxy(activity, PENDING_ACTION_ENABLE_CONNECT, null, false);
    }

    public static void disableConnect(@NonNull Context context) {
        if (isManagedByOtherApp()) {
            Log.i(TAG, "disableConnect() ignored: another app is already managing Kiber WiFi");
            return;
        }
        if (!serviceRunning && !holdsInterAppLock) {
            Log.i(TAG, "disableConnect() ignored: manager not running");
            return;
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CONNECT_ENABLED, false)
                .apply();
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_DISABLE_CONNECT);
        context.startService(intent);
    }

    private static void clearLearnedBleFilters(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LEARNED_DEVICE_ADDRESSES, "")
                .apply();
    }

    public static void changeDeviceSerial(@NonNull Context context, @NonNull String deviceSerial) {
        if (isManagedByOtherApp()) {
            Log.i(TAG, "changeDeviceSerial() ignored: another app is already managing Kiber WiFi");
            return;
        }
        if (!serviceRunning && !holdsInterAppLock) {
            Log.i(TAG, "changeDeviceSerial() ignored: manager not running");
            return;
        }
        String suffix = normalizeTargetSerial(deviceSerial);
        if (suffix == null) {
            throw new IllegalArgumentException("deviceSerial must be XXXXX (or KIBERSCOPE-XXXXX)");
        }
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_CHANGE_DEVICE);
        intent.putExtra(EXTRA_DEVICE_SERIAL, suffix);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void setListener(@Nullable KiberEventListener listener) {
        eventListener = listener;
    }

    public static void setHostAppInForeground(@NonNull Context context, boolean inForeground) {
        hostAppInForeground = inForeground;
        if (inForeground && serviceRunning) {
            maybeShowPendingConnectionRefusedDialog(context.getApplicationContext());
        }
    }

    public static void setLanguage(@Nullable String languageCode) {
        String normalized = languageCode == null ? "en" : languageCode.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            normalized = "en";
        }
        sdkLanguage = normalized;
    }

    public static void resetSessionState() {
        Log.i(TAG, "resetSessionState() requested");
        suspended = false;
        retryBlockedForSession = false;
        manualRetryCountForSession = 0;
        connectionRefusedDialogActive = false;
        currentStatus = KiberStatus.IDLING;
    }

    @NonNull
    public static KiberStatus getStatus() {
        return currentStatus;
    }

    public static boolean isTargetPresent() {
        return targetPresentGlobal;
    }

    private static void maybeShowPendingConnectionRefusedDialog(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_CONNECTION_REFUSED_PENDING, false)) {
            return;
        }
        prefs.edit().putBoolean(PREF_CONNECTION_REFUSED_PENDING, false).apply();
        if (manualRetryCountForSession >= MAX_MANUAL_RETRY_PER_SESSION) {
            retryBlockedForSession = true;
        }
        connectionRefusedDialogActive = true;
        Intent intent = new Intent(
                context,
                retryBlockedForSession ? KiberTooManyRetriesDialogActivity.class : KiberConnectionRefusedDialogActivity.class
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    private static synchronized boolean acquireInterAppLock() {
        if (holdsInterAppLock && interAppLockSocket != null) {
            Log.d(TAG, "acquireInterAppLock: already held by this process");
            return true;
        }
        try {
            interAppLockSocket = new LocalServerSocket(INTER_APP_LOCK_NAME);
            holdsInterAppLock = true;
            Log.i(TAG, "acquireInterAppLock: acquired");
            return true;
        } catch (IOException e) {
            holdsInterAppLock = false;
            interAppLockSocket = null;
            Log.w(TAG, "acquireInterAppLock: failed (owned by another process?)");
            return false;
        }
    }

    private static synchronized boolean isManagedByOtherApp() {
        if (serviceRunning || holdsInterAppLock) {
            Log.d(TAG, "isManagedByOtherApp=false (local service/lock active)");
            return false;
        }
        LocalServerSocket probe = null;
        try {
            probe = new LocalServerSocket(INTER_APP_LOCK_NAME);
            Log.d(TAG, "isManagedByOtherApp=false (lock free)");
            return false;
        } catch (IOException e) {
            Log.i(TAG, "isManagedByOtherApp=true (lock already held externally)");
            return true;
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static synchronized void releaseInterAppLock() {
        Log.i(TAG, "releaseInterAppLock() called: hadSocket=" + (interAppLockSocket != null));
        if (interAppLockSocket != null) {
            try {
                interAppLockSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "releaseInterAppLock(): close failed", e);
            }
        }
        interAppLockSocket = null;
        holdsInterAppLock = false;
    }

    private static void centerDialogMessage(@Nullable AlertDialog dialog) {
        if (dialog == null) {
            return;
        }
        TextView messageView = dialog.findViewById(android.R.id.message);
        if (messageView == null) {
            return;
        }
        messageView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        messageView.setGravity(Gravity.CENTER_HORIZONTAL);
    }

    private static String tr(@NonNull String key) {
        String lang = sdkLanguage;
        switch (key) {
            case "bg_loc_title":
                switch (lang) {
                    case "it": return "Permesso richiesto";
                    case "de": return "Berechtigung erforderlich";
                    case "fr": return "Autorisation requise";
                    case "es": return "Permiso requerido";
                    case "ru": return "Требуется разрешение";
                    default: return "Permission required";
                }
            case "bg_loc_msg":
                switch (lang) {
                    case "it": return "Per usare l'autoconnect in background, consenti la localizzazione sempre attiva nelle impostazioni dell'app.";
                    case "de": return "Um die automatische Verbindung im Hintergrund zu verwenden, erlauben Sie in den App-Einstellungen den Standortzugriff immer.";
                    case "fr": return "Pour utiliser la connexion automatique en arrière-plan, autorisez la localisation en permanence dans les paramètres de l'application.";
                    case "es": return "Para usar la conexión automática en segundo plano, permite la ubicación siempre activa en la configuración de la aplicación.";
                    case "ru": return "Чтобы использовать автоподключение в фоне, разрешите постоянный доступ к геопозиции в настройках приложения.";
                    default: return "To use background autoconnect, allow always-on location in app settings.";
                }
            case "bg_loc_open":
                switch (lang) {
                    case "it": return "Apri impostazioni app";
                    case "de": return "App-Einstellungen öffnen";
                    case "fr": return "Ouvrir les paramètres de l'application";
                    case "es": return "Abrir ajustes de la aplicación";
                    case "ru": return "Открыть настройки приложения";
                    default: return "Open app settings";
                }
            case "battery_title":
                switch (lang) {
                    case "it": return "Ottimizzazione batteria";
                    case "de": return "Akkuoptimierung";
                    case "fr": return "Optimisation de la batterie";
                    case "es": return "Optimización de batería";
                    case "ru": return "Оптимизация батареи";
                    default: return "Battery optimization";
                }
            case "battery_msg":
                switch (lang) {
                    case "it": return "Per migliorare la reattività in background, disattiva l'ottimizzazione batteria per questa app.";
                    case "de": return "Für bessere Reaktionsfähigkeit im Hintergrund deaktivieren Sie die Akkuoptimierung für diese App.";
                    case "fr": return "Pour une meilleure réactivité en arrière-plan, désactivez l'optimisation de la batterie pour cette application.";
                    case "es": return "Para una mejor respuesta en segundo plano, desactiva la optimización de batería para esta aplicación.";
                    case "ru": return "Для лучшей работы в фоне отключите оптимизацию батареи для этого приложения.";
                    default: return "For better background responsiveness, disable battery optimization for this app.";
                }
            case "battery_open":
                switch (lang) {
                    case "it": return "Disattiva ottimizzazione";
                    case "de": return "Optimierung deaktivieren";
                    case "fr": return "Désactiver l'optimisation";
                    case "es": return "Desactivar optimización";
                    case "ru": return "Отключить оптимизацию";
                    default: return "Disable optimization";
                }
            case "required_permissions_toast":
                switch (lang) {
                    case "it": return "Permessi necessari per il funzionamento";
                    case "de": return "Für den Betrieb sind Berechtigungen erforderlich";
                    case "fr": return "Autorisations requises pour le fonctionnement";
                    case "es": return "Se requieren permisos para el funcionamiento";
                    case "ru": return "Для работы требуются разрешения";
                    default: return "Required permissions are needed for proper operation";
                }
            case "connection_refused_title":
                switch (lang) {
                    case "it": return "Connessione KIBERSCOPE rifiutata";
                    case "de": return "KIBERSCOPE-Verbindung abgelehnt";
                    case "fr": return "Connexion KIBERSCOPE refusée";
                    case "es": return "Conexión KIBERSCOPE rechazada";
                    case "ru": return "Подключение KIBERSCOPE отклонено";
                    default: return "KIBERSCOPE connection refused";
                }
            case "connection_refused_msg":
                switch (lang) {
                    case "it": return "Connessione rifiutata. Verifica che il dispositivo non sia già ingaggiato da un altro mobile.";
                    case "de": return "Verbindung abgelehnt. Prüfen Sie, ob das Gerät bereits von einem anderen Mobilgerät gebunden ist.";
                    case "fr": return "Connexion refusée. Vérifiez que l'appareil n'est pas déjà engagé par un autre mobile.";
                    case "es": return "Conexión rechazada. Comprueba que el dispositivo no esté ya enganchado por otro móvil.";
                    case "ru": return "Подключение отклонено. Проверьте, что устройство уже занято другим мобильным устройством.";
                    default: return "Connection refused. Check whether the device is already engaged by another mobile.";
                }
            case "retry":
                switch (lang) {
                    case "it": return "Riprova";
                    case "de": return "Erneut versuchen";
                    case "fr": return "Réessayer";
                    case "es": return "Reintentar";
                    case "ru": return "Повторить";
                    default: return "Retry";
                }
            case "too_many_title":
                switch (lang) {
                    case "it": return "Troppi tentativi di connessione falliti";
                    case "de": return "Zu viele fehlgeschlagene Verbindungsversuche";
                    case "fr": return "Trop de tentatives de connexion échouées";
                    case "es": return "Demasiados intentos de conexión fallidos";
                    case "ru": return "Слишком много неудачных попыток подключения";
                    default: return "Too many failed connection attempts";
                }
            case "too_many_msg":
                switch (lang) {
                    case "it": return "La connessione wireless a un dispositivo Kiber già ingaggiato è stata tentata troppe volte. I tentativi automatici sono ora disabilitati per questa sessione. Riavvia l'app se vuoi riprovare e assicurati che il dispositivo non sia già ingaggiato da un altro mobile.";
                    case "de": return "Die drahtlose Verbindung zu einem bereits gebundenen Kiber-Gerät wurde zu oft versucht. Automatische Wiederholungen sind für diese Sitzung nun deaktiviert. Starten Sie die App neu, wenn Sie es erneut versuchen möchten, und stellen Sie sicher, dass das Gerät nicht bereits von einem anderen Mobilgerät gebunden ist.";
                    case "fr": return "La connexion sans fil à un appareil Kiber déjà engagé a été tentée trop de fois. Les nouvelles tentatives automatiques sont désormais désactivées pour cette session. Redémarrez l'application si vous voulez réessayer et assurez-vous que l'appareil n'est pas déjà engagé par un autre mobile.";
                    case "es": return "La conexión inalámbrica a un dispositivo Kiber ya enganchado se intentó demasiadas veces. Los reintentos automáticos ahora están deshabilitados para esta sesión. Reinicia la aplicación si quieres volver a intentarlo y asegúrate de que el dispositivo no esté ya enganchado por otro móvil.";
                    case "ru": return "Беспроводное подключение к уже занятому устройству Kiber выполнялось слишком много раз. Автоматические повторные попытки отключены для этой сессии. Перезапустите приложение, если хотите попробовать снова, и убедитесь, что устройство не занято другим мобильным устройством.";
                    default: return "Wireless connection to an engaged Kiber device was attempted too many times. Automatic retries are now disabled for this session. Restart the app if you want to try again, and make sure the device is not already engaged by another mobile.";
                }
            case "ok_got_it":
                switch (lang) {
                    case "it": return "Ok, ho capito!";
                    case "de": return "OK, verstanden!";
                    case "fr": return "OK, compris !";
                    case "es": return "¡OK, entendido!";
                    case "ru": return "Ок, понятно!";
                    default: return "Ok, got it!";
                }
            case "wifi_off_msg":
                switch (lang) {
                    case "it": return "Wi-Fi spento. Attivalo per continuare.";
                    case "de": return "WLAN ist ausgeschaltet. Aktivieren Sie es, um fortzufahren.";
                    case "fr": return "Le Wi-Fi est désactivé. Activez-le pour continuer.";
                    case "es": return "El Wi-Fi está desactivado. Actívalo para continuar.";
                    case "ru": return "Wi-Fi отключен. Включите его, чтобы продолжить.";
                    default: return "Wi-Fi is off. Enable it to continue.";
                }
            case "bt_off_msg":
                switch (lang) {
                    case "it": return "Bluetooth spento. Attivalo per continuare.";
                    case "de": return "Bluetooth ist ausgeschaltet. Aktivieren Sie es, um fortzufahren.";
                    case "fr": return "Le Bluetooth est désactivé. Activez-le pour continuer.";
                    case "es": return "Bluetooth está desactivado. Actívalo para continuar.";
                    case "ru": return "Bluetooth отключен. Включите его, чтобы продолжить.";
                    default: return "Bluetooth is off. Enable it to continue.";
                }
            case "open_settings":
                switch (lang) {
                    case "it": return "Apri impostazioni";
                    case "de": return "Einstellungen öffnen";
                    case "fr": return "Ouvrir les paramètres";
                    case "es": return "Abrir ajustes";
                    case "ru": return "Открыть настройки";
                    default: return "Open settings";
                }
            default:
                return key;
        }
    }

    private static boolean hasRuntimePermissions(@NonNull Context context, boolean includeBackgroundLocation) {
        boolean hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!hasLocation) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) return false;
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (includeBackgroundLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return true;
    }

    private static boolean hasBatteryOptimizationExemption(@NonNull Context context) {
        PowerManager pm = context.getSystemService(PowerManager.class);
        return pm == null || pm.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    private static void launchPermissionProxy(
            @NonNull Activity activity,
            @NonNull String pendingAction,
            @Nullable String contentText,
            boolean connected
    ) {
        Intent intent = new Intent(activity, KiberPermissionProxyActivity.class);
        intent.putExtra(EXTRA_PENDING_ACTION, pendingAction);
        if (contentText != null) {
            intent.putExtra(EXTRA_CONTENT_TEXT, contentText);
        }
        intent.putExtra(EXTRA_CONNECTED, connected);
        activity.startActivity(intent);
    }

    private static String buildAssociatedNotificationText(@NonNull Context context) {
        String suffix = getTargetSerialSuffix(context);
        if (suffix == null) {
            return context.getString(R.string.foreground_service_text_autoconnect_waiting);
        }
        return "Associated with <" + suffix + ">";
    }

    private static String buildSearchingNotificationText(@NonNull Context context) {
        String suffix = getTargetSerialSuffix(context);
        if (suffix == null) {
            return context.getString(R.string.foreground_service_text_searching);
        }
        return "Searching <" + suffix + ">";
    }

    private static String buildConnectingNotificationText(@NonNull Context context) {
        String suffix = getTargetSerialSuffix(context);
        if (suffix == null) {
            return context.getString(R.string.foreground_service_text_connecting);
        }
        return "Connecting to <" + suffix + ">";
    }

    private static String buildConnectedNotificationText(@NonNull Context context) {
        String suffix = getTargetSerialSuffix(context);
        if (suffix == null) {
            return context.getString(R.string.foreground_service_text_connected);
        }
        return "<" + suffix + "> connected";
    }

    @Nullable
    private static String getTargetSerialSuffix(@NonNull Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String suffix = prefs.getString(PREF_SSID_SUFFIX, "");
        if (suffix == null || suffix.isEmpty()) {
            return null;
        }
        suffix = suffix.toUpperCase(Locale.ROOT).trim();
        if (!suffix.matches("^[A-Z0-9]{" + SSID_SUFFIX_LENGTH + "}$")) {
            return null;
        }
        return suffix;
    }

    private static String normalizeTargetSerial(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.toUpperCase(Locale.ROOT).trim();
        if (!normalized.startsWith(SSID_PREFIX)) {
            if (normalized.matches("^[A-Z0-9]{" + SSID_SUFFIX_LENGTH + "}$")) {
                return normalized;
            }
            return null;
        }
        String suffix = normalized.substring(SSID_PREFIX.length()).trim();
        if (suffix.matches("^[A-Z0-9]{" + SSID_SUFFIX_LENGTH + "}$")) {
            return suffix;
        }
        return null;
    }

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                Log.d(TAG, "Service wifi state changed: " + wifiState);
                if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                    attemptAutoConnectIfEnabled();
                } else if (wifiState == WifiManager.WIFI_STATE_DISABLED) {
                    if (connectedState) {
                        BeepHelper.playBeep(KiberWifiServiceManager.this, false);
                    }
                    connectedState = false;
                    updateNotification(getString(R.string.foreground_service_text_wifi_disabled), false);
                    emitStatus(KiberStatus.IDLING, "KIBER_WIFI_OFF");
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!acquireInterAppLock()) {
            Log.i(TAG, "Service onCreate aborted: lock owned by another app");
            stopSelf();
            return;
        }
        serviceRunning = true;
        serviceCreatedAtMs = SystemClock.elapsedRealtime();
        Log.i(TAG, "Service onCreate build=" + getAppVersionTag());
        connectivityManager = getSystemService(ConnectivityManager.class);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
        
        createNotificationChannel();
        registerWifiReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!holdsInterAppLock) {
            Log.i(TAG, "onStartCommand ignored: lock not owned");
            stopSelf();
            return START_NOT_STICKY;
        }
        Log.d(TAG, "Service onStartCommand action=" + (intent != null ? intent.getAction() : "null")
                + " build=" + getAppVersionTag());
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "ACTION_STOP received: connectedState=" + connectedState
                    + ", isBleScanning=" + isBleScanning
                    + ", connectionInProgress=" + connectionInProgress);
            if (connectedState) {
                BeepHelper.playBeep(this, false);
            }
            stopBleScan();
            releaseBleScanWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            Log.i(TAG, "ACTION_STOP handled: stopSelf issued");
            return START_NOT_STICKY;
        }
        if (ACTION_DISABLE_CONNECT.equals(action)) {
            handleDisableConnectAction();
        }
        if (ACTION_ENABLE_CONNECT.equals(action)) {
            getPrefs().edit().putBoolean(PREF_CONNECT_ENABLED, true).apply();
            // Force a fresh immediate cycle to minimize latency after manual Connect.
            cancelRetry();
            stopBleScan();
        }
        if (ACTION_CHANGE_DEVICE.equals(action)) {
            String suffix = intent != null ? intent.getStringExtra(EXTRA_DEVICE_SERIAL) : null;
            if (suffix != null) {
                handleChangeDeviceAction(suffix);
            }
        }

        String contentText = getString(R.string.foreground_service_text_default);
        if (intent != null && intent.hasExtra(EXTRA_CONTENT_TEXT)) {
            contentText = intent.getStringExtra(EXTRA_CONTENT_TEXT);
        }
        if (intent != null && intent.hasExtra(EXTRA_CONNECTED)) {
            connectedState = intent.getBooleanExtra(EXTRA_CONNECTED, connectedState);
        }
        boolean actuallyConnectedNow = isConnectedToKiberscopeApNow();
        connectedState = actuallyConnectedNow;
        if (connectedState) {
            contentText = buildConnectedNotificationText(this);
        }

        startForeground(NOTIFICATION_ID, buildNotification(contentText, connectedState));
        if (!connectedState) {
            cancelRetry();
            attemptAutoConnectIfEnabled();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service onDestroy: serviceRunning->false");
        serviceRunning = false;
        cancelPendingConnectedConfirmation();
        cancelRetry();
        stopBleScan();
        releaseBleScanWakeLock();
        clearNetworkCallback();
        unregisterWifiReceiver();
        releaseInterAppLock();
        super.onDestroy();
    }

    @Override
    public @Nullable IBinder onBind(@NonNull Intent intent) {
        return null;
    }

    private Notification buildNotification(String contentText, boolean connected) {
        Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (notificationIntent == null) {
            notificationIntent = new Intent();
        }
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.foreground_service_title))
                .setContentText(contentText)
                .setSmallIcon(connected ? R.drawable.ic_notification_k_wifi : R.drawable.ic_notification_k)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
    }

    private void updateNotification(String contentText, boolean connected) {
        emitStatus(connected ? KiberStatus.CONNECTED : KiberStatus.IDLING, contentText);
        if (contentText != null
                && contentText.equals(lastNotificationText)
                && connected == lastNotificationConnected) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(contentText, connected));
            lastNotificationText = contentText;
            lastNotificationConnected = connected;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(R.string.foreground_service_channel_description));
        channel.setSound(null, (AudioAttributes) null);
        channel.enableVibration(false);
        channel.enableLights(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void registerWifiReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        ContextCompat.registerReceiver(this, wifiReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void unregisterWifiReceiver() {
        if (!receiverRegistered) {
            return;
        }
        unregisterReceiver(wifiReceiver);
        receiverRegistered = false;
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private boolean isAutoConnectEnabled() {
        return getPrefs().getBoolean(PREF_AUTOCONNECT_ENABLED, false);
    }

    private boolean isConnectEnabled() {
        return getPrefs().getBoolean(PREF_CONNECT_ENABLED, false);
    }

    private boolean hasRequiredPermissions() {
        boolean location = hasLocationPermission();
        boolean bluetooth = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetooth = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return location && bluetooth;
    }

    private boolean hasLocationPermission() {
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) {
            return false;
        }
        // Manual connect flow can run with foreground location; passive background reliability still
        // expects background location permission.
        if (isConnectEnabled()) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isWifiDisabled() {
        return wifiManager == null || !wifiManager.isWifiEnabled();
    }

    private boolean isBluetoothDisabled() {
        return bluetoothAdapter == null || !bluetoothAdapter.isEnabled();
    }

    private boolean ensureBluetoothReadyAndPromptIfNeeded() {
        if (isBluetoothDisabled()) {
            emitStatus(KiberStatus.IDLING, "KIBER_BT_OFF");
            maybeShowRadioDialog(RADIO_TYPE_BT);
            return false;
        }
        return true;
    }

    private boolean ensureWifiReadyAndPromptIfNeeded() {
        if (isWifiDisabled()) {
            updateNotification(getString(R.string.foreground_service_text_wifi_disabled), false);
            emitStatus(KiberStatus.IDLING, "KIBER_WIFI_OFF");
            maybeShowRadioDialog(RADIO_TYPE_WIFI);
            return false;
        }
        return true;
    }

    private void maybeShowRadioDialog(@NonNull String radioType) {
        if (!hostAppInForeground) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (radioType.equals(lastRadioDialogType) && (now - lastRadioDialogAtMs) < 3_000L) {
            return;
        }
        lastRadioDialogType = radioType;
        lastRadioDialogAtMs = now;
        Intent intent = new Intent(getApplicationContext(), KiberRadioStateDialogActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(EXTRA_RADIO_TYPE, radioType);
        startActivity(intent);
    }

    private String getCurrentConnectedSsidOrNull() {
        try {
            if (wifiManager == null || wifiManager.getConnectionInfo() == null) {
                return null;
            }
            @SuppressLint("MissingPermission")
            String currentSsidRaw = wifiManager.getConnectionInfo().getSSID();
            if (currentSsidRaw == null) {
                return null;
            }
            return currentSsidRaw.replace("\"", "");
        } catch (Exception e) {
            Log.w(TAG, "Service failed to read current SSID for state sync", e);
            return null;
        }
    }

    private boolean isConnectedToKiberscopeApNow() {
        String targetSsid = getTargetSsidOrNull();
        String currentSsid = getCurrentConnectedSsidOrNull();
        if (targetSsid == null || currentSsid == null) {
            return false;
        }
        return targetSsid.equals(currentSsid);
    }

    private void cancelPendingConnectedConfirmation() {
        if (pendingConnectedConfirmRunnable != null) {
            handler.removeCallbacks(pendingConnectedConfirmRunnable);
            pendingConnectedConfirmRunnable = null;
        }
    }

    private String getTargetSsidOrNull() {
        String suffix = getPrefs().getString(PREF_SSID_SUFFIX, "");
        if (suffix == null || suffix.isEmpty()) {
            return null;
        }
        suffix = suffix.toUpperCase(Locale.ROOT);
        if (suffix.length() != SSID_SUFFIX_LENGTH) {
            return null;
        }
        if (!suffix.matches("^[A-Z0-9]{" + SSID_SUFFIX_LENGTH + "}$")) {
            return null;
        }
        return SSID_PREFIX + suffix;
    }

    private String getTargetBleNameOrNull() {
        String suffix = getPrefs().getString(PREF_SSID_SUFFIX, "");
        if (suffix == null || suffix.isEmpty()) {
            return null;
        }
        return BLE_PREFIX + suffix.toUpperCase(Locale.ROOT);
    }

    private void attemptAutoConnectIfEnabled() {
        if (retryBlockedForSession) {
            Log.i(TAG, "Service connect attempt blocked: retry limit reached for this app session");
            return;
        }
        if (connectedState || connectionInProgress) {
            return;
        }
        if (connectionRefusedDialogActive) {
            Log.d(TAG, "Service connect attempt suspended: refusal dialog active");
            scheduleRetry();
            return;
        }
        Log.d(TAG, "Service autoconnect attempt started");

        if (!ensureBluetoothReadyAndPromptIfNeeded()) {
            scheduleRetry();
            return;
        }
        
        if (!hasRequiredPermissions()) {
            updateNotification(getString(R.string.foreground_service_text_background_location_required), false);
            scheduleRetry();
            return;
        }
        
        if (getTargetSsidOrNull() == null) {
            updateNotification(getString(R.string.foreground_service_text_waiting_suffix), false);
            return;
        }

        if (isConnectEnabled() && isTargetDevicePresentRecently()) {
            Log.d(TAG, "Service immediate connect: target already marked present");
            requestNetworkInBackground(getTargetSsidOrNull());
            return;
        }
        
        boolean shouldAnnounceSearching = !isTargetDevicePresentRecently();
        if (shouldAnnounceSearching) {
            updateNotification(buildSearchingNotificationText(this), false);
            emitStatus(KiberStatus.SCANNING, "KIBER_SCANNING");
        } else {
            updateNotification(buildAssociatedNotificationText(this), false);
            emitStatus(KiberStatus.MONITORING, "KIBER_MONITORING");
        }
        startBleScan();
    }

    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (isBleScanning || connectedState || connectionInProgress) {
            return;
        }
        if (handler.hasCallbacks(retryRunnable)) {
            // A scan/retry cycle is already pending: collapse concurrent triggers.
            return;
        }
        if (!ensureBluetoothReadyAndPromptIfNeeded()) {
            scheduleRetry();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < scanBackoffUntilMs) {
            long waitMs = scanBackoffUntilMs - now;
            Log.w(TAG, "Service scan in backoff window, waitMs=" + waitMs);
            if (!handler.hasCallbacks(retryRunnable)) {
                handler.postDelayed(retryRunnable, waitMs);
            }
            return;
        }
        long sinceLastStart = now - lastScanStartAttemptAtMs;
        if (sinceLastStart < MIN_SCAN_START_INTERVAL_MS) {
            long waitMs = MIN_SCAN_START_INTERVAL_MS - sinceLastStart;
            Log.w(TAG, "Service scan start throttled to avoid too-frequent registration, waitMs=" + waitMs);
            if (!handler.hasCallbacks(retryRunnable)) {
                handler.postDelayed(retryRunnable, waitMs);
            }
            return;
        }
        lastScanStartAttemptAtMs = now;
        clearBleScanTimeout();
        scanMatchedTargetName = false;
        
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Service: Bluetooth adapter not available");
            emitStatus(KiberStatus.IDLING, "KIBER_BT_OFF");
            scheduleRetry();
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Log.w(TAG, "Service: Cannot get BLE scanner, adapterState=" + bluetoothAdapter.getState());
            emitStatus(KiberStatus.IDLING, "KIBER_BT_OFF");
            scheduleRetry();
            return;
        }

        String targetBleName = getTargetBleNameOrNull();
        if (targetBleName == null) return;
        
        Log.d(TAG, "Service starting BLE scan for " + targetBleName);
        acquireBleScanWakeLock();

        bleScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                String deviceName = null;
                String recordName = null;
                try {
                    deviceName = result.getDevice().getName();
                    if (result.getScanRecord() != null) {
                        recordName = result.getScanRecord().getDeviceName();
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException getting device name", e);
                }

                boolean nameMatched = isTargetBleNameMatch(targetBleName, deviceName)
                        || isTargetBleNameMatch(targetBleName, recordName);
                boolean macMatched = hasAnyLearnedDeviceAddress(result);
                boolean hasMacGuard = !getLearnedDeviceAddresses().isEmpty();
                // Be robust to rotating/random BLE addresses: name match is sufficient,
                // learned MAC is a fast path/filter hint, not a hard blocker.
                boolean matched = nameMatched;
                if (matched) {
                    scanMatchedTargetName = true;
                    Log.d(TAG, "Service found target BLE device: deviceName=" + deviceName + ", recordName=" + recordName);
                    cacheDeviceAddressFromResult(result, "Service");
                    lastTargetSeenAtMs = SystemClock.elapsedRealtime();
                    targetDevicePresent = true;
                    targetPresentGlobal = true;
                    emitStatus(KiberStatus.IDLING, "KIBER_TARGET_PRESENT");
                    stopBleScan();
                    boolean connectEnabled = isConnectEnabled();
                    boolean autoConnectEnabled = isAutoConnectEnabled();
                    Log.d(TAG, "Service target found, connect flags: connectEnabled="
                            + connectEnabled + ", autoConnectEnabled=" + autoConnectEnabled);
                    if (connectEnabled || autoConnectEnabled) {
                        requestNetworkInBackground(getTargetSsidOrNull());
                    } else {
                        updateNotification(buildAssociatedNotificationText(KiberWifiServiceManager.this), false);
                        scheduleRetry();
                    }
                } else if (scanResultLogBudget > 0) {
                    scanResultLogBudget--;
                    Log.d(TAG, "Service BLE non-match: deviceName=" + deviceName
                            + ", recordName=" + recordName
                            + ", nameMatched=" + nameMatched
                            + ", hasMacGuard=" + hasMacGuard
                            + ", macMatched=" + macMatched
                            + ", rssi=" + result.getRssi());
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "Service BLE scan failed: " + errorCode);
                emitStatus(KiberStatus.ERROR, "BLE_SCAN_FAILED_" + errorCode);
                isBleScanning = false;
                releaseBleScanWakeLock();
                if (errorCode == 6) {
                    scanBackoffUntilMs = SystemClock.elapsedRealtime() + SCAN_FAILED_BACKOFF_MS;
                    Log.w(TAG, "Service scan failed TOO_FREQUENTLY: applying backoffMs=" + SCAN_FAILED_BACKOFF_MS);
                }
                scheduleRetry();
            }
        };

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        List<ScanFilter> scanFilters = buildBleScanFilters();
        scanUsedMacFilters = scanFilters != null && !scanFilters.isEmpty();
        Set<String> learnedMac = getLearnedDeviceAddresses();
        Log.d(TAG, "Service BLE scan config: learnedMacCount=" + learnedMac.size()
                + ", learnedMac=" + learnedMac);
        
        try {
            scanResultLogBudget = 12;
            bluetoothLeScanner.startScan(scanFilters, settings, bleScanCallback);
            isBleScanning = true;
            if (scanFilters == null || scanFilters.isEmpty()) {
                Log.d(TAG, "Service BLE unfiltered scan started for " + targetBleName);
            } else {
                Log.d(TAG, "Service BLE MAC-filtered scan started for " + targetBleName + ", filters=" + scanFilters.size());
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException starting BLE scan", e);
            releaseBleScanWakeLock();
            scheduleRetry();
        }
        
        // Keep a longer scan window to reduce gaps between detection opportunities.
        bleScanTimeoutRunnable = () -> {
            if (isBleScanning) {
                Log.d(TAG, "Service BLE scan timeout");
                stopBleScan();
                targetDevicePresent = isTargetDevicePresentRecently();
                targetPresentGlobal = targetDevicePresent;
                if (!targetDevicePresent) {
                    emitStatus(KiberStatus.IDLING, "KIBER_TARGET_ABSENT");
                }
                if (scanUsedMacFilters && !scanMatchedTargetName) {
                    Log.w(TAG, "Service scan timeout with stale MAC filters: clearing learned filters and retrying unfiltered");
                    clearLearnedDeviceAddresses();
                }
                scheduleRetry();
            }
        };
        handler.postDelayed(bleScanTimeoutRunnable, BLE_SCAN_WINDOW_MS);
    }

    @SuppressLint("MissingPermission")
    private void stopBleScan() {
        clearBleScanTimeout();
        if (!isBleScanning || bluetoothLeScanner == null || bleScanCallback == null) {
            releaseBleScanWakeLock();
            return;
        }
        Log.d(TAG, "Service stopping BLE scan");
        try {
            bluetoothLeScanner.stopScan(bleScanCallback);
        } catch (Exception e) {
            Log.w(TAG, "Service: Error stopping BLE scan", e);
        }
        isBleScanning = false;
        bleScanCallback = null;
        releaseBleScanWakeLock();
    }

    private void clearBleScanTimeout() {
        if (bleScanTimeoutRunnable != null) {
            handler.removeCallbacks(bleScanTimeoutRunnable);
            bleScanTimeoutRunnable = null;
        }
    }

    private List<ScanFilter> buildBleScanFilters() {
        List<ScanFilter> filters = new ArrayList<>();
        for (String mac : getLearnedDeviceAddresses()) {
            if (!BluetoothAdapter.checkBluetoothAddress(mac)) continue;
            filters.add(new ScanFilter.Builder()
                    .setDeviceAddress(mac)
                    .build());
        }
        if (filters.isEmpty()) {
            return null;
        }
        return filters;
    }

    private Set<String> getLearnedDeviceAddresses() {
        String raw = getPrefs().getString(PREF_LEARNED_DEVICE_ADDRESSES, "");
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            String trimmed = part.trim().toUpperCase(Locale.ROOT);
            if (BluetoothAdapter.checkBluetoothAddress(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private boolean hasAnyLearnedDeviceAddress(ScanResult result) {
        Set<String> learned = getLearnedDeviceAddresses();
        if (learned.isEmpty() || result.getDevice() == null) {
            return false;
        }
        String address = result.getDevice().getAddress();
        if (address == null) {
            return false;
        }
        return learned.contains(address.toUpperCase(Locale.ROOT));
    }

    private void clearLearnedDeviceAddresses() {
        getPrefs().edit().putString(PREF_LEARNED_DEVICE_ADDRESSES, "").apply();
    }

    private boolean isTargetBleNameMatch(String expectedName, String candidateName) {
        if (expectedName == null || expectedName.isEmpty() || candidateName == null || candidateName.isEmpty()) {
            return false;
        }
        String normalizedCandidate = candidateName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
        if (expectedName.equals(normalizedCandidate)) {
            return true;
        }
        // Some stacks append noisy suffix chars; accept prefix match on expected token.
        return normalizedCandidate.startsWith(expectedName);
    }

    private void cacheDeviceAddressFromResult(ScanResult result, String owner) {
        if (result.getDevice() == null) {
            return;
        }
        String address = result.getDevice().getAddress();
        if (address == null) {
            return;
        }
        address = address.toUpperCase(Locale.ROOT);
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            return;
        }
        Set<String> existing = getLearnedDeviceAddresses();
        if (!existing.add(address)) {
            return;
        }
        StringBuilder csv = new StringBuilder();
        for (String mac : existing) {
            if (csv.length() > 0) csv.append(',');
            csv.append(mac);
        }
        getPrefs().edit().putString(PREF_LEARNED_DEVICE_ADDRESSES, csv.toString()).apply();
        Log.d(TAG, owner + " learned BLE MAC filters: " + csv);
    }


    private void acquireBleScanWakeLock() {
        try {
            if (bleScanWakeLock == null) {
                PowerManager powerManager = getSystemService(PowerManager.class);
                if (powerManager == null) {
                    Log.w(TAG, "PowerManager unavailable, cannot acquire wake lock");
                    return;
                }
                bleScanWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
                bleScanWakeLock.setReferenceCounted(false);
            }
            if (!bleScanWakeLock.isHeld()) {
                bleScanWakeLock.acquire(BLE_SCAN_WINDOW_MS + 5_000L);
                Log.d(TAG, "Service wake lock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire wake lock", e);
        }
    }

    private void releaseBleScanWakeLock() {
        try {
            if (bleScanWakeLock != null && bleScanWakeLock.isHeld()) {
                bleScanWakeLock.release();
                Log.d(TAG, "Service wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release wake lock", e);
        }
    }

    private String getAppVersionTag() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            long versionCode = PackageInfoCompat.getLongVersionCode(info);
            return info.versionName + "(" + versionCode + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void requestNetworkInBackground(String targetSsid) {
        if (retryBlockedForSession) {
            Log.i(TAG, "Service requestNetwork skipped: retry limit reached for this app session");
            return;
        }
        if (connectionRefusedDialogActive) {
            Log.d(TAG, "Service requestNetwork skipped: refusal dialog active");
            scheduleRetry();
            return;
        }
        if (connectionInProgress || connectivityManager == null || targetSsid == null) {
            return;
        }
        if (!ensureWifiReadyAndPromptIfNeeded()) {
            scheduleRetry();
            return;
        }
        clearNetworkCallback();
        connectionInProgress = true;
        updateNotification(buildConnectingNotificationText(this), false);
        emitStatus(KiberStatus.CONNECTING, "KIBER_CONNECTING");
        
        Log.d(TAG, "Service requesting network for " + targetSsid);

        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(targetSsid)
                .setWpa2Passphrase(KIBERSCOPE_PASSPHRASE)
                .build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                super.onAvailable(network);
                cancelPendingConnectedConfirmation();
                String currentSsid = getCurrentConnectedSsidOrNull();
                if (currentSsid == null || !targetSsid.equals(currentSsid)) {
                    Log.w(TAG, "Service network available but SSID mismatch. expected="
                            + targetSsid + ", current=" + currentSsid);
                    connectedState = false;
                    connectionInProgress = false;
                    clearNetworkCallback();
                    scheduleRetry();
                    return;
                }
                pendingConnectedConfirmRunnable = () -> {
                    String confirmedSsid = getCurrentConnectedSsidOrNull();
                    if (confirmedSsid == null || !targetSsid.equals(confirmedSsid)) {
                        Log.d(TAG, "Service connected confirmation skipped: SSID changed before stabilization. expected="
                                + targetSsid + ", current=" + confirmedSsid);
                        connectedState = false;
                        connectionInProgress = false;
                        scheduleRetry();
                        return;
                    }
                    getPrefs().edit().putBoolean(PREF_CONNECTION_REFUSED_PENDING, false).apply();
                    manualRetryCountForSession = 0;
                    retryBlockedForSession = false;
                    connectedState = true;
                    connectionInProgress = false;
                    cancelRetry();
                    BeepHelper.playBeep(KiberWifiServiceManager.this, true);
                    Log.d(TAG, "Service network available and stabilized: " + network);
                    updateNotification(buildConnectedNotificationText(KiberWifiServiceManager.this), true);
                    emitStatus(KiberStatus.CONNECTED, "KIBER_CONNECTED");
                    pendingConnectedConfirmRunnable = null;
                };
                handler.postDelayed(pendingConnectedConfirmRunnable, CONNECTED_STABILIZATION_MS);
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                cancelPendingConnectedConfirmation();
                if (connectedState) {
                    BeepHelper.playBeep(KiberWifiServiceManager.this, false);
                }
                connectedState = false;
                connectionInProgress = false;
                Log.d(TAG, "Service network lost: " + network);
                clearNetworkCallback();
                updateNotification(buildAssociatedNotificationText(KiberWifiServiceManager.this), false);
                emitStatus(KiberStatus.DISCONNECTED, "KIBER_DISCONNECTED");
                scheduleRetry();
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                cancelPendingConnectedConfirmation();
                connectedState = false;
                connectionInProgress = false;
                Log.d(TAG, "Service network unavailable");
                getPrefs().edit().putBoolean(PREF_CONNECTION_REFUSED_PENDING, true).apply();
                targetPresentGlobal = false;
                emitStatus(KiberStatus.ERROR, "KIBER_CONNECTION_REFUSED");
                if (hostAppInForeground) {
                    maybeShowPendingConnectionRefusedDialog(getApplicationContext());
                }
                ensureWifiReadyAndPromptIfNeeded();
                clearNetworkCallback();
                disableAutoConnectAfterFailure();
            }
        };

        connectivityManager.requestNetwork(request, networkCallback);
    }

    private void disableAutoConnectAfterFailure() {
        getPrefs().edit()
                .putBoolean(PREF_AUTOCONNECT_ENABLED, false)
                .putBoolean(PREF_CONNECT_ENABLED, false)
                .apply();
        cancelRetry();
        stopBleScan();
        releaseBleScanWakeLock();
        updateNotification(getString(R.string.foreground_service_text_connection_refused), false);
        if (!retryBlockedForSession) {
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (connectedState || connectionInProgress) {
            return;
        }
        if (handler.hasCallbacks(retryRunnable)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long sinceCreate = now - serviceCreatedAtMs;
        boolean canUseFastStartRetry = sinceCreate <= FAST_START_WINDOW_MS
                && !isTargetDevicePresentRecently();
        long scheduledDelay = canUseFastStartRetry
                ? FAST_START_RETRY_DELAY_MS
                : AUTOCONNECT_RETRY_DELAY_MS;
        handler.postDelayed(retryRunnable, scheduledDelay);
        Log.d(TAG, "Service scheduled retry in " + scheduledDelay + " ms");
    }

    private void cancelRetry() {
        handler.removeCallbacks(retryRunnable);
    }

    private void clearNetworkCallback() {
        if (networkCallback == null || connectivityManager == null) {
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Service network callback already unregistered");
        }
        networkCallback = null;
        if (!connectedState) {
            if (!isTargetDevicePresentRecently()) {
                lastTargetSeenAtMs = 0L;
                targetDevicePresent = false;
                targetPresentGlobal = false;
            }
        }
    }

    private void handleDisableConnectAction() {
        clearNetworkCallback();
        connectionInProgress = false;
        if (connectedState) {
            BeepHelper.playBeep(this, false);
        }
        connectedState = false;
        disconnectFromTargetApIfNeeded();
        updateNotification(buildAssociatedNotificationText(this), false);
        emitStatus(KiberStatus.DISCONNECTED, "KIBER_DISCONNECTED");
        attemptAutoConnectIfEnabled();
    }

    private void handleChangeDeviceAction(@NonNull String newSuffix) {
        String normalizedSuffix = normalizeTargetSerial(newSuffix);
        if (normalizedSuffix == null) {
            Log.w(TAG, "changeDeviceSerial ignored: invalid serial " + newSuffix);
            return;
        }
        String currentSuffix = getPrefs().getString(PREF_SSID_SUFFIX, "");
        if (normalizedSuffix.equalsIgnoreCase(currentSuffix == null ? "" : currentSuffix)) {
            Log.d(TAG, "changeDeviceSerial no-op: same serial " + normalizedSuffix);
            return;
        }

        boolean keepConnectEnabled = isConnectEnabled();
        boolean keepAutoConnectEnabled = isAutoConnectEnabled();
        Log.d(TAG, "changeDeviceSerial: " + currentSuffix + " -> " + normalizedSuffix
                + ", keepConnectEnabled=" + keepConnectEnabled
                + ", keepAutoConnectEnabled=" + keepAutoConnectEnabled);

        cancelRetry();
        clearNetworkCallback();
        connectionInProgress = false;
        if (connectedState) {
            BeepHelper.playBeep(this, false);
        }
        connectedState = false;
        disconnectFromTargetApIfNeeded();
        stopBleScan();
        releaseBleScanWakeLock();

        getPrefs().edit()
                .putString(PREF_SSID_SUFFIX, normalizedSuffix)
                .putString(PREF_LEARNED_DEVICE_ADDRESSES, "")
                .putBoolean(PREF_CONNECT_ENABLED, keepConnectEnabled)
                .putBoolean(PREF_AUTOCONNECT_ENABLED, keepAutoConnectEnabled)
                .apply();

        lastTargetSeenAtMs = 0L;
        targetDevicePresent = false;
        targetPresentGlobal = false;

        updateNotification(buildAssociatedNotificationText(this), false);
        emitStatus(KiberStatus.DISCONNECTED, "KIBER_DEVICE_CHANGED");
        attemptAutoConnectIfEnabled();
    }

    private boolean isTargetDevicePresentRecently() {
        if (lastTargetSeenAtMs <= 0L) {
            return false;
        }
        return (SystemClock.elapsedRealtime() - lastTargetSeenAtMs) <= DEVICE_PRESENT_TTL_MS;
    }

    private void disconnectFromTargetApIfNeeded() {
        try {
            if (wifiManager == null || wifiManager.getConnectionInfo() == null) {
                return;
            }
            @SuppressLint("MissingPermission")
            String currentSsidRaw = wifiManager.getConnectionInfo().getSSID();
            if (currentSsidRaw == null) {
                return;
            }
            String currentSsid = currentSsidRaw.replace("\"", "");
            if (!currentSsid.startsWith(SSID_PREFIX)) {
                return;
            }
            wifiManager.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Service failed to disconnect target AP", e);
        }
    }

    private void emitStatus(@NonNull KiberStatus status, @NonNull String message) {
        currentStatus = status;
        KiberEventListener listener = eventListener;
        if (listener != null) {
            try {
                listener.onKiberEvent(status, message);
            } catch (Exception e) {
                Log.w(TAG, "Listener callback failed", e);
            }
        }
    }

    private static void emitStatusStatic(@NonNull KiberStatus status, @NonNull String message) {
        currentStatus = status;
        KiberEventListener listener = eventListener;
        if (listener != null) {
            try {
                listener.onKiberEvent(status, message);
            } catch (Exception e) {
                Log.w(TAG, "Listener callback failed", e);
            }
        }
    }
    public static class KiberPermissionProxyActivity extends AppCompatActivity {
    private static final int REQ_CORE = 1001;
    private static final int REQ_BACKGROUND_LOCATION = 1002;

    private String pendingAction = KiberWifiServiceManager.PENDING_ACTION_NONE;
    private String pendingContentText = null;
    private boolean pendingConnected = false;
    private boolean waitingBackgroundSettings = false;
    private boolean waitingBatterySettings = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (intent != null) {
            pendingAction = intent.getStringExtra(KiberWifiServiceManager.EXTRA_PENDING_ACTION);
            if (pendingAction == null) pendingAction = KiberWifiServiceManager.PENDING_ACTION_NONE;
            pendingContentText = intent.getStringExtra("extra_content_text");
            pendingConnected = intent.getBooleanExtra("extra_connected", false);
        }
        requestMissingPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingBackgroundSettings) {
            waitingBackgroundSettings = false;
            if (hasBackgroundLocationPermission()) {
                maybeRequestBatteryOptimizationExemption();
            } else {
                Toast.makeText(this, R.string.background_location_permission_required, Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }
        if (waitingBatterySettings) {
            waitingBatterySettings = false;
            completePendingAction();
        }
    }

    private void requestMissingPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        if (!hasLocationPermission()) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQ_CORE);
            return;
        }
        requestBackgroundLocationIfNeeded();
    }

    private void requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            maybeRequestBatteryOptimizationExemption();
            return;
        }
        if (hasBackgroundLocationPermission()) {
            maybeRequestBatteryOptimizationExemption();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(tr("bg_loc_title"))
                    .setMessage(tr("bg_loc_msg"))
                    .setPositiveButton(tr("bg_loc_open"), (d, which) -> {
                        waitingBackgroundSettings = true;
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.fromParts("package", getPackageName(), null));
                        startActivity(intent);
                    })
                    .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
                    .setOnCancelListener(d -> finish())
                    .create();
            dialog.show();
            centerDialogMessage(dialog);
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                REQ_BACKGROUND_LOCATION);
    }

    private boolean hasBackgroundLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void maybeRequestBatteryOptimizationExemption() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            completePendingAction();
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(tr("battery_title"))
                .setMessage(tr("battery_msg"))
                .setPositiveButton(tr("battery_open"), (d, which) -> {
                    waitingBatterySettings = true;
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    try {
                        startActivity(intent);
                    } catch (Exception e) {
                        waitingBatterySettings = false;
                        completePendingAction();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> completePendingAction())
                .setOnCancelListener(d -> completePendingAction())
                .create();
        dialog.show();
        centerDialogMessage(dialog);
    }

    private boolean hasLocationPermission() {
        boolean fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CORE) {
            if (!allGranted(grantResults)) {
                Toast.makeText(this, tr("required_permissions_toast"), Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            requestBackgroundLocationIfNeeded();
            return;
        }
        if (requestCode == REQ_BACKGROUND_LOCATION) {
            if (!allGranted(grantResults)) {
                Toast.makeText(this, R.string.background_location_permission_required, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            maybeRequestBatteryOptimizationExemption();
        }
    }

    private boolean allGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) return false;
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void completePendingAction() {
        if (KiberWifiServiceManager.PENDING_ACTION_START.equals(pendingAction)) {
            String text = pendingContentText != null
                    ? pendingContentText
                    : buildAssociatedNotificationText(this);
            KiberWifiServiceManager.start(getApplicationContext(), text, pendingConnected);
        } else if (KiberWifiServiceManager.PENDING_ACTION_ENABLE_CONNECT.equals(pendingAction)) {
            KiberWifiServiceManager.enableConnect(getApplicationContext());
        }
        finish();
    }
    }

    public static class KiberConnectionRefusedDialogActivity extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            connectionRefusedDialogActive = true;
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(tr("connection_refused_title"))
                    .setMessage(tr("connection_refused_msg"))
                    .setPositiveButton(tr("retry"), (d, which) -> {
                        connectionRefusedDialogActive = false;
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(PREF_CONNECTION_REFUSED_PENDING, false)
                                .apply();
                        manualRetryCountForSession++;
                        KiberWifiServiceManager.enableConnect(getApplicationContext());
                        finish();
                    })
                    .setOnCancelListener(d -> {
                        connectionRefusedDialogActive = false;
                        finish();
                    })
                    .create();
            dialog.show();
            centerDialogMessage(dialog);
        }

        @Override
        protected void onDestroy() {
            connectionRefusedDialogActive = false;
            super.onDestroy();
        }
    }

    public static class KiberTooManyRetriesDialogActivity extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            connectionRefusedDialogActive = true;
            retryBlockedForSession = true;
            suspended = true;
            // Stop foreground service immediately when retry limit is reached,
            // so the persistent notification icon disappears at once.
            KiberWifiServiceManager.stop(getApplicationContext());
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(tr("too_many_title"))
                    .setMessage(tr("too_many_msg"))
                    .setPositiveButton(tr("ok_got_it"), (d, which) -> {
                        connectionRefusedDialogActive = false;
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putBoolean(PREF_CONNECTION_REFUSED_PENDING, false)
                                .putBoolean(PREF_AUTOCONNECT_ENABLED, false)
                                .putBoolean(PREF_CONNECT_ENABLED, false)
                                .apply();
                        KiberWifiServiceManager.stop(getApplicationContext());
                        finish();
                    })
                    .setOnCancelListener(d -> {
                        connectionRefusedDialogActive = false;
                        finish();
                    })
                    .create();
            dialog.show();
            centerDialogMessage(dialog);
        }

        @Override
        protected void onDestroy() {
            connectionRefusedDialogActive = false;
            super.onDestroy();
        }
    }

    public static class KiberRadioStateDialogActivity extends AppCompatActivity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            String radioType = getIntent() != null ? getIntent().getStringExtra(EXTRA_RADIO_TYPE) : null;
            boolean wifi = RADIO_TYPE_WIFI.equals(radioType);
            int title = wifi ? R.string.status_warning_wifi_off : R.string.status_warning_bluetooth_off;
            String message = wifi
                    ? tr("wifi_off_msg")
                    : tr("bt_off_msg");
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(tr("open_settings"), (d, which) -> {
                        Intent intent = wifi
                                ? new Intent(Settings.ACTION_WIFI_SETTINGS)
                                : new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        startActivity(intent);
                        finish();
                    })
                    .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
                    .setOnCancelListener(d -> finish())
                    .create();
            dialog.show();
            centerDialogMessage(dialog);
        }
    }

    static final class BeepHelper {
        private static final String TAG = "BeepHelper";
        private static ToneGenerator toneGen;
        private static int toneStream = -1;
        private static long lastBeepTimeMs = 0;
        private static Boolean lastBeepState = null;

        static synchronized void playBeep(Context context, boolean connected) {
            long now = SystemClock.elapsedRealtime();
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
}



