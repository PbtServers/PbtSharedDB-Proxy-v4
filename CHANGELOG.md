# Historial de cambios

## 1.0.0-SNAPSHOT — 2026-10-26

- Primera implementación Java 25 contra Velocity API 4.3.0 real.
- Gestión de MariaDB nativo 11.4.13 mediante ProcessBuilder, inicialización solo sobre datos vacíos, bloqueos y cierre ordenado acotado.
- Modos preinstalled y portable x86_64 glibc con artefacto oficial y SHA-256 fijados; Linux aarch64 mediante herramientas nativas de la imagen.
- Configuración YAML validada, cuentas de socket administrativas, usuarios TCP por host y credenciales privadas aleatorias.
- Comandos administrativos, copia lógica con bloqueo global y retención.
- Pruebas unitarias y suite de integración MariaDB real separada, CI y documentación española para Wings/LuckPerms/seguridad/restauración.
- Carga real y comandos comprobados en Velocity 4.3.0 build 44 con Java 25 y motor deshabilitado; 19 pruebas unitarias correctas.
- Pendiente de ejecutar aceptación Linux, contenedores y LuckPerms; no se afirma aprobación de producción.
