# Kiber WiFi SDK

Android SDK per connettere in modo affidabile app host a dispositivi KIBERSCOPE usando BLE discovery + Wi-Fi managed foreground service.

Include:
- modulo libreria `kiberwifi-sdk`
- documentazione in `docs/`
- `sample-host-app` pronta per test d'integrazione

## Repository Maven

```kotlin
repositories {
    maven { url = uri("https://maven.franco-tecchia.workers.dev") }
}
```

```kotlin
dependencies {
    implementation("com.kiber:kiberwifi-sdk:0.2.5")
}
```

Import pubblico consigliato:

```java
import com.kiber.kiberwifi.KiberWifiServiceManager;
```

## Quick Start (Java minimale)

```java
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KiberWifiServiceManager.start(this, "NT3XC", false);
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

    @Override
    protected void onDestroy() {
        KiberWifiServiceManager.stop(getApplicationContext());
        super.onDestroy();
    }
}
```

Con `autoConnect=false` la libreria continua a fare discovery BLE; la connessione Wi-Fi parte quando chiami `enableConnect(...)`.

## API principali

- `ensurePermissions(activity)`
- `start(activity, deviceSerial, autoConnect)`
- `startManaged(activity, contentText, connected)`
- `enableConnect(activity)` / `enableConnect(context)`
- `disableConnect(context)`
- `changeDeviceSerial(context, deviceSerial)`
- `stop(context)`
- `setListener(listener)`
- `setHostAppInForeground(context, inForeground)`
- `getStatus()`
- `isTargetPresent()`

## Documentazione

- Integrazione completa: [docs/integration.md](docs/integration.md)
- Eventi e stati: [docs/events.md](docs/events.md)
- Permessi: [docs/permissions.md](docs/permissions.md)
- Versioni: [CHANGELOG.md](CHANGELOG.md)

## Build locale

```bash
./gradlew :kiberwifi-sdk:assembleRelease
./gradlew :sample-host-app:assembleDebug
```

## Versioning e allineamento tag/Maven

Per mantenere GitHub e Maven allineati:

1. Aggiorna versione in `kiberwifi-sdk/build.gradle.kts`
2. Aggiorna `CHANGELOG.md`
3. Pubblica artefatto Maven (`com.kiber:kiberwifi-sdk:<version>`)
4. Crea tag Git uguale alla versione Maven:

```bash
git tag v0.2.5
git push origin main --tags
```

Regola consigliata: **ogni release Maven deve avere il tag Git corrispondente**.

