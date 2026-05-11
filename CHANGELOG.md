# Changelog

## 0.2.5
- Aggiunta facade compatibile nel package pubblico `com.kiber.kiberwifi.KiberWifiServiceManager`.
- Migliorata logica di no-op inter-app: se un altro APK ha gia' il manager attivo, l'host non avvia/ferma nulla e non crea notifiche aggiuntive.

## 0.2.4
- Aggiunto lock inter-app nel `KiberWifiServiceManager`:
  - se un altro APK ha gia' il manager attivo, le chiamate `start/enableConnect/disableConnect/changeDeviceSerial` diventano no-op.
  - emesso evento `KIBER_EXTERNAL_MANAGER_ACTIVE` lato listener.

## 0.2.3
- Aggiornamenti funzionali introdotti il 5 maggio 2026.
- Versione allineata per pubblicazione su repository Maven Cloudflare.

## 0.2.0
- Pubblicata prima versione SDK reale (`com.kiber:kiberwifi-sdk`) con:
  - `KiberWifiServiceManager`
  - gestione permessi interna
  - gestione dialog interna (permessi, radio state, connection refused)
  - stato `CONNECTING`
  - beep helper integrato
- Packaging come libreria Android AAR consumabile da Maven repository.

## 0.1.0
- Versione tecnica di test pubblicazione Maven (placeholder `KiberSdk`).
