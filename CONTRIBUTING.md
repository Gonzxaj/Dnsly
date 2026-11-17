# Contributing

Gracias por tu interés en Dnsly. El proyecto es Kotlin + Rust y **todo el toolchain vive en Docker**: no instales Android SDK, NDK, Rust ni Gradle en tu máquina.

## Antes de mandar un PR

```bash
docker/test-core.sh                    # Rust: cargo fmt --check, clippy -D warnings, tests
docker/gradle.sh testDebugUnitTest     # Android: unit tests
```

Ambos tienen que pasar en verde. Leé [AGENTS.md](AGENTS.md) y [ARCHITECTURE.md](ARCHITECTURE.md) antes de cambiar diseño: las decisiones técnicas (VPN solo-DNS, engine puro, fail-open al DNS de red) no son negociables por conveniencia.

## Convenciones

- Commits en formato [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`...).
- Código e identificadores en inglés; documentación y comunicación en español.
- Funciones cortas, una responsabilidad, sin código muerto ni abstracciones especulativas.
- Toda lógica nueva no trivial lleva su test. En Rust: nada de `unwrap()` en producción, y `cargo fmt` + `clippy -D warnings` limpios.
- Nunca commitear claves de firma (`*.jks`, `*.keystore`, `keystore.properties`).

## Reportar bugs

Abrí un issue con: versión de Android, qué hacías, qué esperabas y qué pasó. Si podés, logs de `adb logcat -s Dnsly` ayudan un montón (los `Log.w` de error quedan en builds release).
