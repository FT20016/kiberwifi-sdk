package com.example.directwifi2;

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
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import android.media.AudioAttributes;

public class KiberWifiServiceManager extends Service {
    public enum KiberStatus {
        IDLING,
        SCANNING,
        MONITORING,
        CONNECTED,
        DISCONNECTED,
        ERROR
    }

    public interface KiberEventListener {
        void onKiberEvent(@NonNull KiberStatus status, @NonNull String message);
    }

    public static final String ACTION_START = "com.example.directwifi2.action.START";
    public static final String ACTION_STOP = "com.example.directwifi2.action.STOP";
    public static final String ACTION_ENABLE_CONNECT = "com.example.directwifi2.action.ENABLE_CONNECT";
    public static final String ACTION_DISABLE_CONNECT = "com.example.directwifi2.action.DISABLE_CONNECT";
    private static final String EXTRA_CONTENT_TEXT = "extra_content_text";
    private static final String EXTRA_CONNECTED = "extra_connected";

    private static final String TAG = "KiberWifiServiceManager";
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
    
    private static final long AUTOCONNECT_RETRY_DELAY_MS = 3_000L;
    private static final long BLE_SCAN_WINDOW_MS = 5_000L;
    private static final long DEVICE_PRESENT_TTL_MS = 10_000L;
    private static final long MIN_SCAN_START_INTERVAL_MS = 4_000L;
    private static volatile KiberStatus currentStatus = KiberStatus.IDLING;
    private static volatile KiberEventListener eventListener;
    private static volatile boolean targetPresentGlobal = false;

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
    private String lastNotificationText = null;
    private boolean lastNotificationConnected = false;
    private final Runnable retryRunnable = this::attemptAutoConnectIfEnabled;

    public static void start(Context context, String contentText, boolean connected) {
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_CONTENT_TEXT, contentText);
        intent.putExtra(EXTRA_CONNECTED, connected);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void ensureRunning(Context context) {
        start(context, context.getString(R.string.foreground_service_text_autoconnect_waiting), false);
    }

    public static void start(@NonNull Activity activity, @NonNull String deviceName, boolean autoConnect) {
        String suffix = extractSuffixFromDeviceName(deviceName);
        if (suffix == null) {
            throw new IllegalArgumentException("deviceName must be in form KIBERSCOPE-XXXXX");
        }
        SharedPreferences prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(PREF_SSID_SUFFIX, suffix)
                .putBoolean(PREF_AUTOCONNECT_ENABLED, autoConnect)
                .apply();
        start(activity.getApplicationContext(), activity.getString(R.string.foreground_service_text_autoconnect_waiting), false);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    public static void enableConnect(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CONNECT_ENABLED, true)
                .apply();
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_ENABLE_CONNECT);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void disableConnect(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CONNECT_ENABLED, false)
                .apply();
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_DISABLE_CONNECT);
        context.startService(intent);
    }

    public static void clearLearnedBleFilters(@NonNull Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LEARNED_DEVICE_ADDRESSES, "")
                .apply();
    }

    public static void setListener(@Nullable KiberEventListener listener) {
        eventListener = listener;
    }

    @NonNull
    public static KiberStatus getStatus() {
        return currentStatus;
    }

    public static boolean isTargetPresent() {
        return targetPresentGlobal;
    }

    private static String extractSuffixFromDeviceName(String deviceName) {
        if (deviceName == null) {
            return null;
        }
        String normalized = deviceName.toUpperCase(Locale.ROOT);
        if (!normalized.startsWith(SSID_PREFIX)) {
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
        Log.d(TAG, "Service onStartCommand action=" + (intent != null ? intent.getAction() : "null")
                + " build=" + getAppVersionTag());
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            if (connectedState) {
                BeepHelper.playBeep(this, false);
            }
            stopBleScan();
            releaseBleScanWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
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
            contentText = getString(R.string.foreground_service_text_connected);
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
        cancelRetry();
        stopBleScan();
        releaseBleScanWakeLock();
        clearNetworkCallback();
        unregisterWifiReceiver();
        super.onDestroy();
    }

    @Override
    public @Nullable IBinder onBind(@NonNull Intent intent) {
        return null;
    }

    private Notification buildNotification(String contentText, boolean connected) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
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

    private boolean isConnectedToKiberscopeApNow() {
        try {
            if (wifiManager == null || wifiManager.getConnectionInfo() == null) {
                return false;
            }
            @SuppressLint("MissingPermission")
            String currentSsidRaw = wifiManager.getConnectionInfo().getSSID();
            if (currentSsidRaw == null) {
                return false;
            }
            String currentSsid = currentSsidRaw.replace("\"", "");
            return currentSsid.startsWith(SSID_PREFIX);
        } catch (Exception e) {
            Log.w(TAG, "Service failed to read current SSID for state sync", e);
            return false;
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
        if (connectedState || connectionInProgress) {
            return;
        }
        Log.d(TAG, "Service autoconnect attempt started");

        if (isWifiDisabled()) {
            updateNotification(getString(R.string.foreground_service_text_wifi_disabled), false);
            emitStatus(KiberStatus.IDLING, "KIBER_WIFI_OFF");
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
            updateNotification(getString(R.string.foreground_service_text_searching), false);
            emitStatus(KiberStatus.SCANNING, "KIBER_SCANNING");
        } else {
            updateNotification(getString(R.string.foreground_service_text_autoconnect_waiting), false);
            emitStatus(KiberStatus.MONITORING, "KIBER_MONITORING");
        }
        startBleScan();
    }

    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (isBleScanning || connectedState || connectionInProgress) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
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
                    if (isConnectEnabled() || isAutoConnectEnabled()) {
                        requestNetworkInBackground(getTargetSsidOrNull());
                    } else {
                        updateNotification(getString(R.string.foreground_service_text_autoconnect_waiting), false);
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
        if (connectionInProgress || connectivityManager == null || targetSsid == null) {
            return;
        }
        clearNetworkCallback();
        connectionInProgress = true;
        updateNotification(getString(R.string.foreground_service_text_connecting), false);
        
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
                getPrefs().edit().putBoolean(PREF_CONNECTION_REFUSED_PENDING, false).apply();
                connectedState = true;
                connectionInProgress = false;
                cancelRetry();
                
                BeepHelper.playBeep(KiberWifiServiceManager.this, true);
                
                Log.d(TAG, "Service network available: " + network);
                updateNotification(getString(R.string.foreground_service_text_connected), true);
                emitStatus(KiberStatus.CONNECTED, "KIBER_CONNECTED");
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                if (connectedState) {
                    BeepHelper.playBeep(KiberWifiServiceManager.this, false);
                }
                connectedState = false;
                connectionInProgress = false;
                Log.d(TAG, "Service network lost: " + network);
                clearNetworkCallback();
                updateNotification(getString(R.string.foreground_service_text_autoconnect_waiting), false);
                emitStatus(KiberStatus.DISCONNECTED, "KIBER_DISCONNECTED");
                scheduleRetry();
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                connectedState = false;
                connectionInProgress = false;
                Log.d(TAG, "Service network unavailable");
                getPrefs().edit().putBoolean(PREF_CONNECTION_REFUSED_PENDING, true).apply();
                targetPresentGlobal = false;
                emitStatus(KiberStatus.ERROR, "KIBER_CONNECTION_REFUSED");
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
        scheduleRetry();
    }

    private void scheduleRetry() {
        if (connectedState || connectionInProgress) {
            return;
        }
        if (handler.hasCallbacks(retryRunnable)) {
            return;
        }
        long scheduledDelay = AUTOCONNECT_RETRY_DELAY_MS;
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
        updateNotification(getString(R.string.foreground_service_text_autoconnect_waiting), false);
        emitStatus(KiberStatus.DISCONNECTED, "KIBER_DISCONNECTED");
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
}

