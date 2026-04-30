package com.example.directwifi2;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.util.Log;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.pm.PackageInfoCompat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.lang.ref.WeakReference;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 3;
    private static final int BLE_PERMISSION_REQUEST_CODE = 4;
    
    private static final String SSID_PREFIX = "KIBERSCOPE-";
    private static final String BLE_PREFIX = "KS-";
    private static final int SSID_SUFFIX_LENGTH = 5;
    
    private static final String PREFS_NAME = "directwifi2_prefs";
    private static final String PREF_SSID_SUFFIX = "pref_ssid_suffix";
    private static final String PREF_AUTOCONNECT_ENABLED = "pref_autoconnect_enabled";
    private static final String PREF_BATTERY_OPT_DIALOG_SHOWN = "pref_battery_opt_dialog_shown";
    private static final String PREF_LEARNED_DEVICE_ADDRESSES = "pref_learned_device_addresses";
    private static final String PREF_RUNTIME_PERMISSIONS_ASKED_ONCE = "pref_runtime_permissions_asked_once";
    private static final String PREF_CONNECTION_REFUSED_PENDING = "pref_connection_refused_pending";
    
    private static final String COLOR_DISCONNECTED = "#F44336";
    private static final String COLOR_CONNECTING = "#FFA500";
    private static final String COLOR_CONNECTED = "#4CAF50";
    
    private static final long AUTOCONNECT_RETRY_INITIAL_DELAY_MS = 10_000L;
    private static final long AUTOCONNECT_RETRY_MAX_DELAY_MS = 30_000L;
    private static final long STATE_CHECK_INTERVAL_MS = 2_000L;
    private static final long BLE_VALIDATION_SCAN_WINDOW_MS = 5_000L;
    private static final long BLE_VALIDATION_SCAN_CYCLE_MS = 10_000L;
    
    private static WeakReference<MainActivity> currentInstance = new WeakReference<>(null);
    private static boolean isInForeground = false;

    private ConnectivityManager connectivityManager;
    private WifiManager wifiManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bluetoothLeScanner;
    private ScanCallback bleScanCallback;
    
    private TextView statusTextView;
    private TextView dualWifiStatusTextView;
    private Button connectButton;
    private CheckBox autoConnectCheckBox;
    private TextView ssidPrefixTextView;
    private EditText ssidSuffixEditText;
    
    private String lastSavedSuffix;
    private boolean updatingAutoConnectCheckBox = false;
    private boolean isConnected = false;
    private boolean isConnectionInProgress = false;
    private boolean pendingAutoConnectAfterPermission = false;
    private boolean isWifiReceiverRegistered = false;
    private boolean suppressAutoConnect = false;
    private boolean lastConnectionAttemptWasAutoConnect = false;
    private boolean isBleScanning = false;
    private boolean bleTargetDetected = false;
    private boolean bleSeenInCurrentWindow = false;
    private boolean wifiEnablePromptShown = false;
    private boolean bluetoothEnablePromptShown = false;
    
    private long autoConnectRetryDelayMs = AUTOCONNECT_RETRY_INITIAL_DELAY_MS;
    private final Handler autoConnectHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoConnectRetryRunnable = this::attemptAutoConnectIfEnabled;
    private final Runnable stateCheckRunnable = new Runnable() {
        @Override
        public void run() {
            performPeriodicStateCheck();
            autoConnectHandler.postDelayed(this, STATE_CHECK_INTERVAL_MS);
        }
    };
    private final Runnable bleValidationCycleRunnable = new Runnable() {
        @Override
        public void run() {
            runBleValidationCycle();
            autoConnectHandler.postDelayed(this, BLE_VALIDATION_SCAN_CYCLE_MS);
        }
    };
    private Runnable bleScanTimeoutRunnable;

    /**
     * Listens only for global Wi-Fi enable/disable events.
     * Wi-Fi scan results are no longer used here as detection is moved to BLE.
     */
    private final BroadcastReceiver wifiStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String action = intent.getAction();
            if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                Log.d(TAG, "Wi-Fi state changed: " + wifiState);
                if (wifiState == WifiManager.WIFI_STATE_ENABLED) {
                    attemptAutoConnectIfEnabled();
                } else if (wifiState == WifiManager.WIFI_STATE_DISABLED && !isConnected) {
                    updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
                }
            }
        }
    };

    private ConnectivityManager.NetworkCallback networkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate build=" + getAppVersionTag());
        currentInstance = new WeakReference<>(this);
        setContentView(R.layout.activity_main);

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        statusTextView = findViewById(R.id.statusTextView);
        dualWifiStatusTextView = findViewById(R.id.dualWifiStatusTextView);
        connectButton = findViewById(R.id.connectButton);
        autoConnectCheckBox = findViewById(R.id.autoConnectCheckBox);
        ssidPrefixTextView = findViewById(R.id.ssidPrefixTextView);
        ssidSuffixEditText = findViewById(R.id.ssidSuffixEditText);

        findViewById(R.id.main).setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
            }
            if (!ssidSuffixEditText.hasFocus()) {
                return false;
            }
            int[] location = new int[2];
            ssidSuffixEditText.getLocationOnScreen(location);
            float x = event.getRawX();
            float y = event.getRawY();
            boolean inside = x >= location[0]
                    && x <= location[0] + ssidSuffixEditText.getWidth()
                    && y >= location[1]
                    && y <= location[1] + ssidSuffixEditText.getHeight();
            if (!inside) {
                ssidSuffixEditText.setText(lastSavedSuffix);
                ssidSuffixEditText.setSelection(ssidSuffixEditText.getText().length());
                ssidSuffixEditText.clearFocus();
            }
            return false;
        });

        initSsidEditor();
        initAutoConnectToggle();
        requestNotificationPermissionIfNeeded();

        updateButtonState();
        refreshConnectButtonEnabled();
        checkDualWifiSupport();

        connectButton.setOnClickListener(v -> {
            if (isConnected) {
                disableAutoConnect();
                disconnectFromKiberscopeWifi();
                return;
            }
            suppressAutoConnect = false;
            if (isWifiDisabled()) {
                showEnableWifiDialog();
                return;
            }
            requestPermissionsForConnection(false);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerWifiReceiver();
        requestCorePermissionsAtStartupIfNeeded();
        syncUiWithCurrentWifiState();
        autoConnectHandler.removeCallbacks(stateCheckRunnable);
        autoConnectHandler.post(stateCheckRunnable);
        autoConnectHandler.removeCallbacks(bleValidationCycleRunnable);
        autoConnectHandler.post(bleValidationCycleRunnable);
        if (isAutoConnectEnabled()) {
            if (isConnected) {
                KiberWifiServiceManager.start(
                        getApplicationContext(),
                        getString(R.string.foreground_service_text_connected),
                        true
                );
            }
            // Do not trigger autoconnect attempts just by opening the app.
            // Attempts must start explicitly from the Connect button.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        currentInstance = new WeakReference<>(this);
        isInForeground = true;
        maybeShowPendingConnectionRefusedDialog();
    }

    @Override
    protected void onPause() {
        isInForeground = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        autoConnectHandler.removeCallbacks(stateCheckRunnable);
        autoConnectHandler.removeCallbacks(bleValidationCycleRunnable);
        cancelAutoConnectRetry();
        stopBleScan();
        unregisterWifiReceiver();
        super.onStop();
    }

    private void initSsidEditor() {
        lastSavedSuffix = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_SSID_SUFFIX, "");

        ssidPrefixTextView.setText(SSID_PREFIX);
        ssidSuffixEditText.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(SSID_SUFFIX_LENGTH)
        });
        ssidSuffixEditText.setText(lastSavedSuffix.toUpperCase(Locale.ROOT));
        ssidSuffixEditText.setSelection(ssidSuffixEditText.getText().length());

        ssidSuffixEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable editable) {
                String suffix = editable.toString().toUpperCase(Locale.ROOT);
                suffix = suffix.replaceAll("[^A-Z0-9]", "");
                if (suffix.length() > SSID_SUFFIX_LENGTH) {
                    suffix = suffix.substring(0, SSID_SUFFIX_LENGTH);
                }

                if (!suffix.equals(editable.toString())) {
                    ssidSuffixEditText.removeTextChangedListener(this);
                    ssidSuffixEditText.setText(suffix);
                    ssidSuffixEditText.setSelection(suffix.length());
                    ssidSuffixEditText.addTextChangedListener(this);
                }

                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(PREF_SSID_SUFFIX, suffix)
                        .apply();
                bleTargetDetected = false;
                refreshConnectButtonEnabled();

                if (suffix.length() == SSID_SUFFIX_LENGTH) {
                    ssidSuffixEditText.post(() -> {
                        ssidSuffixEditText.clearFocus();
                        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) {
                            imm.hideSoftInputFromWindow(ssidSuffixEditText.getWindowToken(), 0);
                        }
                    });
                }
            }
        });
    }

    private void initAutoConnectToggle() {
        boolean isAutoConnectEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_AUTOCONNECT_ENABLED, false);
        
        // Ensure permissions are valid if autoconnect is currently set to true
        if (isAutoConnectEnabled && !hasRequiredPermissionsForAutoconnect()) {
            isAutoConnectEnabled = false;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_AUTOCONNECT_ENABLED, false)
                    .apply();
        }
        
        setAutoConnectCheckedSilently(isAutoConnectEnabled);
        
        autoConnectCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingAutoConnectCheckBox) return;
            
            if (isChecked && !hasRequiredPermissionsForAutoconnect()) {
                setAutoConnectCheckedSilently(false);
                showAutoConnectPermissionDialog();
                return;
            }
            
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_AUTOCONNECT_ENABLED, isChecked)
                    .apply();

            suppressAutoConnect = !isChecked;
            pendingAutoConnectAfterPermission = false;

            if (isChecked) {
                // Enabling autoconnect only sets the mode; it must not start a connection attempt immediately.
                if (!isConnected && !isConnectionInProgress) {
                    updateStatusBadge(R.string.status_autoconnect_waiting, COLOR_CONNECTING);
                    updateButtonState();
                    setSsidEditable(true);
                }
            } else {
                // Turning off autoconnect must also release any active KIBERSCOPE Wi-Fi connection.
                disconnectFromKiberscopeWifi();
            }
        });
    }

    private void setAutoConnectCheckedSilently(boolean checked) {
        updatingAutoConnectCheckBox = true;
        autoConnectCheckBox.setChecked(checked);
        updatingAutoConnectCheckBox = false;
    }

    private void disableAutoConnect() {
        suppressAutoConnect = true;
        pendingAutoConnectAfterPermission = false;
        cancelAutoConnectRetry();
        stopBleScan();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_AUTOCONNECT_ENABLED, false)
                .apply();
        setAutoConnectCheckedSilently(false);
        KiberWifiServiceManager.stop(getApplicationContext());
    }

    private void checkDualWifiSupport() {
        boolean isSupported = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            isSupported = wifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported();
        }

        if (isSupported) {
            dualWifiStatusTextView.setText(R.string.dual_wifi_supported);
            dualWifiStatusTextView.setTextColor(Color.parseColor(COLOR_CONNECTED));
        } else {
            dualWifiStatusTextView.setText(R.string.dual_wifi_not_supported);
            dualWifiStatusTextView.setTextColor(Color.parseColor(COLOR_DISCONNECTED));
        }
    }

    private void registerWifiReceiver() {
        if (isWifiReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        ContextCompat.registerReceiver(this, wifiStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        isWifiReceiverRegistered = true;
    }

    private void unregisterWifiReceiver() {
        if (!isWifiReceiverRegistered) return;
        unregisterReceiver(wifiStateReceiver);
        isWifiReceiverRegistered = false;
    }

    private void requestPermissionsForConnection(boolean autoConnectAfterGrant) {
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

        if (!permissionsToRequest.isEmpty()) {
            pendingAutoConnectAfterPermission = autoConnectAfterGrant;
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), BLE_PERMISSION_REQUEST_CODE);
            return;
        }

        pendingAutoConnectAfterPermission = false;
        if (autoConnectAfterGrant) {
            startBleScan(true);
        } else {
            connectToKiberscopeWifi(false);
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
    }

    private void requestCorePermissionsAtStartupIfNeeded() {
        boolean alreadyAsked = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_RUNTIME_PERMISSIONS_ASKED_ONCE, false);
        if (alreadyAsked) {
            return;
        }

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

        if (permissionsToRequest.isEmpty()) {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREF_RUNTIME_PERMISSIONS_ASKED_ONCE, true)
                    .apply();
            return;
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_RUNTIME_PERMISSIONS_ASKED_ONCE, true)
                .apply();
        ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), BLE_PERMISSION_REQUEST_CODE);
    }

    private void requestBackgroundLocationPermissionIfNeeded() {
        if (hasBackgroundLocationPermission()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            showBackgroundLocationSettingsDialog();
            return;
        }
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE);
    }

    private boolean hasRequiredPermissionsForAutoconnect() {
        boolean basePermissions = hasLocationPermission() && hasBackgroundLocationPermission();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            basePermissions &= (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED);
        }
        return basePermissions;
    }

    private boolean hasBackgroundLocationPermission() {
        // Background location is required on Android 10+ for reliable background BLE scanning on this app flow.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void showBackgroundLocationSettingsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.background_location_dialog_title)
                .setMessage(R.string.background_location_dialog_message)
                .setPositiveButton(R.string.background_location_dialog_open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAutoConnectPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.autoconnect_permission_dialog_title)
                .setMessage(R.string.autoconnect_permission_dialog_message)
                .setPositiveButton(R.string.background_location_dialog_open_settings, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void maybePromptDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null) return;
        if (powerManager.isIgnoringBatteryOptimizations(getPackageName())) return;

        boolean alreadyShown = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_BATTERY_OPT_DIALOG_SHOWN, false);
        if (alreadyShown) return;

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_BATTERY_OPT_DIALOG_SHOWN, true)
                .apply();

        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_optimization_dialog_title)
                .setMessage(R.string.battery_optimization_dialog_message)
                .setPositiveButton(R.string.battery_optimization_dialog_open_settings, (dialog, which) -> openBatteryOptimizationSettings())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent fallbackIntent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(fallbackIntent);
        }
    }

    private boolean hasLocationPermission() {
        boolean hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return hasFineLocation || hasCoarseLocation;
    }

    private void attemptAutoConnectIfEnabled() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            autoConnectHandler.post(this::attemptAutoConnectIfEnabled);
            return;
        }
        if (!isAutoConnectEnabled() || suppressAutoConnect || isConnected || isConnectionInProgress) return;
        
        if (isWifiDisabled()) {
            Log.d(TAG, "Autoconnect skipped: Wi-Fi disabled");
            scheduleAutoConnectRetry();
            return;
        }
        if (!isSsidValid()) {
            Log.d(TAG, "Autoconnect skipped: invalid suffix");
            return;
        }
        Log.d(TAG, "Autoconnect BLE attempt started");
        String targetSsid = getTargetSsidOrNull();
        if (targetSsid != null) {
            updateStatusBadgeText("Ricerca dispositivo " + targetSsid, COLOR_CONNECTING, true);
        } else {
            updateStatusBadge(R.string.status_autoconnect_searching, COLOR_CONNECTING);
        }
        requestPermissionsForConnection(true);
    }

    public static boolean requestServiceDrivenAutoconnect() {
        MainActivity activity = currentInstance.get();
        if (activity == null || !isInForeground) return false;
        activity.runOnUiThread(activity::handleServiceAutoconnectRequest);
        return true;
    }

    private void handleServiceAutoconnectRequest() {
        if (!isAutoConnectEnabled() || suppressAutoConnect || isConnected || isConnectionInProgress) return;
        if (isWifiDisabled()) return;
        requestPermissionsForConnection(true);
    }

    @SuppressLint("MissingPermission")
    private void startBleScan(boolean connectOnFound) {
        if (isBleScanning || isConnected || isConnectionInProgress) return;
        clearBleScanTimeout();
        bleSeenInCurrentWindow = false;
        
        if (bluetoothAdapter == null) {
            Log.w(TAG, "Bluetooth adapter not available");
            scheduleAutoConnectRetry();
            return;
        }

        bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null) {
            Log.w(TAG, "Cannot get BLE scanner, adapterState=" + bluetoothAdapter.getState());
            scheduleAutoConnectRetry();
            return;
        }

        String targetBleName = BLE_PREFIX + ssidSuffixEditText.getText().toString().toUpperCase(Locale.ROOT);
        Log.d(TAG, "Starting BLE scan for " + targetBleName);

        bleScanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                String deviceName = null;
                try {
                    deviceName = result.getDevice().getName();
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException getting BLE device name", e);
                }

                if (isTargetBleNameMatch(targetBleName, deviceName)) {
                    bleSeenInCurrentWindow = true;
                    Log.d(TAG, "Found target BLE device: " + deviceName);
                    cacheDeviceAddressFromResult(result, "Main");
                    bleTargetDetected = true;
                    refreshConnectButtonEnabled();
                    stopBleScan();
                    if (connectOnFound) {
                        runOnUiThread(() -> connectToKiberscopeWifi(true));
                    }
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                Log.e(TAG, "BLE scan failed: " + errorCode);
                isBleScanning = false;
                bleTargetDetected = false;
                refreshConnectButtonEnabled();
                if (!isConnected && !isConnectionInProgress) {
                    updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
                }
                scheduleAutoConnectRetry();
            }
        };

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        
        bluetoothLeScanner.startScan(null, settings, bleScanCallback);
        isBleScanning = true;
        
        String targetSsid = getTargetSsidOrNull();
        if (targetSsid != null) {
            updateStatusBadgeText("Ricerca dispositivo " + targetSsid, COLOR_CONNECTING, true);
        } else {
            updateStatusBadge(R.string.status_autoconnect_searching, COLOR_CONNECTING);
        }
        
        // Scan window for BLE validation cycle: 5 seconds ON, then scheduler provides 5 seconds OFF.
        bleScanTimeoutRunnable = () -> {
            if (isBleScanning) {
                Log.d(TAG, "BLE scan timeout");
                stopBleScan();
                if (!bleSeenInCurrentWindow) {
                    bleTargetDetected = false;
                }
                refreshConnectButtonEnabled();
                scheduleAutoConnectRetry();
            }
        };
        autoConnectHandler.postDelayed(bleScanTimeoutRunnable, BLE_VALIDATION_SCAN_WINDOW_MS);
    }

    @SuppressLint("MissingPermission")
    private void stopBleScan() {
        clearBleScanTimeout();
        if (!isBleScanning || bluetoothLeScanner == null || bleScanCallback == null) return;
        Log.d(TAG, "Stopping BLE scan");
        try {
            bluetoothLeScanner.stopScan(bleScanCallback);
        } catch (Exception e) {
            Log.w(TAG, "Error stopping BLE scan", e);
        }
        isBleScanning = false;
        bleScanCallback = null;
        if (!isConnected && !isConnectionInProgress) {
            updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
        }
    }

    private void clearBleScanTimeout() {
        if (bleScanTimeoutRunnable != null) {
            autoConnectHandler.removeCallbacks(bleScanTimeoutRunnable);
            bleScanTimeoutRunnable = null;
        }
    }


    private Set<String> getLearnedDeviceAddresses() {
        String raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_LEARNED_DEVICE_ADDRESSES, "");
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
            if (csv.length() > 0) {
                csv.append(',');
            }
            csv.append(mac);
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_LEARNED_DEVICE_ADDRESSES, csv.toString())
                .apply();
        Log.d(TAG, owner + " learned BLE MAC filters: " + csv);
    }

    private boolean isTargetBleNameMatch(String expectedName, String candidateName) {
        if (expectedName == null || expectedName.isEmpty() || candidateName == null || candidateName.isEmpty()) {
            return false;
        }
        String normalizedCandidate = candidateName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", "");
        return expectedName.equals(normalizedCandidate);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                Toast.makeText(this, R.string.background_location_permission_required, Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode != BLE_PERMISSION_REQUEST_CODE && requestCode != LOCATION_PERMISSION_REQUEST_CODE) return;

        boolean allGranted = true;
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        boolean autoConnectAfterGrant = pendingAutoConnectAfterPermission;
        pendingAutoConnectAfterPermission = false;

        if (!allGranted) {
            scheduleAutoConnectRetry();
            Toast.makeText(this, "Permessi necessari per il funzionamento", Toast.LENGTH_LONG).show();
            return;
        }

        if (autoConnectAfterGrant) {
            requestBackgroundLocationPermissionIfNeeded();
            startBleScan(true);
        } else {
            connectToKiberscopeWifi(false);
        }
    }

    private void connectToKiberscopeWifi(boolean autoConnectAttempt) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            autoConnectHandler.post(() -> connectToKiberscopeWifi(autoConnectAttempt));
            return;
        }
        if (isConnectionInProgress) return;
        
        if (isWifiDisabled()) {
            if (!autoConnectAttempt) showEnableWifiDialog();
            else scheduleAutoConnectRetry();
            return;
        }
        if (!isSsidValid()) {
            if (!autoConnectAttempt) Toast.makeText(this, R.string.ssid_invalid_message, Toast.LENGTH_LONG).show();
            return;
        }

        stopBleScan();
        cancelAutoConnectRetry();
        clearNetworkCallback();
        isConnectionInProgress = true;
        lastConnectionAttemptWasAutoConnect = autoConnectAttempt;
        autoConnectRetryDelayMs = AUTOCONNECT_RETRY_INITIAL_DELAY_MS;
        Log.d(TAG, "Connecting to target network, autoConnectAttempt=" + autoConnectAttempt);

        updateStatusBadge(R.string.status_connecting, COLOR_CONNECTING);
        setSsidEditable(false);

        String suffix = ssidSuffixEditText.getText().toString().toUpperCase(Locale.ROOT);
        lastSavedSuffix = suffix;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_SSID_SUFFIX, lastSavedSuffix).apply();
        String targetSsid = SSID_PREFIX + suffix;

        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(targetSsid)
                .setWpa2Passphrase("12345678")
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
                Log.d(TAG, "Network available: " + network);
                setConnectionRefusedPending(false);
                isConnected = true;
                isConnectionInProgress = false;
                suppressAutoConnect = false;
                autoConnectRetryDelayMs = AUTOCONNECT_RETRY_INITIAL_DELAY_MS;
                cancelAutoConnectRetry();
                connectivityManager.bindProcessToNetwork(network);
                
                BeepHelper.playBeep(MainActivity.this, true);
                
                runOnUiThread(() -> {
                    updateStatusBadge(R.string.status_connected, COLOR_CONNECTED);
                    updateButtonState();
                    connectButton.setEnabled(true);
                    setSsidEditable(false);
                });
                KiberWifiServiceManager.start(getApplicationContext(), getString(R.string.foreground_service_text_connected), true);
            }

            @Override
            public void onLost(@NonNull Network network) {
                super.onLost(network);
                Log.d(TAG, "Network lost: " + network);
                if (isConnected) {
                    BeepHelper.playBeep(MainActivity.this, false);
                    isConnected = false;
                    isConnectionInProgress = false;
                    bleTargetDetected = false;
                    runOnUiThread(() -> {
                        int statusRes = isAutoConnectEnabled() && !suppressAutoConnect ? R.string.status_autoconnect_searching : R.string.status_disconnected;
                        String statusColor = isAutoConnectEnabled() && !suppressAutoConnect ? COLOR_CONNECTING : COLOR_DISCONNECTED;
                        updateStatusBadge(statusRes, statusColor);
                        updateButtonState();
                        connectButton.setEnabled(true);
                        setSsidEditable(true);
                    });
                    if (!isAutoConnectEnabled()) {
                        KiberWifiServiceManager.stop(getApplicationContext());
                    } else {
                        KiberWifiServiceManager.start(getApplicationContext(), getString(R.string.foreground_service_text_autoconnect_waiting), false);
                    }
                }
                clearNetworkCallback();
                if (isAutoConnectEnabled() && !suppressAutoConnect && isInForeground) {
                    autoConnectHandler.post(() -> {
                        scheduleAutoConnectRetry();
                        attemptAutoConnectIfEnabled();
                    });
                }
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                Log.d(TAG, "Network unavailable");
                isConnected = false;
                isConnectionInProgress = false;
                bleTargetDetected = false;
                boolean failedDuringAutoconnect = lastConnectionAttemptWasAutoConnect && isAutoConnectEnabled();
                if (failedDuringAutoconnect) {
                    disableAutoConnect();
                }
                runOnUiThread(() -> {
                    updateStatusBadge(R.string.status_connection_refused, COLOR_DISCONNECTED);
                    updateButtonState();
                    connectButton.setEnabled(true);
                    setSsidEditable(true);
                });
                if (isInForeground) {
                    runOnUiThread(MainActivity.this::showConnectionRefusedDialog);
                } else {
                    setConnectionRefusedPending(true);
                }
                if (failedDuringAutoconnect) {
                    // Autoconnect is already disabled above; keep service stopped to avoid retries/notification loops.
                    KiberWifiServiceManager.stop(getApplicationContext());
                } else if (!isAutoConnectEnabled()) {
                    KiberWifiServiceManager.stop(getApplicationContext());
                } else {
                    KiberWifiServiceManager.start(getApplicationContext(), getString(R.string.foreground_service_text_connection_refused), false);
                }
                clearNetworkCallback();
                if (failedDuringAutoconnect) {
                    // Nothing else to do: autoconnect was disabled to prevent repeated failures.
                }
            }
        };

        connectivityManager.requestNetwork(request, networkCallback);
    }

    private void disconnectFromKiberscopeWifi() {
        if (isConnected) {
            BeepHelper.playBeep(this, false);
        }
        clearNetworkCallback();
        isConnected = false;
        isConnectionInProgress = false;
        bleTargetDetected = false;
        cancelAutoConnectRetry();
        stopBleScan();
        KiberWifiServiceManager.stop(getApplicationContext());
        runOnUiThread(() -> {
            updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
            updateButtonState();
            connectButton.setEnabled(true);
            setSsidEditable(true);
        });
        connectivityManager.bindProcessToNetwork(null);
        disconnectFromTargetApIfNeeded();
        Log.d(TAG, "Manual disconnect completed.");
    }

    private void disconnectFromTargetApIfNeeded() {
        try {
            if (wifiManager == null) {
                return;
            }
            @SuppressLint("MissingPermission")
            String currentSsidRaw = wifiManager.getConnectionInfo() != null
                    ? wifiManager.getConnectionInfo().getSSID()
                    : null;
            if (currentSsidRaw == null) {
                return;
            }
            String currentSsid = currentSsidRaw.replace("\"", "");
            if (!currentSsid.startsWith(SSID_PREFIX)) {
                return;
            }
            boolean disconnected = wifiManager.disconnect();
            Log.d(TAG, "Requested Wi-Fi disconnect from target AP, success=" + disconnected + ", ssid=" + currentSsid);
        } catch (Exception e) {
            Log.w(TAG, "Failed to force disconnect from target AP", e);
        }
    }

    private void clearNetworkCallback() {
        if (networkCallback == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Network callback already unregistered");
        }
        networkCallback = null;
    }

    private void scheduleAutoConnectRetry() {
        if (!isAutoConnectEnabled() || suppressAutoConnect || isConnected || isConnectionInProgress) return;
        autoConnectHandler.removeCallbacks(autoConnectRetryRunnable);
        long scheduledDelay = autoConnectRetryDelayMs;
        autoConnectHandler.postDelayed(autoConnectRetryRunnable, scheduledDelay);
        autoConnectRetryDelayMs = Math.min(autoConnectRetryDelayMs * 2, AUTOCONNECT_RETRY_MAX_DELAY_MS);
        Log.d(TAG, "Scheduled autoconnect retry in " + scheduledDelay + " ms");
    }

    private void cancelAutoConnectRetry() {
        autoConnectHandler.removeCallbacks(autoConnectRetryRunnable);
        autoConnectRetryDelayMs = AUTOCONNECT_RETRY_INITIAL_DELAY_MS;
    }

    private void updateButtonState() {
        connectButton.setText(isConnected ? R.string.disconnect_kiberscope_wifi : R.string.connect_kiberscope_wifi);
        refreshConnectButtonEnabled();
    }

    private void refreshConnectButtonEnabled() {
        if (connectButton == null) {
            return;
        }
        if (isConnected) {
            connectButton.setEnabled(true);
            return;
        }
        boolean enabled = !isConnectionInProgress
                && !isWifiDisabled()
                && isBluetoothReadyForBleScan()
                && isSsidValid()
                && bleTargetDetected;
        connectButton.setEnabled(enabled);
    }

    private void syncUiWithCurrentWifiState() {
        boolean connectedNow = isConnectedToKiberscopeApNow();
        if (connectedNow == isConnected) {
            return;
        }
        isConnected = connectedNow;
        if (connectedNow) {
            isConnectionInProgress = false;
            cancelAutoConnectRetry();
            stopBleScan();
            updateStatusBadge(R.string.status_connected, COLOR_CONNECTED);
            setSsidEditable(false);
        } else if (!isConnectionInProgress) {
            int statusRes = isAutoConnectEnabled() && !suppressAutoConnect
                    ? R.string.status_autoconnect_waiting
                    : R.string.status_disconnected;
            String statusColor = isAutoConnectEnabled() && !suppressAutoConnect
                    ? COLOR_CONNECTING
                    : COLOR_DISCONNECTED;
            updateStatusBadge(statusRes, statusColor);
            setSsidEditable(true);
        }
        updateButtonState();
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
            Log.w(TAG, "Failed to read current SSID for state sync", e);
            return false;
        }
    }

    private void updateStatusBadge(int textRes, String backgroundColor) {
        statusTextView.setText(textRes);
        float statusSizeSp = (textRes == R.string.status_autoconnect_searching) ? 14f : 18f;
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, statusSizeSp);
        statusTextView.setBackgroundColor(Color.parseColor(backgroundColor));
    }

    private void updateStatusBadgeText(String text, String backgroundColor, boolean compact) {
        statusTextView.setText(text);
        statusTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 14f : 18f);
        statusTextView.setBackgroundColor(Color.parseColor(backgroundColor));
    }

    private void setConnectionRefusedPending(boolean pending) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CONNECTION_REFUSED_PENDING, pending)
                .apply();
    }

    private void maybeShowPendingConnectionRefusedDialog() {
        boolean pending = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean(PREF_CONNECTION_REFUSED_PENDING, false);
        if (!pending) {
            return;
        }
        setConnectionRefusedPending(false);
        showConnectionRefusedDialog();
    }

    private void showConnectionRefusedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.connection_refused_notification_title)
                .setMessage(R.string.connection_refused_notification_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private boolean isSsidValid() {
        return getTargetSsidOrNull() != null;
    }

    private String getTargetSsidOrNull() {
        String suffix = ssidSuffixEditText.getText().toString().toUpperCase(Locale.ROOT);
        if (suffix.length() != SSID_SUFFIX_LENGTH) return null;
        if (!suffix.matches("^[A-Z0-9]{" + SSID_SUFFIX_LENGTH + "}$")) return null;
        return SSID_PREFIX + suffix;
    }

    private boolean isAutoConnectEnabled() {
        return autoConnectCheckBox.isChecked();
    }

    private void setSsidEditable(boolean editable) {
        if (!editable) ssidSuffixEditText.clearFocus();
        ssidSuffixEditText.setEnabled(editable);
        ssidSuffixEditText.setFocusable(editable);
        ssidSuffixEditText.setFocusableInTouchMode(editable);
        ssidSuffixEditText.setTextColor(editable ? Color.BLACK : Color.GRAY);
        ssidSuffixEditText.setAlpha(editable ? 1.0f : 0.6f);
        ssidPrefixTextView.setTextColor(editable ? Color.BLACK : Color.GRAY);
        ssidPrefixTextView.setAlpha(editable ? 1.0f : 0.6f);
    }

    private boolean isWifiDisabled() {
        return wifiManager == null || !wifiManager.isWifiEnabled();
    }

    private boolean isBluetoothReadyForBleScan() {
        if (bluetoothAdapter == null) {
            return false;
        }
        if (!bluetoothAdapter.isEnabled()) {
            return false;
        }
        return bluetoothAdapter.getBluetoothLeScanner() != null;
    }

    private void performPeriodicStateCheck() {
        boolean wifiOn = !isWifiDisabled();
        boolean bluetoothReady = isBluetoothReadyForBleScan();

        if (!wifiOn) {
            bleTargetDetected = false;
            if (isInForeground && !wifiEnablePromptShown && !isFinishing()) {
                wifiEnablePromptShown = true;
                showEnableWifiDialog();
            }
        } else {
            wifiEnablePromptShown = false;
        }

        if (!bluetoothReady) {
            bleTargetDetected = false;
            if (isInForeground && !bluetoothEnablePromptShown && !isFinishing()) {
                bluetoothEnablePromptShown = true;
                showEnableBluetoothDialog();
            }
        } else {
            bluetoothEnablePromptShown = false;
        }

        if (!isConnected && !isConnectionInProgress && !isBleScanning) {
            if (!wifiOn && !bluetoothReady) {
                updateStatusBadge(R.string.status_warning_wifi_bluetooth_off, COLOR_DISCONNECTED);
            } else if (!wifiOn) {
                updateStatusBadge(R.string.status_warning_wifi_off, COLOR_DISCONNECTED);
            } else if (!bluetoothReady) {
                updateStatusBadge(R.string.status_warning_bluetooth_off, COLOR_DISCONNECTED);
            }
        }

        refreshConnectButtonEnabled();
    }

    private void runBleValidationCycle() {
        if (isConnected || isConnectionInProgress || isBleScanning) {
            return;
        }
        if (isWifiDisabled() || !isBluetoothReadyForBleScan() || !isSsidValid()) {
            bleTargetDetected = false;
            refreshConnectButtonEnabled();
            return;
        }
        startBleScan(false);
    }

    private void showEnableWifiDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.wifi_disabled_dialog_title)
                .setMessage(R.string.wifi_disabled_dialog_message)
                .setCancelable(false)
                .setPositiveButton(R.string.wifi_disabled_dialog_enable_button, (dialog, which) -> {
                    Intent intent = new Intent(Settings.Panel.ACTION_WIFI);
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showEnableBluetoothDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.bluetooth_disabled_dialog_title)
                .setMessage(R.string.bluetooth_disabled_dialog_message)
                .setCancelable(false)
                .setPositiveButton(R.string.bluetooth_disabled_dialog_enable_button, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MainActivity activity = currentInstance.get();
        if (activity == this) currentInstance = new WeakReference<>(null);
        if (isConnected && !isAutoConnectEnabled()) disconnectFromKiberscopeWifi();
        stopBleScan();
    }
}
