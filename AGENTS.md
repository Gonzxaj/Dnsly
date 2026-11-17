# Guía para agentes (Claude, Devin, Codex, Cursor, etc.)

Estas reglas aplican a cualquier agente que trabaje en este repositorio. Son obligatorias.

## Proyecto

Dnsly: app Android (Kotlin + Rust) que activa un DNS propio (p. ej. Pi-hole) según ubicación y salud del servidor, sin root, mediante un `VpnService` local que solo enruta DNS.

- `PLAN.md`: requisitos y etapas.
- `ARCHITECTURE.md`: decisiones técnicas. Leerlo antes de cambiar diseño.
- `core/`: lógica en Rust (probe DNS, máquina de estados, forwarder, JNI).
- `android/`: app Kotlin, paquete `app.dnsly`.
- `docker/`: todo se compila y testea dentro de Docker. No instalar toolchains en el host.

## Verificación: solo tests

Verificar cada cambio únicamente con tests:

```bash
docker/test-core.sh                    # Rust: fmt --check, clippy -D warnings, cargo test
docker/gradle.sh testDebugUnitTest     # Android: unit tests
```

- **No** correr `docker/build-apk.sh` ni `assembleDebug` después de cada cambio.
- El APK se construye solo cuando el usuario lo pide o cuando una etapa está lista para probar en dispositivo. Aun así, **preguntar antes**.
- Al terminar una etapa: correr los tests, informar el resultado y detenerse.
- Si un test falla, decirlo con la salida. No ocultar fallos ni saltear tests.

## Código limpio

- Nombres claros que expliquen la intención. Sin abreviaturas inventadas.
- Funciones cortas, con una sola responsabilidad.
- Sin código muerto, duplicado ni "por si acaso". Si ya existe un helper, usarlo.
- Sin abstracciones, opciones ni configuración que nadie pidió.
- Respetar la estructura y convenciones existentes del código.
- Comentarios solo para explicar el *por qué*, no el *qué*.
- Manejo de errores explícito. En Rust, nada de `unwrap()` en código de producción.
- Toda lógica nueva no trivial lleva su test.
- Rust: el código debe pasar `cargo fmt` y `cargo clippy -- -D warnings` sin excepciones.
- Kotlin: seguir las Kotlin coding conventions oficiales.
- Preferir el cambio más chico que resuelva el problema completo.

## Git

- Commits en formato Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `test:`...).
- Commitear o pushear solo cuando el usuario lo pide.
- **Nunca** agregar a una IA como autor o colaborador: sin `Co-Authored-By`, sin "Generated with...", sin atribución de ningún tipo.
- Nunca commitear claves de firma (`*.jks`, `*.keystore`, `keystore.properties`).

## Idioma

Documentación y comunicación con el usuario en español. Código, identificadores y comentarios en inglés.
