# Permessi e Runtime Behavior

## Permessi usati dalla libreria

- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `POST_NOTIFICATIONS`
- permessi rete/wifi/service necessari al foreground service

## Requisiti minimi Manifest host app

Il modulo SDK dichiara gia `service` e `activity` interne via manifest merge.
L'app host deve garantire almeno questi permessi:

```xml
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

Se l'host app sovrascrive la dichiarazione del service SDK, mantieni:

```xml
android:foregroundServiceType="connectedDevice"
```

## Come vengono richiesti

La libreria gestisce internamente:
- richiesta runtime permessi base
- richiesta posizione sempre attiva (background location)
- richiesta esclusione ottimizzazione batteria

Entry-point:

```kotlin
KiberWifiServiceManager.ensurePermissions(activity)
```

## Dialog runtime interne

- Permission Proxy dialog flow
- Connection refused dialog
- Radio state dialog (Wi-Fi/Bluetooth off)

## Foreground awareness

Per abilitare correttamente le dialog quando l'app e visibile:

```kotlin
KiberWifiServiceManager.setHostAppInForeground(this, true)  // onResume
KiberWifiServiceManager.setHostAppInForeground(this, false) // onPause
```

## Nota integrazione

Verifica che il progetto host non blocchi il manifest merge delle componenti SDK (service + activity interne).
