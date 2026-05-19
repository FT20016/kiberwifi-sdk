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

Per abilitare correttamente le dialog quando l'app è visibile:

```kotlin
KiberWifiServiceManager.setHostAppInForeground(this, true)  // onResume
KiberWifiServiceManager.setHostAppInForeground(this, false) // onPause
```

## Nota integrazione

Verifica che il progetto host non blocchi il manifest merge delle componenti SDK (service + activity interne).
