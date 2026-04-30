package com.example.directwifi2;

import android.Manifest;
import android.annotation.SuppressLint;
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

public class KiberWifiServiceManager extends Service {

    public static final String ACTION_START = "com.example.directwifi2.action.START";
    public static final String ACTION_STOP = "com.example.directwifi2.action.STOP";
    private static final String EXTRA_CONTENT_TEXT = "extra_content_text";
    private static final String EXTRA_CONNECTED = "extra_connected";

    private static final String TAG = "KiberWifiServiceManager";
    private static final String WAKELOCK_TAG = "DirectWifi2:BleScanWakeLock";
    private static final String CHANNEL_ID = "directwifi2_keepalive_channel_v2";
    private static final int NOTIFICATION_ID = 1001;
    
    private static final String SSID_PREFIX = "KIBERSCOPE-";
    private static final String BLE_PREFIX = "KS-";
    private static final int SSID_SUFFIX_LENGTH = 5;
    
    private static final String PREFS_NAME = "directwifi2_prefs";
    private static final String PREF_SSID_SUFFIX = "pref_ssid_suffix";
    private static final String PREF_AUTOCONNECT_ENABLED = "pref_autoconnect_enabled";
    private static final String PREF_LEARNED_DEVICE_ADDRESSES = "pref_learned_device_addresses";
    private static final String PREF_CONNECTION_REFUSED_PENDING = "pref_connection_refused_pending";
    private static final String KIBERSCOPE_PASSPHRASE = "12345678";
    
    private static final long AUTOCONNECT_RETRY_DELAY_MS = 2_000L;
    private static final long BLE_SCAN_WINDOW_MS = 30_000L;

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
    private int scanResultLogBudget = 0;
    private PowerManager.WakeLock bleScanWakeLock;
    private Runnable bleScanTimeoutRunnable;
    
    private ConnectivityManager.NetworkCallback networkCallback;
    private long lastTargetSeenAtMs = 0L;
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

    public static void stop(Context context) {
        Intent intent = new Intent(context, KiberWifiServiceManager.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
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
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            if (connectedState) {
                BeepHelper.playBeep(this, false);
            }
            stopBleScan();
            releaseBleScanWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
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
        if (isAutoConnectEnabled() && !connectedState) {
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
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build();
    }

    private void updateNotification(String contentText, boolean connected) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(contentText, connected));
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.foreground_service_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription(getString(R.string.foreground_service_channel_description));

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
        // Background location is required on Android 10+ for reliable background BLE scanning on this app flow.
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
        if (!isAutoConnectEnabled() || connectedState || connectionInProgress) {
            return;
        }
        Log.d(TAG, "Service autoconnect attempt started");
        
        if (MainActivity.requestServiceDrivenAutoconnect()) {
            cancelRetry();
            updateNotification(getString(R.string.foreground_service_text_connecting_via_app), false);
            return;
        }
        
        if (isWifiDisabled()) {
            updateNotification(getString(R.string.foreground_service_text_wifi_disabled), false);
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
        
        updateNotification(getString(R.string.foreground_service_text_searching), false);
        startBleScan();
    }

    @SuppressLint("MissingPermission")
    private void startBleScan() {
        if (isBleScanning || connectedState || connectionInProgress) {
            return;
        }
        clearBleScanTimeout();
        
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Service: Bluetooth adapter not available");
            scheduleRetry();
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Log.w(TAG, "Service: Cannot get BLE scanner, adapterState=" + bluetoothAdapter.getState());
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
                boolean matched = nameMatched && (!hasMacGuard || macMatched);
                if (matched) {
                    Log.d(TAG, "Service found target BLE device: deviceName=" + deviceName + ", recordName=" + recordName);
                    cacheDeviceAddressFromResult(result, "Service");
                    lastTargetSeenAtMs = SystemClock.elapsedRealtime();
                    stopBleScan();
                    
                    boolean delegated = MainActivity.requestServiceDrivenAutoconnect();
                    if (delegated) {
                        updateNotification(getString(R.string.foreground_service_text_connecting_via_app), false);
                    } else {
                        requestNetworkInBackground(getTargetSsidOrNull());
                    }
                } else if (scanResultLogBudget > 0) {
                    scanResultLogBudget--;
                    Log.d(TAG, "Service BLE non-match: deviceName=" + deviceName
                            + ", recordName=" + recordName
                            + ", nameMatched=" + nameMatched
                            + ", macMatched=" + macMatched
                            + ", rssi=" + result.getRssi());
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "Service BLE scan failed: " + errorCode);
                isBleScanning = false;
                releaseBleScanWakeLock();
                scheduleRetry();
            }
        };

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        List<ScanFilter> scanFilters = buildBleScanFilters();
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

    private boolean isTargetBleNameMatch(String expectedName, String candidateName) {
        if (expectedName == null || expectedName.isEmpty() || candidateName == null || candidateName.isEmpty()) {
            return false;
        }
        String normalizedCandidate = candidateName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
        return expectedName.equals(normalizedCandidate);
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
                scheduleRetry();
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                connectedState = false;
                connectionInProgress = false;
                Log.d(TAG, "Service network unavailable");
                getPrefs().edit().putBoolean(PREF_CONNECTION_REFUSED_PENDING, true).apply();
                clearNetworkCallback();
                disableAutoConnectAfterFailure();
            }
        };

        connectivityManager.requestNetwork(request, networkCallback);
    }

    private void disableAutoConnectAfterFailure() {
        getPrefs().edit().putBoolean(PREF_AUTOCONNECT_ENABLED, false).apply();
        cancelRetry();
        stopBleScan();
        releaseBleScanWakeLock();
        handler.post(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void scheduleRetry() {
        if (!isAutoConnectEnabled() || connectedState || connectionInProgress) {
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
            lastTargetSeenAtMs = 0L;
        }
    }
}
