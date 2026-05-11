package com.kiber.sample;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.kiber.sdk.KiberWifiServiceManager;

public class MainActivity extends AppCompatActivity implements KiberWifiServiceManager.KiberEventListener {
    private TextView statusText;
    private EditText deviceNameInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        deviceNameInput = findViewById(R.id.deviceNameInput);
        Button startBtn = findViewById(R.id.startBtn);
        Button connectBtn = findViewById(R.id.connectBtn);
        Button disconnectBtn = findViewById(R.id.disconnectBtn);

        startBtn.setOnClickListener(v -> {
            String name = deviceNameInput.getText().toString().trim().toUpperCase();
            KiberWifiServiceManager.start(this, name, false);
        });

        connectBtn.setOnClickListener(v -> KiberWifiServiceManager.enableConnect(this));
        disconnectBtn.setOnClickListener(v -> KiberWifiServiceManager.disableConnect(getApplicationContext()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        KiberWifiServiceManager.setHostAppInForeground(this, true);
        KiberWifiServiceManager.setListener(this);
    }

    @Override
    protected void onPause() {
        KiberWifiServiceManager.setHostAppInForeground(this, false);
        KiberWifiServiceManager.setListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        KiberWifiServiceManager.stop(getApplicationContext());
    }

    @Override
    public void onKiberEvent(@NonNull KiberWifiServiceManager.KiberStatus status, @NonNull String message) {
        runOnUiThread(() -> statusText.setText(status.name() + " | " + message));
    }
}
