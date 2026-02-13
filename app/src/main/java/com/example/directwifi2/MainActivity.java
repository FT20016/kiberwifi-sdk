package com.example.directwifi2;

import android.Manifest;
import android.content.Context;
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
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final String SSID_PREFIX = "KIBERSCOPE-";
    private static final int SSID_SUFFIX_LENGTH = 5;
    private static final String PREFS_NAME = "directwifi2_prefs";
    private static final String PREF_SSID_SUFFIX = "pref_ssid_suffix";

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiManager wifiManager;
    private TextView statusTextView;
    private TextView dualWifiStatusTextView;
    private Button connectButton;
    private TextView ssidPrefixTextView;
    private EditText ssidSuffixEditText;
    private String lastSavedSuffix;
    private boolean suppressBlurRevert = false;

    private boolean isConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        statusTextView = findViewById(R.id.statusTextView);
        dualWifiStatusTextView = findViewById(R.id.dualWifiStatusTextView);
        connectButton = findViewById(R.id.connectButton);
        ssidPrefixTextView = findViewById(R.id.ssidPrefixTextView);
        ssidSuffixEditText = findViewById(R.id.ssidSuffixEditText);

        findViewById(R.id.main).setOnTouchListener((v, event) -> {
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

        // Il pulsante è sempre attivo quando non connesso
        connectButton.setEnabled(true);
        updateButtonState();
        checkDualWifiSupport();

        connectButton.setOnClickListener(v -> {
            if (isConnected) {
                disconnectFromDrone();
            } else {
                requestLocationPermission();
            }
            suppressBlurRevert = false;
        });
    }

    private void initSsidEditor() {
        lastSavedSuffix = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_SSID_SUFFIX, "");
        if (lastSavedSuffix == null) {
            lastSavedSuffix = "";
        }

        ssidPrefixTextView.setText(SSID_PREFIX);
        ssidSuffixEditText.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(SSID_SUFFIX_LENGTH)
        });
        ssidSuffixEditText.setText(lastSavedSuffix.toUpperCase());
        ssidSuffixEditText.setSelection(ssidSuffixEditText.getText().length());

        ssidSuffixEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable editable) {
                String suffix = editable.toString().toUpperCase();
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

            }
        });
    }

    private void checkDualWifiSupport() {
        boolean isSupported = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            isSupported = wifiManager.isStaConcurrencyForLocalOnlyConnectionsSupported();
        }

        if (isSupported) {
            dualWifiStatusTextView.setText("Dual Wi-Fi: Supportato");
            dualWifiStatusTextView.setTextColor(Color.parseColor("#4CAF50")); // Verde
        } else {
            dualWifiStatusTextView.setText("Dual Wi-Fi: Non supportato");
            dualWifiStatusTextView.setTextColor(Color.parseColor("#F44336")); // Rosso
        }
    }

    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_PERMISSION_REQUEST_CODE);
        } else {
            connectToDrone();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                connectToDrone();
            } else {
                Toast.makeText(this, "Permesso di localizzazione necessario per trovare reti Wi-Fi", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void connectToDrone() {
        if (!isSsidValid()) {
            Toast.makeText(this, "Inserisci un seriale alfanumerico di 5 caratteri", Toast.LENGTH_LONG).show();
            return;
        }

        runOnUiThread(() -> {
            statusTextView.setText("Connessione in corso...");
            statusTextView.setBackgroundColor(Color.parseColor("#FFA500")); // Arancione
            setSsidEditable(false);
        });

        final String networkPassword = "12345678";
        final String suffix = ssidSuffixEditText.getText().toString().toUpperCase();
        lastSavedSuffix = suffix;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_SSID_SUFFIX, lastSavedSuffix)
                .apply();
        final String targetSsid = SSID_PREFIX + suffix;

        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(targetSsid)
                .setWpa2Passphrase(networkPassword)
                .build();

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build();

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                super.onAvailable(network);
                Log.d(TAG, "Network available: " + network);
                isConnected = true;
                connectivityManager.bindProcessToNetwork(network);
                runOnUiThread(() -> {
                    statusTextView.setText("Connesso");
                    statusTextView.setBackgroundColor(Color.parseColor("#4CAF50")); // Green
                    updateButtonState();
                    connectButton.setEnabled(true);
                    setSsidEditable(false);
                });
            }

            @Override
            public void onLost(Network network) {
                super.onLost(network);
                Log.d(TAG, "Network lost: " + network);
                if (isConnected) {
                    isConnected = false;
                    runOnUiThread(() -> {
                        statusTextView.setText("Disconnesso");
                        statusTextView.setBackgroundColor(Color.parseColor("#F44336")); // Red
                        updateButtonState();
                        connectButton.setEnabled(true); // Riattiva il pulsante
                        setSsidEditable(true);
                    });
                }
            }

            @Override
            public void onUnavailable() {
                super.onUnavailable();
                Log.d(TAG, "Network unavailable");
                isConnected = false;
                runOnUiThread(() -> {
                    statusTextView.setText("Rete non trovata");
                    statusTextView.setBackgroundColor(Color.parseColor("#F44336")); // Red
                    updateButtonState();
                    connectButton.setEnabled(true); // Riattiva il pulsante
                    setSsidEditable(true);
                });
            }
        };

        connectivityManager.requestNetwork(request, networkCallback);
    }

    private void disconnectFromDrone() {
        if (networkCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
                networkCallback = null;
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Callback già deregistrato");
            }
        }

        connectivityManager.bindProcessToNetwork(null);

        isConnected = false;
        runOnUiThread(() -> {
            statusTextView.setText("Disconnesso");
            statusTextView.setBackgroundColor(Color.parseColor("#F44336")); // Red
            updateButtonState();
            connectButton.setEnabled(true); // Riattiva il pulsante
            setSsidEditable(true);
        });
        Log.d(TAG, "Disconnessione manuale completata.");
    }

    private void updateButtonState(){
        if (isConnected) {
            connectButton.setText("Disconnetti");
        } else {
            connectButton.setText("Connetti a Kiber");
        }
    }

    private boolean isSsidValid() {
        String suffix = ssidSuffixEditText.getText().toString().toUpperCase();
        if (suffix.length() != SSID_SUFFIX_LENGTH) {
            return false;
        }
        return suffix.matches("^[A-Z0-9]{" + SSID_SUFFIX_LENGTH + "}$");
    }

    private void setSsidEditable(boolean editable) {
        if (!editable) {
            ssidSuffixEditText.clearFocus();
        }
        ssidSuffixEditText.setEnabled(editable);
        ssidSuffixEditText.setFocusable(editable);
        ssidSuffixEditText.setFocusableInTouchMode(editable);
        ssidSuffixEditText.setTextColor(editable ? Color.BLACK : Color.GRAY);
        ssidSuffixEditText.setAlpha(editable ? 1.0f : 0.6f);
        ssidPrefixTextView.setTextColor(editable ? Color.BLACK : Color.GRAY);
        ssidPrefixTextView.setAlpha(editable ? 1.0f : 0.6f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isConnected) {
            disconnectFromDrone();
        }
    }
}
