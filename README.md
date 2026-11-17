# Dnsly

App Android que usa tu propio servidor DNS (por ejemplo un Pi-hole en casa) **solo cuando tiene sentido**: dentro del área configurada y con el servidor respondiendo. Cuando salís del área o el servidor cae, vuelve automáticamente al DNS de la red — sin root, sin servicios externos y sin fugas de DNS.

## Cómo funciona

```
Dentro del área + servidor OK  →  VPN local solo-DNS → tus consultas van a tu servidor
Fuera del área / servidor caído → VPN cerrada → Android usa el DNS de la red
```

- **Engine en Rust** (`core/`): máquina de estados pura — sonda el servidor DNS, decide cuándo activar/desactivar, con umbrales de fallos consecutivos, backoff de recuperación y reactivación automática opcional.
- **VPN local** (`VpnService`): enruta únicamente las direcciones DNS virtuales al forwarder Rust, que reenvía las consultas UDP/53 al servidor configurado. El resto del tráfico nunca toca la VPN.
- **Geofencing**: Play Services despierta la app en transiciones ENTER/EXIT del área (funciona con la app cerrada y la pantalla apagada).
- **Fail-safe**: cualquier condición incumplida (servidor caído, fuera del área, VPN revocada, permisos quitados) devuelve el teléfono al DNS de la red. Nunca quedás sin DNS.

## Funciones

- Activación/desactivación manual y automática, persistente entre reinicios.
- Configuración del servidor: IP o hostname, puerto, UDP/TCP, dominio y timeout de prueba, intervalo y fallos consecutivos.
- Área geográfica con "usar mi ubicación actual" y radio configurable.
- Historial persistente (Room) de eventos relevantes: servidor caído/recuperado, entrada/salida del área, error de VPN.
- Dashboard en vivo: estado, RTT promedio, última y próxima comprobación.
- Aviso cuando DNS privado estricto está activo (ignora la VPN).

## Requisitos

- Android 8.0+ (minSdk 26), compilado con targetSdk 36.
- Permisos: ubicación (incl. segundo plano para geofencing), VPN, notificaciones.

## Desarrollo

Todo se compila y testea dentro de Docker — no hace falta instalar toolchains en el host:

```bash
docker/test-core.sh                    # Rust: fmt, clippy -D warnings, cargo test
docker/gradle.sh testDebugUnitTest     # Android: unit tests
docker/build-apk.sh                    # tests + APK release → dist/dnsly.apk
docker/install-apk.sh [ip:puerto]      # instala por USB, o por Wi-Fi debug con argumento
```

Estructura:

```
core/       Rust: probe DNS, forwarder TUN, máquina de estados, JNI
android/    App Kotlin + Jetpack Compose (paquete app.dnsly)
docker/     Imagen y scripts de build/test
```

Ver [ARCHITECTURE.md](ARCHITECTURE.md) para las decisiones técnicas y [PLAN.md](PLAN.md) para requisitos y etapas. [AGENTS.md](AGENTS.md) tiene las reglas del repo para agentes/contribuidores.

## Licencia

MIT — ver [LICENSE](LICENSE).
