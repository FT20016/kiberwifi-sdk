# Integrazione SDK

## 1. Dipendenza

```kotlin
repositories {
    maven { url = uri("https://maven.franco-tecchia.workers.dev") }
}

dependencies {
    implementation("com.kiber:kiberwifi-sdk:0.2.5")
}
```

## 2. Setup Activity host

### In `onCreate`
- chiama `ensurePermissions(this)`
- inizializza il target device name (`XXXXX`)
- avvia il manager con `startManaged(...)` o `start(activity, deviceName, autoConnect)`

### In `onResume` / `onPause`
- `setHostAppInForeground(this, true/false)`
- `setListener(...)` / `setListener(null)`

Esempio:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.kiber.kiberwifi.KiberWifiServiceManager.ensurePermissions(this)
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

## 4. Cambio target device

Quando cambia seriale/device name usa direttamente:

```kotlin
com.kiber.kiberwifi.KiberWifiServiceManager.changeDeviceSerial(context, "NT3XC")
```

## 5. Shutdown app

In uscita app puoi chiamare:

```kotlin
com.kiber.kiberwifi.KiberWifiServiceManager.stop(applicationContext)
```

(Se vuoi mantenere comportamento background anche ad app chiusa, non chiamare `stop`.)
