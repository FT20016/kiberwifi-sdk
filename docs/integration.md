# Integrazione SDK

## 1. Dipendenza

```kotlin
repositories {
    maven { url = uri("https://maven.franco-tecchia.workers.dev") }
}

dependencies {
    implementation("com.kiber:kiberwifi-sdk:0.2.10")
}
```

## 2. Setup Activity host

### In `onCreate`
- opzionale: imposta lingua SDK con `setLanguage("en" | "it" | "de" | "fr" | "es" | "ru")`
- inizializza il seriale target (`XXXXX`, senza prefisso `KIBERSCOPE-`)
- avvia il manager con `start(activity, deviceSerial, autoConnect)`

Con `autoConnect=false`, lo SDK avvia comunque servizio e ricerca BLE. Non tenta pero' la connessione Wi-Fi finche' l'app host non chiama `enableConnect(activity)`.

Con `autoConnect=true`, lo SDK tenta la connessione Wi-Fi appena il target viene trovato via BLE.

### In `onResume` / `onPause`
- `setHostAppInForeground(this, true/false)`
- `setListener(...)` / `setListener(null)`

Esempio:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.kiber.kiberwifi.KiberWifiServiceManager.setLanguage("en")
    com.kiber.kiberwifi.KiberWifiServiceManager.start(this, "NT3XC", false)
}

override fun onResume() {
    super.onResume()
    com.kiber.kiberwifi.KiberWifiServiceManager.setHostAppInForeground(this, true)
    com.kiber.kiberwifi.KiberWifiServiceManager.setListener(this)
}

override fun onPause() {
    com.kiber.kiberwifi.KiberWifiServiceManager.setHostAppInForeground(this, false)
    com.kiber.kiberwifi.KiberWifiServiceManager.setListener(null)
    super.onPause()
}
```

## 3. Connect / Disconnect

```kotlin
com.kiber.kiberwifi.KiberWifiServiceManager.enableConnect(this)             // Connect abilitato
com.kiber.kiberwifi.KiberWifiServiceManager.disableConnect(applicationContext) // Disconnect + stop connect intent
```

`enableConnect(activity)` non riavvia il servizio: abilita l'intenzione di connettersi. Se il target BLE e' gia' presente, il collegamento Wi-Fi parte subito; altrimenti parte appena il prossimo scan trova il dispositivo.

Il prompt Bluetooth puo' comparire durante la ricerca BLE. Il prompt Wi-Fi viene mostrato solo quando lo SDK sta per entrare in `CONNECTING`.

## 4. Esempio Java minimale

```java
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.kiber.kiberwifi.KiberWifiServiceManager;

public class MainActivity extends AppCompatActivity {
    private static final String INITIAL_SERIAL = "NT3XC";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        KiberWifiServiceManager.setLanguage("it");

        // Starts foreground service + BLE discovery.
        // false = discovery only; connect later with enableConnect(...).
        KiberWifiServiceManager.start(this, INITIAL_SERIAL, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        KiberWifiServiceManager.setHostAppInForeground(this, true);
    }

    @Override
    protected void onPause() {
        KiberWifiServiceManager.setHostAppInForeground(this, false);
        super.onPause();
    }

    public void connectToKiber() {
        KiberWifiServiceManager.enableConnect(this);
    }

    public void disconnectFromKiber() {
        KiberWifiServiceManager.disableConnect(getApplicationContext());
    }

    public void changeDevice(String newSerial) {
        // Example: newSerial = "ABCDE".
        // SDK internally disconnects current Wi-Fi if needed,
        // updates the target, and clears learned BLE filters.
        KiberWifiServiceManager.changeDeviceSerial(
                getApplicationContext(),
                newSerial
        );
    }

    @Override
    protected void onDestroy() {
        KiberWifiServiceManager.stop(getApplicationContext());
        KiberWifiServiceManager.resetSessionState();
        super.onDestroy();
    }
}
```

`changeDeviceSerial(...)` sostituisce il vecchio flusso `stop + clearLearnedBleFilters + start`: la pulizia dei filtri BLE e' interna allo SDK.

## 5. Cambio target device

Quando cambia seriale:
1. `changeDeviceSerial(context, "ABCDE")`

Lo SDK disconnette eventuale connessione corrente, aggiorna il target e pulisce internamente i learned BLE filters. L'app host non deve chiamare funzioni di pulizia manuale.

## 6. Shutdown app

In uscita app puoi chiamare:

```kotlin
com.kiber.kiberwifi.KiberWifiServiceManager.stop(applicationContext)
com.kiber.kiberwifi.KiberWifiServiceManager.resetSessionState()
```

(Se vuoi mantenere comportamento background anche ad app chiusa, non chiamare `stop`.)

