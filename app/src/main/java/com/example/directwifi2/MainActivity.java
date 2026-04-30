package com.example.directwifi2;

import android.annotation.SuppressLint;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
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
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements KiberWifiServiceManager.KiberEventListener {

    private static final String TAG = "MainActivity";
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 2;
    private static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 3;
    private static final int BLE_PERMISSION_REQUEST_CODE = 4;
    
    private static final String SSID_PREFIX = "KIBERSCOPE-";
    private static final int SSID_SUFFIX_LENGTH = 5;
    
    private static final String PREFS_NAME = "directwifi2_prefs";
    private static final String PREF_SSID_SUFFIX = "pref_ssid_suffix";
    private static final String PREF_RUNTIME_PERMISSIONS_ASKED_ONCE = "pref_runtime_permissions_asked_once";
    private static final String PREF_CONNECTION_REFUSED_PENDING = "pref_connection_refused_pending";
    
    private static final String COLOR_DISCONNECTED = "#F44336";
    private static final String COLOR_CONNECTING = "#FFA500";
    private static final String COLOR_CONNECTED = "#4CAF50";
    
    private static boolean isInForeground = false;

    private WifiManager wifiManager;
    private BluetoothAdapter bluetoothAdapter;
    
    private TextView statusTextView;
    private TextView dualWifiStatusTextView;
    private Button connectButton;
    private TextView ssidPrefixTextView;
    private EditText ssidSuffixEditText;
    
    private String lastSavedSuffix;
    private String lastServiceTargetSuffix = "";
    private boolean isConnected = false;
    private boolean isConnectionInProgress = false;
    private boolean bleTargetDetected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "MainActivity onCreate build=" + getAppVersionTag());
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        statusTextView = findViewById(R.id.statusTextView);
        dualWifiStatusTextView = findViewById(R.id.dualWifiStatusTextView);
        connectButton = findViewById(R.id.connectButton);
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
        requestNotificationPermissionIfNeeded();

        updateButtonState();
        bleTargetDetected = KiberWifiServiceManager.isTargetPresent();
        refreshConnectButtonEnabled();
        checkDualWifiSupport();

        connectButton.setOnClickListener(v -> {
            if (isConnected) {
                KiberWifiServiceManager.disableConnect(getApplicationContext());
                return;
            }
            requestPermissionsForConnection();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        requestCorePermissionsAtStartupIfNeeded();
        syncUiWithCurrentWifiState();
        if (isSsidValid()) {
            KiberWifiServiceManager.start(
                    getApplicationContext(),
                    isConnected ? getString(R.string.foreground_service_text_connected)
                            : getString(R.string.foreground_service_text_autoconnect_waiting),
                    isConnected
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isInForeground = true;
        KiberWifiServiceManager.setListener(this);
        maybeShowPendingConnectionRefusedDialog();
    }

    @Override
    protected void onPause() {
        isInForeground = false;
        KiberWifiServiceManager.setListener(null);
        super.onPause();
    }

    @Override
    public void onKiberEvent(@NonNull KiberWifiServiceManager.KiberStatus status, @NonNull String message) {
        runOnUiThread(() -> {
            if ("KIBER_TARGET_PRESENT".equals(message)) {
                bleTargetDetected = true;
                if (!isConnected) {
                    updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
                }
                refreshConnectButtonEnabled();
            } else if ("KIBER_TARGET_ABSENT".equals(message)) {
                bleTargetDetected = false;
                refreshConnectButtonEnabled();
            } else if ("KIBER_WIFI_OFF".equals(message)) {
                bleTargetDetected = false;
                isConnectionInProgress = false;
                updateStatusBadge(R.string.status_warning_wifi_off, COLOR_DISCONNECTED);
                refreshConnectButtonEnabled();
            } else if ("KIBER_BT_OFF".equals(message)) {
                bleTargetDetected = false;
                isConnectionInProgress = false;
                updateStatusBadge(R.string.status_warning_bluetooth_off, COLOR_DISCONNECTED);
                refreshConnectButtonEnabled();
            }
            if (status == KiberWifiServiceManager.KiberStatus.SCANNING && !isConnected) {
                isConnectionInProgress = false;
                String targetSsid = getTargetSsidOrNull();
                if (targetSsid != null) {
                    updateStatusBadgeText(
                            "Ricerca dispositivo " + targetSsid,
                            COLOR_CONNECTING,
                            true
                    );
                } else {
                    updateStatusBadge(R.string.status_autoconnect_searching, COLOR_CONNECTING);
                }
                updateButtonState();
            } else if (status == KiberWifiServiceManager.KiberStatus.MONITORING && !isConnected) {
                isConnectionInProgress = false;
                updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
                updateButtonState();
            } else if (status == KiberWifiServiceManager.KiberStatus.CONNECTED) {
                isConnected = true;
                isConnectionInProgress = false;
                setSsidEditable(false);
                updateStatusBadge(R.string.status_connected, COLOR_CONNECTED);
                updateButtonState();
            } else if (status == KiberWifiServiceManager.KiberStatus.DISCONNECTED && !isConnected) {
                isConnectionInProgress = false;
                updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
                updateButtonState();
            } else if (status == KiberWifiServiceManager.KiberStatus.DISCONNECTED) {
                isConnected = false;
                isConnectionInProgress = false;
                setSsidEditable(true);
                updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
                updateButtonState();
            } else if (status == KiberWifiServiceManager.KiberStatus.IDLING && !isConnected) {
                isConnectionInProgress = false;
                setSsidEditable(true);
                updateButtonState();
            }
        });
    }

    @Override
    protected void onStop() {
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
                lastSavedSuffix = suffix;
                bleTargetDetected = false;
                refreshConnectButtonEnabled();
                onTargetSuffixChanged(suffix);

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

    private void onTargetSuffixChanged(String suffix) {
        boolean valid = suffix != null
                && suffix.length() == SSID_SUFFIX_LENGTH
                && suffix.matches("^[A-Z0-9]{" + SSID_SUFFIX_LENGTH + "}$");

        if (!valid) {
            // Ignore partial edits; reconfigure manager only when target code is complete.
            return;
        }

        if (suffix.equals(lastServiceTargetSuffix)) {
            return;
        }

        // Target changed: reset learned BLE MAC filters to avoid stale filtering
        // from previous devices/serials.
        KiberWifiServiceManager.clearLearnedBleFilters(getApplicationContext());
        KiberWifiServiceManager.stop(getApplicationContext());
        String contentText = isConnected
                ? getString(R.string.foreground_service_text_connected)
                : getString(R.string.foreground_service_text_autoconnect_waiting);
        // Small gap avoids scanner registration throttling during rapid target reconfiguration.
        ssidSuffixEditText.postDelayed(
                () -> KiberWifiServiceManager.start(getApplicationContext(), contentText, isConnected),
                400
        );
        lastServiceTargetSuffix = suffix;
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

    private void requestPermissionsForConnection() {
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
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), BLE_PERMISSION_REQUEST_CODE);
            return;
        }

        KiberWifiServiceManager.enableConnect(getApplicationContext());
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

    private boolean hasLocationPermission() {
        boolean hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        return hasFineLocation || hasCoarseLocation;
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

        if (requestCode != BLE_PERMISSION_REQUEST_CODE) return;

        boolean allGranted = true;
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            Toast.makeText(this, "Permessi necessari per il funzionamento", Toast.LENGTH_LONG).show();
            return;
        }

        requestBackgroundLocationPermissionIfNeeded();
        KiberWifiServiceManager.enableConnect(getApplicationContext());
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
            updateStatusBadge(R.string.status_connected, COLOR_CONNECTED);
            setSsidEditable(false);
        } else if (!isConnectionInProgress) {
            updateStatusBadge(R.string.status_disconnected, COLOR_DISCONNECTED);
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
        if (isConnected) {
            KiberWifiServiceManager.disableConnect(getApplicationContext());
        }
    }
}
