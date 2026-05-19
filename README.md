# Kiber WiFi SDK

SDK Android per gestione BLE + connessione Wi-Fi KIBERSCOPE tramite foreground service.

## Installazione

```kotlin
repositories {
    maven { url = uri("https://maven.franco-tecchia.workers.dev") }
}

dependencies {
    implementation("com.kiber:kiberwifi-sdk:0.2.7")
}
```

## Quick Start

```kotlin
class MainActivity : AppCompatActivity(), com.kiber.kiberwifi.KiberWifiServiceManager.KiberEventListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Richiede permessi necessari (runtime + battery optimization)
        com.kiber.kiberwifi.KiberWifiServiceManager.ensurePermissions(this)

        // Avvia manager (service) quando hai un target valido
        com.kiber.kiberwifi.KiberWifiServiceManager.startManaged(
            this,
            "Autoconnect attivo in background",
            false
        )
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

    fun onConnectClick() {
        com.kiber.kiberwifi.KiberWifiServiceManager.enableConnect(this)
    }

    fun onDisconnectClick() {
        com.kiber.kiberwifi.KiberWifiServiceManager.disableConnect(applicationContext)
    }

    override fun onKiberEvent(
        status: com.kiber.kiberwifi.KiberWifiServiceManager.KiberStatus,
        message: String
    ) {
        // Aggiorna UI in base a status/message
    }
}
```

## API principali

- `ensurePermissions(activity)`
- `setLanguage(languageCode)` (`en`, `it`, `de`, `fr`, `es`, `ru`)
- `start(activity, deviceName, autoConnect)`
- `startManaged(activity, contentText, connected)`
- `enableConnect(activity)` / `enableConnect(context)`
- `disableConnect(context)`
- `stop(context)`
- `resetSessionState()`
- `setListener(listener)`
- `setHostAppInForeground(context, inForeground)`
- `getStatus()`
- `isTargetPresent()`
- `clearLearnedBleFilters(context)`

## Stati

`IDLING`, `SCANNING`, `MONITORING`, `CONNECTING`, `CONNECTED`, `DISCONNECTED`, `ERROR`

Dettagli eventi e messaggi in [docs/events.md](docs/events.md).

## Documentazione

- Integrazione completa: [docs/integration.md](docs/integration.md)
- Eventi e stati: [docs/events.md](docs/events.md)
- Permessi e comportamento runtime: [docs/permissions.md](docs/permissions.md)
- Versioning: [CHANGELOG.md](CHANGELOG.md)
