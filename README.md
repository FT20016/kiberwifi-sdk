# Kiber WiFi SDK

SDK Android per gestione BLE + connessione Wi-Fi KIBERSCOPE tramite foreground service.

## Installazione

```kotlin
repositories {
    maven { url = uri("https://maven.franco-tecchia.workers.dev") }
}

dependencies {
    implementation("com.kiber:kiberwifi-sdk:0.2.9")
}
```

## Quick Start

```kotlin
class MainActivity : AppCompatActivity(), com.kiber.kiberwifi.KiberWifiServiceManager.KiberEventListener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Avvio SDK: target seriale + autoConnect
        com.kiber.kiberwifi.KiberWifiServiceManager.start(
            this,
            "NT3XC",
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

- `setLanguage(languageCode)` (`en`, `it`, `de`, `fr`, `es`, `ru`)
- `start(activity, deviceName, autoConnect)`
- `changeDeviceSerial(context, deviceSerial)`
- `enableConnect(activity)`
- `disableConnect(context)`
- `stop(context)`
- `resetSessionState()`
- `setListener(listener)`
- `setHostAppInForeground(context, inForeground)`
- `getStatus()`
- `isTargetPresent()`

## Stati

`IDLING`, `SCANNING`, `MONITORING`, `CONNECTING`, `CONNECTED`, `DISCONNECTED`, `ERROR`

Dettagli eventi e messaggi in [docs/events.md](docs/events.md).

## Documentazione

- Integrazione completa: [docs/integration.md](docs/integration.md)
- Eventi e stati: [docs/events.md](docs/events.md)
- Permessi e comportamento runtime: [docs/permissions.md](docs/permissions.md)
- Versioning: [CHANGELOG.md](CHANGELOG.md)
