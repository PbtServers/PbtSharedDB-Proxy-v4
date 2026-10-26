# Historial de cambios

## 1.0.0-SNAPSHOT — 2026-10-26

- Primera implementación Java 25 contra Velocity API 4.3.0 real.
- Gestión de MariaDB nativo 11.4.13 mediante ProcessBuilder, inicialización solo sobre datos vacíos, bloqueos y cierre ordenado acotado.
- Modos preinstalled y portable x86_64 glibc con artefacto oficial y SHA-256 fijados; Linux aarch64 mediante herramientas nativas de la imagen.
- Configuración YAML validada, cuentas de socket administrativas, usuarios TCP por host y credenciales privadas aleatorias.
- Comandos administrativos, copia lógica con bloqueo global y retención.
- Pruebas unitarias y suite de integración MariaDB real separada, CI y documentación española para Wings/LuckPerms/seguridad/restauración.
- Carga real y comandos comprobados en Velocity 4.3.0 build 44 con Java 25 y motor deshabilitado; 20 pruebas unitarias correctas.
- Corrección del laboratorio Linux: fuentes escribibles por mysql y coordinación privada mediante docker exec, conservando permisos 0700/0600.
- Corrección de la sonda TCP Linux para reinicios con conexiones en TIME_WAIT, conservando el rechazo de listeners activos.
- Dos tests de integración reales correctos en Actions: inicialización MariaDB, SQL entre contenedores, persistencia y backup/restauración. Véanse las evidencias enlazadas en la matriz de aceptación.
- Pendiente de aceptación en Wings y LuckPerms; no se afirma aprobación de producción.

## Desarrollo multiproyecto 1.0.0 (sin publicación estable)

- Trasladar el motor y sus pruebas a proxy conservando historial e implementaciones. Common comparte archivos privados, YAML y protocolo; no se distribuye aparte.
- Añadir Client para Paper 26.1.2 con bootstrap HTTPS/HMAC explícito, pin TLS, protección de repetición y comprobación MariaDB real; salida propia sin contraseñas ni cambios a otros plugins.
- Generar exactamente dos JAR instalables con dependencias internas y descriptores; adaptar CI y conservar el laboratorio MariaDB/LuckPerms existente.
- Corregir variable SQL duplicada del cliente y aplicar la exclusión de etiquetas integration únicamente a los unitarios.
- Añadir auditoría reproducible de nuevos hashes contra repositorios oficiales manteniendo los hashes anteriores.
