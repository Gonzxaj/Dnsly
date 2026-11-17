# Dnsly — Investigación técnica y arquitectura (Etapa 0)

Estado: propuesta. Los puntos marcados **[verificar en dispositivo]** no se pueden confirmar sin hardware real.

## 1. Cómo cambiar el DNS en Android sin root

| Opción | Viable | Motivo |
|---|---|---|
| API pública para fijar DNS del sistema | No | No existe. `LinkProperties` es de solo lectura para apps. |
| Private DNS (`Settings.Global.private_dns_*`) | No como vía principal | Solo acepta un hostname DoT (puerto 853), no una IP UDP. Escribirlo requiere `WRITE_SECURE_SETTINGS`, que solo se concede por `adb`. Pi-hole no habla DoT por defecto. |
| Cambiar DNS de la red Wi-Fi | No | `WifiConfiguration` con IP estática está bloqueado para apps desde Android 10. |
| **`VpnService` local "solo DNS"** | **Sí** | Única vía sin root. Patrón usado por DNS66, personalDNSfilter, RethinkDNS. |

**Decisión:** `VpnService` local en modo *split tunnel* que solo enruta DNS.

Dnsly **no** cambia el DNS global del sistema. Levanta una VPN local cuyo único propósito es capturar las consultas DNS y reenviarlas al servidor configurado. La UI debe decirlo así ("VPN local de DNS activa").

### Diseño de la VPN "solo DNS"

```
App ──consulta DNS──▶ 10.111.222.1:53 (IP ficticia, DNS de la VPN)
                         │ ruta /32 → interfaz TUN
                         ▼
                 Dnsly (Rust): lee paquete IP/UDP del TUN
                         │ socket propio (app excluida de la VPN)
                         ▼
                 Pi-hole 192.168.x.y:53 ──respuesta──▶ se escribe en el TUN ──▶ App
Resto del tráfico: no coincide con ninguna ruta de la VPN → sale por la red normal.
```

- `Builder.addAddress(10.111.222.2/32)` + `addDnsServer(10.111.222.1)` + `addRoute(10.111.222.1/32)`.
- Equivalente IPv6 (`fd00:…`) para que redes solo-IPv6 o dual-stack no resuelvan por fuera.
- `Builder.addDisallowedApplication(packageName)`: los sockets de Dnsly (sondas y reenvío) no pasan por el TUN. Evita llamar `protect()` por socket desde Rust.
- Sin `addRoute(0.0.0.0/0)`: no se toca el tráfico normal, consumo mínimo.

## 2. Restricciones de Android y cómo se manejan

### Una sola VPN a la vez
- Android admite una única VPN activa. Activar Dnsly desplaza a otra VPN (WireGuard, Tailscale, etc.) y viceversa.
- Si otra app toma la VPN, el sistema llama `onRevoke()`. Dnsly pasa a `VPN_ERROR` y **no reintenta** solo: reintentar provocaría una pelea de VPNs.
- Si otra VPN está configurada como *always-on* / *lockdown*, `establish()` falla → `VPN_ERROR`.
- `VpnService.prepare()` requiere consentimiento del usuario una vez; si otra app lo revoca, hay que volver a pedirlo desde la UI.

### Private DNS (DoT) del sistema
- Modo *estricto* (hostname configurado): el resolvedor del sistema usa DoT hacia ese hostname y **ignora** el DNS de la VPN. Dnsly no puede evitarlo. Detectar con `LinkProperties.getPrivateDnsServerName()` / `isPrivateDnsActive()` y avisar en el dashboard.
- Modo *automático*: el sistema intenta DoT (puerto 853) contra 10.111.222.1. Ese paquete entra al TUN, se descarta y el sistema cae a DNS plano. Sin acción extra.

### Apps con DNS propio
Chrome/Firefox con "DNS seguro" (DoH), o apps con IPs de DNS fijas, no pasan por el DNS del sistema. Es inherente a una VPN "solo DNS"; se documenta, no se intenta resolver (requeriría túnel completo).

### Ejecución en segundo plano
- **Fuera del área:** no hay servicio ni proceso vivo. Geofencing (Google Play Services) despierta la app con un `PendingIntent` broadcast. Recibir una transición de geofence es una excepción oficial que permite arrancar un *foreground service* (FGS) desde segundo plano.
- **Dentro del área:** un único servicio `DnslyService : VpnService` corre como FGS con notificación persistente.
  - Android 14+ exige tipo de FGS. El ejemplo oficial ToyVpn de AOSP usa `specialUse` con subtipo `vpn`; se adopta eso. La app se distribuye por GitHub, no por Play Store: no aplica la revisión de `specialUse`.
  - Android 15 prohíbe arrancar ciertos tipos de FGS desde `BOOT_COMPLETED` (`dataSync`, `camera`, `mediaPlayback`, …); `specialUse` no está en esa lista.
- **Doze:** los timers se retrasan mientras la CPU duerme. Mitigaciones:
  1. Detección pasiva: el reenviador informa timeouts de consultas reales → sonda inmediata. Si el usuario usa el teléfono, hay tráfico y la caída se detecta sin depender de timers.
  2. `NetworkCallback` dispara una sonda en cada cambio de red.
  3. Se asume que un proceso con FGS conserva acceso a red en Doze. **[verificar en dispositivo]**
- No se asume ejecución indefinida: si Android mata el proceso, el sistema cierra el TUN y el DNS vuelve al de la red (fallo seguro). `START_STICKY` + re-evaluación del estado al reiniciar.

### Red local (Android 17)
Apuntar `targetSdk` a 37 exige el nuevo permiso de red local en tiempo de ejecución para conectarse a IPs de la LAN (el Pi-hole). v1 usa `targetSdk = 36`, igual que go-data-android. Revisar al subir a 37.

### Geofencing
- `GeofencingClient` (Play Services). Requiere `ACCESS_FINE_LOCATION` y `ACCESS_BACKGROUND_LOCATION` (Android 10+, pedido por separado; en Android 11+ el usuario debe elegir "Permitir siempre" en Ajustes).
- Las geofences se borran al reiniciar, al borrar datos de la app y al desactivar la ubicación. Se re-registran en `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` y al abrir la app.
- `INITIAL_TRIGGER_ENTER` entrega el estado actual al registrar (sirve tras reinicio).
- Latencia típica de transición: 1–3 min.
- **Decisión v1:** `GeofencingClient` de Play Services (el más eficiente en batería; presente en casi todos los teléfonos). Sin Play Services, la app avisa y el usuario puede desactivar la condición geográfica. Alternativa futura: `LocationManager.addProximityAlert` (AOSP, más batería).

### Selección de ubicación (decidido)
- **Sin mapa.** El usuario toma su ubicación actual con un botón y configura el radio (por defecto 50 m). Se muestran coordenadas y radio.
- Sin tiles ni servicios de mapas: no se descarga nada de terceros.
- Con 50 m la transición es menos confiable: Google recomienda ≥100–150 m y el error de ubicación en interiores puede ser de 20–50 m. El radio queda configurable. **[verificar en dispositivo]**

## 3. Estrategia contra fugas de DNS

Definición de fuga: una consulta que sale hacia un DNS distinto del configurado **mientras Dnsly muestra `DNS_ACTIVE`**.

| Riesgo | Mitigación |
|---|---|
| Resolución por IPv6 fuera del túnel | DNS ficticio IPv6 + ruta /128 al TUN. |
| Private DNS estricto | Detectar y avisar. No se puede forzar. |
| Consulta de Dnsly a sí misma en bucle | App excluida de su propia VPN. |
| Respuesta truncada (TC) → reintento TCP al DNS ficticio | Limitación conocida: el TUN no implementa TCP. Raro en uso normal (registros grandes). Mitigación futura: el reenviador reintenta por TCP aguas arriba y devuelve la respuesta completa por UDP. |
| Apps con DoH propio | Fuera de alcance, documentado. |

**Política de fallo: fail-open.** Si el servidor cae, la VPN se cierra y Android vuelve al DNS de la red. Esto es una "fuga" deliberada: el requisito 7 exige no dejar al dispositivo sin DNS. No hay *kill switch* en v1.

**Ventana sin DNS cuando el servidor cae con la VPN activa:** las consultas expiran hasta alcanzar el umbral de fallos. Peor caso ≈ `fail_threshold × (timeout + retry_delay)`. Con valores por defecto (3 × (2 s + 2 s)) ≈ 12 s. La detección pasiva acorta el inicio de esa ventana.

**Cambio Wi-Fi → datos móviles dentro del área:** si el Pi-hole tiene IP de LAN, deja de ser alcanzable. Las sondas fallan, se cierra la VPN y se vuelve al DNS del operador. Al volver al Wi-Fi, `NetworkCallback` dispara una sonda inmediata y se reactiva.

## 4. Arquitectura Kotlin / Rust

```
┌────────────────────────── Kotlin (Android) ───────────────────────────┐
│ UI (Compose)  │ DataStore config │ Room historial │ Notificaciones    │
│ GeofenceManager/Receiver │ NetworkMonitor │ BootReceiver              │
│ DnslyService (VpnService + FGS)                                      │
│ EngineDriver: traduce eventos Android → Engine; ejecuta acciones      │
│               (StartVpn, StopVpn, timers de sonda)                    │
└───────────────────────────────┬───────────────────────────────────────┘
                                │ JNI (superficie mínima)
┌───────────────────────────────▼──────── Rust (dnsly-core) ──────────┐
│ engine:    máquina de estados pura (eventos → acciones), sin E/S      │
│ probe:     sonda DNS UDP/TCP con timeout                              │
│ dns:       construcción/validación de mensajes DNS                    │
│ forwarder: (Etapa 3) bucle TUN: parseo IPv4/IPv6/UDP, reenvío, reloj  │
└───────────────────────────────────────────────────────────────────────┘
```

Por qué esta división:
- El motor es **puro**: no tiene reloj ni sockets. Devuelve acciones como "programar sonda en N ms". Se prueba de forma determinista en el host, sin Android.
- Rust aporta valor real en el camino de paquetes (parseo IP/UDP del TUN, sin GC, sin copias innecesarias) y en la validación DNS.
- Timers, permisos, servicios y ubicación quedan en Kotlin porque dependen del ciclo de vida de Android.
- Sin dependencias externas en el core (solo `std`) para la Etapa 1. JNI usará el crate `jni` en la Etapa 2.

### Superficie JNI (Etapa 2–4)
- `engineNew(failThreshold, recoverThreshold, checkIntervalMs, retryDelayMs, backoffBaseMs, backoffMaxMs) -> handle`,
  `engineHandle(handle, event, p1, p2) -> acciones`, `engineState(handle) -> estado`, `engineFree(handle)`.
  Eventos y acciones cruzan como strings (`probe_done`, `schedule_probe:<delay>:<gen>`); ver `decode_event`/`encode_actions` en `core/src/jni.rs` y `EngineActions.kt`.
- `probe(host, port, tcp, domain, timeoutMs) -> resultado`.
- `forwarderStart(tunFd, upstream…, listener)` bloqueante en un hilo de Kotlin; se detiene cerrando el fd.
  `listener.onQueryTimeout()` es la señal pasiva de queries expiradas (`QueryTimeout`).

## 5. Máquina de estados

Estados: `DISABLED`, `OUTSIDE_GEOFENCE`, `CHECKING_SERVER`, `DNS_ACTIVE`, `SERVER_UNAVAILABLE`, `VPN_ERROR`.

`SERVER_UNAVAILABLE` y `WAITING_RECOVERY` del plan se fusionan: ambos significan "dentro del área, servidor caído, sondeando con backoff". Tener dos estados con el mismo comportamiento solo agrega transiciones. El dashboard muestra "Servidor no disponible — reintentando en X".

```
            SetEnabled(false) desde cualquier estado ──▶ DISABLED
DISABLED ──SetEnabled(true)──▶ (fuera) OUTSIDE_GEOFENCE / (dentro) CHECKING_SERVER
OUTSIDE ──Geofence(dentro)──▶ CHECKING_SERVER
CHECKING ──1 éxito──▶ DNS_ACTIVE [StartVpn]
CHECKING ──fail_threshold fallos──▶ SERVER_UNAVAILABLE
DNS_ACTIVE ──fail_threshold fallos consecutivos──▶ SERVER_UNAVAILABLE [StopVpn]
SERVER_UNAVAILABLE ──recover_threshold éxitos consecutivos──▶ DNS_ACTIVE [StartVpn]
cualquiera dentro ──Geofence(fuera)──▶ OUTSIDE_GEOFENCE [StopVpn]
cualquiera ──VpnFailed / onRevoke──▶ VPN_ERROR [StopVpn] (sin reintento automático)
VPN_ERROR ──RetryVpn / salir y volver al área / re-habilitar──▶ re-evaluación
```

Anti-oscilación:
- Desactivar exige `fail_threshold` fallos **consecutivos**; un éxito reinicia el contador.
- Reactivar exige `recover_threshold` éxitos consecutivos (un Pi-hole que arranca puede responder de forma intermitente).
- Sondas de recuperación con backoff exponencial acotado (`backoff_base` → `backoff_max`).
- Cada sonda programada lleva un número de generación; resultados viejos se ignoran.
- Sin red: se suspenden las sondas y no se cuentan fallos. Al volver la red: sonda inmediata, contadores a cero.
- El estado no se persiste: se re-deriva al arrancar a partir de (habilitado, dentro del área, red). Así un reinicio o la muerte del proceso no deja estados inconsistentes.

## 6. Qué pasa si…

| Situación | Comportamiento |
|---|---|
| Android mata el proceso | El sistema cierra el TUN → DNS normal. `START_STICKY` relanza y re-evalúa. |
| Reinicio del dispositivo | `BOOT_COMPLETED` re-registra geofences; `INITIAL_TRIGGER_ENTER` informa si está dentro. |
| Usuario revoca ubicación | Las geofences dejan de funcionar → se trata como fuera del área (fail-open) + notificación. |
| Usuario revoca consentimiento VPN / otra VPN la toma | `onRevoke` → `VPN_ERROR`, notificación, sin reintento. |
| Usuario revoca notificaciones (13+) | Sigue funcionando; el FGS no muestra notificación visible. |
| Sin red | Sondas suspendidas, estado congelado. |

## 7. Estructura de carpetas

```
Dnsly/
├── PLAN.md
├── ARCHITECTURE.md
├── core/                         Rust: crate dnsly-core
│   ├── Cargo.toml
│   ├── src/
│   │   ├── lib.rs
│   │   ├── dns.rs                mensaje DNS: query + validación
│   │   ├── probe.rs              sondas UDP/TCP
│   │   ├── engine.rs             máquina de estados pura
│   │   ├── forwarder.rs          (Etapa 3)
│   │   └── jni.rs                (Etapa 2)
│   └── tests/probe.rs            sondas contra servidores DNS falsos locales
└── android/                      (Etapa 2) proyecto Gradle
    └── app/src/main/java/app/dnsly/
        ├── ui/                   dashboard, dns, ubicación, automatización, historial
        ├── service/              DnslyService, BootReceiver
        ├── geo/                  GeofenceManager, GeofenceReceiver
        ├── net/                  NetworkMonitor
        ├── data/                 DataStore, Room
        └── core/                 NativeCore (JNI), EngineDriver
```

## 8. Plan por etapas

| # | Etapa | Verificación |
|---|---|---|
| 0 | Investigación y arquitectura (este documento) | Revisión del usuario |
| 1 ✅ | Core Rust: `dns`, `probe`, `engine` | `docker/test-core.sh`: fmt, clippy, tests (sondas contra servidores falsos, todas las transiciones) |
| 2 ✅ | Proyecto Android + JNI (cargo-ndk), dashboard mínimo con botón "probar servidor" | APK en dispositivo; sonda real contra Pi-hole (UDP y TCP NOERROR; IP falsa → timeout) |
| 3 🟡 | VPN solo-DNS + reenviador Rust; encendido manual; detección de Private DNS estricto + aviso en el dashboard | Tests ✅ (parseo IPv4/IPv6, checksums, loop completo con TUN simulado). Falta en dispositivo: navegación OK con la VPN; logs de Pi-hole muestran el teléfono; con Private DNS estricto aparece el aviso |
| 4 🟡 | EngineDriver: FGS, timers, `NetworkCallback`, notificaciones, boot | Tests ✅ (codec JNI, callback de timeouts). Falta en dispositivo: apagar Pi-hole → VPN se cierra; encender → se reactiva; reinicio del teléfono |
| 5 🟡 | Geofencing + ubicación actual + radio + permisos | Tests ✅ (`effectiveInside`). Falta en dispositivo: entrar/salir del área (o mock location); transición con app cerrada; permiso "siempre" |
| 6 | Conectar la UI a datos reales: dashboard completo (PLAN §5: estado, servidor, área, VPN, última/próxima comprobación), configuración, automatización, historial (Room) | Persistencia tras reinicio; el dashboard refleja cada estado del motor |
| UI ✅ | Maqueta de todas las pantallas con datos de ejemplo (`ui/Model.kt` → `Demo`), según `docs/ui-reference.png` | Revisión visual aprobada. Las etapas 4–6 reemplazan `Demo` por datos reales |
| 7 | Matriz de pruebas del plan §8 en dispositivo + endurecimiento | Checklist completo |

**Build:** todo corre en Docker (`docker/`), sin SDK local. Misma base que go-data-android: Gradle 9.9.1 + JDK 21, AGP 9.5.1, Kotlin 2.4.21, `compileSdk` 37. Se agregan NDK 30.0.16248370, Rust 1.99.1 y cargo-ndk 4.1.2.
- `docker/test-core.sh`: fmt + clippy + tests del core.
- `docker/gradle.sh testDebugUnitTest`: tests de Kotlin. No compila Rust para Android: `cargoBuild` solo corre al empaquetar las `.so`.
- `docker/build-apk.sh`: tests + `dist/dnsly.apk`. Solo para versiones entregables.
- `docker/install-apk.sh`: instala por USB.
- La imagen comparte con go-data-android las capas de JDK + SDK (prefijo idéntico del Dockerfile) y el volumen de caché de Gradle `go-data-android-gradle`. Sin caché de Cargo. Tras cada build de imagen se borran versiones viejas de la imagen.

## 9. Decisiones del usuario

1. Ubicación: sin mapa. Ubicación actual + radio (50 m por defecto).
2. Play Services: sin preferencia → v1 usa `GeofencingClient` (ver §2).
3. Automatización: "desactivar" siempre significa quitar el DNS de Dnsly y volver al DNS normal de la red. Opciones v1: condición geográfica on/off (con radio) y reactivación automática on/off. La desactivación por servidor caído no es configurable: mantener la VPN con el servidor caído dejaría al teléfono sin DNS.
4. Distribución: GitHub, sin Play Store.
5. Nombre: **Dnsly** (paquete `app.dnsly`, crate `dnsly-core`).
6. UI: diseño de referencia `docs/ui-reference.png`, tema oscuro único. Del diseño quedan afuera: el mapa (decisión 1), la pantalla de estadísticas, el widget "Estado rápido" y el "protocolo alternativo" (no están en el plan).
