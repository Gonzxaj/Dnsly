# Proyecto: Dnsly — Smart DNS Management for Android

## Objetivo

Desarrollar una aplicación nativa para Android llamada **Dnsly**, utilizando **Kotlin + Rust**, que permita administrar automáticamente el DNS utilizado por el dispositivo según la ubicación geográfica del usuario y la disponibilidad del servidor DNS configurado.

La aplicación debe funcionar sin root, sin depender de servicios VPN externos y con el menor consumo posible de batería y recursos.

La idea es que el usuario pueda configurar su propio servidor DNS (por ejemplo, un Pi-hole alojado en su servidor doméstico) y que Android lo utilice automáticamente cuando se cumplan determinadas condiciones.

## 1. Funcionamiento principal

La aplicación debe implementar un sistema de activación y desactivación automática del DNS personalizado.

El DNS personalizado solamente debe estar activo cuando se cumplan estas condiciones:

### A. Ubicación geográfica

- El usuario puede definir una ubicación, por ejemplo, su domicilio.
- Debe poder seleccionar la ubicación en un mapa.
- Debe poder configurar un radio de cobertura (100, 200, 500 metros, etc.).
- Cuando el dispositivo ingrese en esa área, se habilita la evaluación de las demás condiciones.
- Cuando salga del área, se desactiva el DNS personalizado.

Utilizar Android Geofencing API cuando sea conveniente, evitando mantener el GPS activo constantemente.

### B. Disponibilidad del servidor DNS

La aplicación debe comprobar que el servidor DNS configurado realmente está funcionando.

No alcanza con realizar un ping a la IP.

Implementar comprobaciones reales mediante consultas DNS:

- Consultas DNS UDP al puerto 53.
- Comprobación de respuestas válidas.
- Soporte opcional para TCP.
- Posibilidad de configurar un dominio de prueba.
- Timeout configurable.
- Cantidad configurable de fallos consecutivos antes de considerar que el servidor está caído.

Si el servidor deja de responder, se debe desactivar automáticamente el DNS personalizado.

Esto es importante porque el servidor puede apagarse por un corte eléctrico, perder conectividad o dejar de ejecutar Pi-hole.

### C. Recuperación automática

Cuando el servidor vuelva a estar disponible:

- Comprobar nuevamente su disponibilidad.
- Verificar que el dispositivo continúa dentro del área configurada.
- Reactivar automáticamente el DNS personalizado si todas las condiciones vuelven a cumplirse.

No debe requerir intervención manual.

## 2. Gestión del DNS en Android

Investigar e implementar la mejor solución disponible para administrar DNS sin root.

Evaluar especialmente:

- Android VpnService.
- VPN local que intercepte las consultas DNS.
- Configuración de DNS UDP/TCP.
- Compatibilidad con diferentes versiones de Android.
- Interacción con otras VPN instaladas.
- Comportamiento ante cambios entre Wi-Fi y datos móviles.
- Restricciones de Android sobre DNS privado y DNS del sistema.

La aplicación no debe afirmar que cambia el DNS global del sistema si técnicamente está utilizando una VPN local para interceptar y resolver consultas.

El comportamiento esperado es que el dispositivo utilice el DNS personalizado mientras Dnsly esté activo y vuelva al funcionamiento DNS habitual cuando se desactive.

**Importante:** estudiar cuidadosamente cómo evitar fugas de DNS y cómo manejar los casos en los que el servidor deja de responder mientras la VPN está activa.

## 3. Arquitectura tecnológica

### Kotlin — Android

Responsable de:

- Interfaz de usuario.
- Configuración de servidores DNS.
- Gestión de permisos.
- Ubicación y geofencing.
- Android VpnService.
- Notificaciones.
- Ciclo de vida de la aplicación.
- Gestión de conectividad.
- Persistencia de configuración.
- Integración con Rust mediante JNI.

### Rust — Core Engine

Implementar un motor independiente para:

- Monitorización de disponibilidad DNS.
- Envío y validación de consultas DNS.
- Gestión de timeouts y reintentos.
- Máquina de estados.
- Evaluación de condiciones de activación.
- Detección de fallos.
- Recuperación automática.

Utilizar JNI para comunicar Kotlin y Rust.

Evitar introducir complejidad innecesaria. Rust debe encargarse de la lógica donde realmente aporte valor, mientras que las integraciones específicas de Android deben permanecer en Kotlin.

## 4. Máquina de estados

Diseñar una máquina de estados clara y robusta.

Estados sugeridos:

- DISABLED: funcionalidad desactivada por el usuario.
- OUTSIDE_GEOFENCE: fuera del área configurada.
- CHECKING_SERVER: verificando disponibilidad.
- DNS_ACTIVE: DNS personalizado activo.
- SERVER_UNAVAILABLE: servidor DNS no disponible.
- WAITING_RECOVERY: esperando recuperación del servidor.
- VPN_ERROR: error al iniciar o mantener el servicio VPN.

Las transiciones deben ser deterministas y evitar activaciones o desactivaciones repetitivas.

También debe existir una opción para activar o desactivar manualmente la automatización.

## 5. Interfaz de usuario

Diseñar una interfaz Android moderna, minimalista y profesional.

La aplicación debe incluir:

### Dashboard

Mostrar claramente:

- Estado actual de Dnsly.
- DNS configurado.
- IP del servidor.
- Disponibilidad del servidor.
- Estado de ubicación.
- Si el dispositivo está dentro o fuera del área.
- Estado de la VPN local.
- Última comprobación.
- Próxima comprobación.

### Configuración DNS

Permitir configurar:

- Nombre del servidor.
- Dirección IP o hostname.
- Puerto.
- Protocolo.
- Dominio de prueba.
- Timeout.
- Intervalo de comprobación.
- Cantidad de fallos consecutivos permitidos.

### Ubicación

Permitir:

- Seleccionar ubicación en mapa.
- Configurar radio geográfico.
- Activar o desactivar la condición geográfica.
- Visualizar la ubicación configurada.

### Automatización

Permitir configurar:

- Activación automática.
- Desactivación al salir del área.
- Desactivación cuando el servidor no responda.
- Reactivación automática al recuperarse.
- Intervalos y políticas de reintento.

### Historial

Registrar eventos relevantes:

- DNS activado.
- DNS desactivado.
- Servidor no disponible.
- Servidor recuperado.
- Entrada o salida de geofence.
- Errores de conexión.
- Errores de VPN.

## 6. Requisitos de funcionamiento

- No requerir root.
- No depender de servidores externos.
- No enviar información personal a terceros.
- Funcionamiento local.
- Bajo consumo de batería.
- Persistencia de configuración.
- Recuperación ante reinicios del dispositivo.
- Manejo de cambios de conectividad.
- Manejo correcto de permisos revocados.
- Notificaciones claras cuando cambia el estado.
- Compatibilidad con Android moderno.

Investigar las restricciones de Android respecto a ejecución en segundo plano, servicios persistentes, Doze y restricciones de ubicación.

No asumir que un servicio puede ejecutarse indefinidamente sin restricciones del sistema.

## 7. Seguridad y confiabilidad

Prestar especial atención a:

- No dejar al dispositivo sin resolución DNS por un fallo del servidor.
- Evitar loops de reconexión.
- Evitar consultas DNS innecesarias.
- Manejar correctamente la caída de Wi-Fi.
- Manejar cambios entre Wi-Fi y datos móviles.
- No interferir con otras VPN.
- No almacenar información sensible innecesaria.
- Evitar fugas de DNS durante transiciones.

Definir qué sucede si Android elimina el proceso, si el dispositivo se reinicia o si el usuario revoca los permisos.

## 8. Testing

Desarrollar pruebas para:

- Servidor DNS disponible.
- Servidor DNS apagado.
- Corte eléctrico del servidor.
- Recuperación del servidor.
- Entrada y salida del área geográfica.
- Cambio de Wi-Fi a datos móviles.
- Pérdida de conectividad.
- Reinicio del dispositivo.
- VPN externa activa.
- Permisos revocados.
- Respuestas DNS inválidas.
- Timeouts.
- Fallos intermitentes.

## 9. Forma de trabajo

Antes de comenzar a desarrollar:

1. Investigar las APIs actuales de Android y sus restricciones.
2. Evaluar si VpnService es la mejor alternativa.
3. Diseñar la arquitectura Kotlin/Rust.
4. Identificar limitaciones técnicas y posibles problemas.
5. Definir la estrategia para evitar fugas DNS.
6. Proponer la estructura de carpetas y módulos.
7. Presentar un plan de implementación por etapas.

No empezar creando toda la aplicación de una sola vez.

Implementar incrementalmente, verificando cada etapa antes de avanzar.

Priorizar código mantenible, arquitectura sencilla, separación de responsabilidades y pruebas automatizadas.

No agregar funcionalidades que no sean necesarias para el objetivo inicial.

## 10. Resultado esperado

Una aplicación Android llamada **Dnsly** que permita utilizar un DNS personalizado de forma inteligente:

- Cuando el usuario está en su domicilio, activa su DNS privado.
- Si el servidor DNS deja de funcionar, desactiva el mecanismo personalizado.
- Si el usuario abandona el área configurada, vuelve al DNS habitual.
- Cuando el servidor vuelve a funcionar y el usuario está nuevamente dentro del área, reactiva automáticamente el DNS personalizado.

La experiencia debe ser completamente automática después de la configuración inicial, con información clara sobre el estado de la conexión y sin necesidad de intervención constante del usuario.

**Primera tarea:** realizar una investigación técnica profunda y presentar una propuesta de arquitectura viable antes de escribir código.
