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

        // Starts the foreground manager and BLE discovery for serial NT3XC.
        // autoConnect=false means: discover the device, but do not connect
        // to Wi-Fi until enableConnect(...) is called.
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
        // If the target was already found by BLE, Wi-Fi connection starts immediately.
        // Otherwise the SDK connects as soon as the next BLE scan finds it.
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

Per un esempio Java minimale con `changeDeviceSerial(...)`, vedi [docs/integration.md](docs/integration.md#4-esempio-java-minimale).

## API principali

- `setLanguage(languageCode)` (`en`, `it`, `de`, `fr`, `es`, `ru`)
- `start(activity, deviceSerial, autoConnect)`
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

## Logica runtime

`start(activity, deviceSerial, false)` avvia il servizio e la ricerca BLE del dispositivo, ma non forza subito il Wi-Fi. Il prompt Bluetooth puo' comparire durante la scansione perche' BLE richiede Bluetooth attivo.

Il prompt Wi-Fi compare solo quando il dispositivo e' stato trovato e parte davvero la fase di connessione, cioe' dopo `enableConnect(activity)` oppure usando `start(activity, deviceSerial, true)`.

## Documentazione

- Integrazione completa: [docs/integration.md](docs/integration.md)
- Eventi e stati: [docs/events.md](docs/events.md)
- Permessi e comportamento runtime: [docs/permissions.md](docs/permissions.md)
- Versioning: [CHANGELOG.md](CHANGELOG.md)
