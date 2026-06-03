# Permessi e Runtime Behavior

## Permessi usati dalla libreria

- `ACCESS_FINE_LOCATION`
- `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `POST_NOTIFICATIONS`
- permessi rete/wifi/service necessari al foreground service

## Come vengono richiesti

La libreria verifica i permessi necessari quando chiami `start(activity, deviceSerial, autoConnect)` e gestisce internamente il flusso runtime richiesto per BLE, Wi-Fi e foreground service.

Non esiste piu' un entry-point separato `ensurePermissions(...)`.

## Dialog runtime interne

- Permission Proxy dialog flow
- Connection refused dialog
- Radio state dialog (Wi-Fi/Bluetooth off)

## Foreground awareness

Per abilitare correttamente le dialog quando l'app è visibile:

```kotlin
KiberWifiServiceManager.setHostAppInForeground(this, true)  // onResume
KiberWifiServiceManager.setHostAppInForeground(this, false) // onPause
```

## Nota integrazione

Verifica che il progetto host non blocchi il manifest merge delle componenti SDK (service + activity interne).

