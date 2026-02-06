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
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final String TARGET_SSID = "TESTAP5";

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiManager wifiManager;
    private TextView statusTextView;
    private TextView dualWifiStatusTextView;
    private Button connectButton;

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
        runOnUiThread(() -> {
            statusTextView.setText("Connessione in corso...");
            statusTextView.setBackgroundColor(Color.parseColor("#FFA500")); // Arancione
        });

        final String networkPassword = "12345678";

        WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(TARGET_SSID)
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isConnected) {
            disconnectFromDrone();
        }
    }
}
