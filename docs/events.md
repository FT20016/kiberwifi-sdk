# Eventi e Stati

## Registrazione callback

- Registra: `KiberWifiServiceManager.setListener(listener)`
- Deregistra: `KiberWifiServiceManager.setListener(null)`
- Firma callback: `onKiberEvent(KiberStatus status, String message)`

Consiglio lifecycle host app:
- `onResume`: registra listener
- `onPause`: deregistra listener

## `KiberStatus`

- `IDLING`: stato neutro / attesa
- `SCANNING`: ricerca BLE attiva (target assente)
- `MONITORING`: target noto, monitoraggio presenza
- `CONNECTING`: richiesta connessione Wi-Fi in corso
- `CONNECTED`: Wi-Fi connesso
- `DISCONNECTED`: scollegato
- `ERROR`: errore operativo

## Messaggi principali (`message` callback)

- `KIBER_SCANNING`
- `KIBER_MONITORING`
- `KIBER_TARGET_PRESENT`
- `KIBER_TARGET_ABSENT`
- `KIBER_CONNECTING`
- `KIBER_CONNECTED`
- `KIBER_DISCONNECTED`
- `KIBER_WIFI_OFF`
- `KIBER_BT_OFF`
- `KIBER_CONNECTION_REFUSED`
- `BLE_SCAN_FAILED_<code>`

## Mapping UI consigliato

- `SCANNING` -> arancione "Ricerca dispositivo..."
- `MONITORING` / `DISCONNECTED` -> rosso "Disconnesso"
- `CONNECTING` -> celeste "CONNECTING..."
- `CONNECTED` -> verde "Connesso"
- `KIBER_WIFI_OFF` / `KIBER_BT_OFF` -> warning rosso con testo specifico

## Note

- `KIBER_CONNECTION_REFUSED` viene anche gestito internamente con dialog dedicata.
- Le dialog radio (Wi-Fi/Bluetooth spenti) vengono triggerate dal manager quando host app e foreground.
