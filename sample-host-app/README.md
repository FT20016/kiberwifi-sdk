# Sample Host App

Questo modulo mostra l'integrazione minima con `KiberWifiServiceManager`.

La sample usa `start(activity, deviceSerial, false)`: il servizio parte e cerca il dispositivo via BLE, ma la connessione Wi-Fi viene richiesta solo quando premi Connect (`enableConnect(activity)`).

## Run

- seleziona modulo `sample-host-app`
- esegui su device Android

## Note

La sample usa `implementation(project(":kiberwifi-sdk"))` per sviluppo locale.
Per testare il package remoto, sostituisci la dependency in `sample-host-app/build.gradle.kts` con:

```kotlin
implementation("com.kiber:kiberwifi-sdk:0.2.10")
```

