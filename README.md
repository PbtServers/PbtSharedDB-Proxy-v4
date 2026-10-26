# PbtSharedDB

Proyecto Gradle multiproyecto para Java **25**: `proxy` (Velocity 4.x/API 4.3.0 y MariaDB **11.4.13**), `client` (Paper **26.1.2**, API 26.1) y `common` (biblioteca interna compartida, sin distribución independiente). Los archivos de versión `1.0.0` son candidatos de desarrollo; no se ha publicado una versión estable ni se ha validado Wings.

La migración conserva el código y las pruebas anteriores. El build local ejecuta **29 unitarios**; el [CI de la migración](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40316432019) acredita dos ejecuciones de cuatro integraciones reales, seis criterios RC y carga en Velocity/Paper, sin fallos ni omisiones. Consulte las [evidencias multiproyecto](docs/EVIDENCIAS-MULTIPROYECTO-2026-10-26.md) para los commits y el alcance exacto. Las [evidencias anteriores](docs/EVIDENCIAS-RC1-2026-10-26.md) acreditan MariaDB y LuckPerms reales para el commit histórico indicado, no automáticamente esta migración. Consulte la [matriz](docs/INTEGRACION-REAL.md), la [guía de cliente y HTTPS/HMAC](docs/CLIENTE-Y-DESCUBRIMIENTO.md) y el [procedimiento Wings pendiente](docs/VALIDACION-RC1.md).

## Arquitectura y funciones implementadas

Velocity administra un único proceso hijo mariadbd con ProcessBuilder. Nunca ejecuta un servicio del host ni mata procesos por PID externo. Usa un ejecutor dedicado para I/O, SQL, procesos y backups, y eventos asíncronos de inicio/cierre. Datos, runtime portable, configuración, secretos y copias residen bajo `plugins/pbtsharedb/`, que debe estar en el volumen persistente.

- Runtime preinstalado o portable oficial x86_64 glibc con URL/version/hash fijados, sin root/apt/systemd durante ejecución. aarch64 requiere herramientas nativas compatibles en la imagen.
- Inicialización con mariadb-install-db solo para un directorio vacío; bloqueo por datadir, marca de propiedad/versionado y rechazo de datos ajenos/incompletos.
- Primera fase sin TCP, cuentas administrativas por socket Unix y usuarios de aplicación por IPv4 explícita; credenciales aleatorias privadas, sin contraseña root remota.
- Estados de proceso separados de disponibilidad; READY exige SELECT 1 autenticado por TCP. Timeouts y apagado SQL/SIGTERM con esperas acotadas. Sin reinicios automáticos que puedan formar bucles.
- Backups lógicos mediante mariadb-dump oficial con bloqueo global de lectura, comprobación de espacio/errores y retención. Las escrituras se detienen durante la copia.
- Configuración validada sin sobrescribir secretos, comandos con consola o permiso administrativo explícito.

No se usa H2, SQLite, MariaDB4j ni una simulación SQL. Connector/J se usa para la comprobación TCP; administración y dump usan clientes oficiales por socket.

## Instalación

1. Prepare una imagen Linux con Java 25 y las herramientas MariaDB 11.4.13 indicadas en [PTERODACTYL.md](docs/PTERODACTYL.md). Velocity debe ejecutarse sin root. También puede usar la distribución portable fijada, si la imagen x86_64 glibc contiene sus bibliotecas necesarias.
2. Compile con `./gradlew clean build` (`./gradlew.bat clean build` en Windows) o descargue el artefacto de una ejecución correcta de Actions. Copie `build/distributions/PbtSharedDB-Proxy-1.0.0.jar` a plugins/ de un proxy compatible con API 4.3.0.
3. Arranque para generar config.yml. Por defecto `enabled: false`, `auto-start: false`, host local y sin usuarios/bases. Configure y valide en un entorno de pruebas antes de activar.
4. Declare bases y cuentas, active enabled y arranque con `pbtsharedb start`. Para inicio con el proxy active auto-start. Solo aprovisione clientes cuando status muestre READY.
5. Para backends de otros contenedores, prepare primero asignación TCP adicional, dirección privada alcanzable y aislamiento de red/firewall. Configure los controles remotos explícitos y compruebe aceptación/rechazo desde los orígenes correspondientes.

No use `127.0.0.1` como host SQL de los backends. No exponga el puerto SQL a Internet. El plugin no modifica puertos, redes Docker, Egg ni Panel. Al apagar Velocity también se detiene MariaDB y todos los clientes pierden acceso. No ofrece alta disponibilidad.

## Cliente Paper y dos artefactos

Instale `build/distributions/PbtSharedDB-Client-1.0.0.jar` únicamente en Paper 26.1.2. Common se incorpora dentro de ambos plugins; no se instala por separado. El cliente parte deshabilitado y necesita URL HTTPS explícita, huella del certificado y token privado por backend. No escanea redes ni exporta contraseñas. `pbtsharedbclient discover` genera un archivo propio para configuración manual, tras verificar MariaDB. La autenticación SQL requiere una clave de aplicación aprovisionada manualmente. Consulte [la guía completa](docs/CLIENTE-Y-DESCUBRIMIENTO.md).

## Comandos

Consola o permiso `pbtsharedb.admin`. Alias oficial registrado: `/sharedb`.

| Comando | Función |
|---|---|
| `/pbtsharedb help` | Ayuda administrativa |
| `/pbtsharedb status` | Estado, sin credenciales |
| `/pbtsharedb info` | Estado, versión y puerto configurado, sin credenciales |
| `/pbtsharedb start` | Inicializar si procede, validar cuentas y arrancar |
| `/pbtsharedb stop` | Apagado ordenado con timeout |
| `/pbtsharedb restart` | Detener y arrancar con configuración ya cargada |
| `/pbtsharedb backup` | Dump consistente de bases declaradas, bloqueando escrituras |
| `/pbtsharedb reload` | Validar y cargar YAML; requiere motor detenido |

Las operaciones se serializan y se rechaza otro comando operativo mientras hay uno en curso. No hay comando para mostrar contraseñas ni ejecutar SQL arbitrario. La restauración se hace mediante cliente oficial en mantenimiento.

## Seguridad y límites

Directorios 0700, secretos 0600 y contraseñas aleatorias de 256 bits. Las cuentas de aplicación reciben permisos sobre su base; no administración global. Root queda restringido a autenticación Unix local. Escuchar en 0.0.0.0 no autoriza una red: se exige configuración externa y cuentas por host. El descubrimiento exige HTTPS/HMAC y certificado fijado. No se configura TLS para las sesiones SQL en esta versión; use solo una red privada confiable y aislada. Los plugins del mismo UID comparten confianza y pueden leer los archivos del contenedor.

Requiere memoria/CPU/PID suficientes además de Java, ejecución nativa permitida y bibliotecas compatibles. No puede resolver un montaje noexec, seccomp restrictivo, un OOM ni una parada forzada de Docker. No hay adopción de datadir existente, actualización automática de motor, restauración automática, catálogo portable ARM/musl, hosts SQL DNS/CIDR/IPv6, rotación automática de logs o planificación de backups. El orden de arranque con LuckPerms en el proxy necesita una prueba de reconexión en la versión instalada.

## Documentación y pruebas

- [Laboratorio Linux y matriz de aceptación real de Wings/LuckPerms](docs/INTEGRACION-REAL.md).
- [Instalación, red, puertos y recuperación en Pterodactyl](docs/PTERODACTYL.md).
- [LuckPerms en Velocity, Lobby, Survival y Creative](docs/LUCKPERMS.md).
- [Todas las opciones de configuración](docs/CONFIGURACION.md).
- [Seguridad y credenciales](docs/SEGURIDAD.md).
- [Copias y procedimiento de restauración](docs/COPIAS-DE-SEGURIDAD.md).
- [Compilación, arquitectura, tests y contribución](docs/DESARROLLO.md).
- [Versiones verificadas, fuentes oficiales y límites de validación](docs/FUENTES-Y-VALIDACION.md).
- [Historial](CHANGELOG.md). Se conserva el [README original de planificación](docs/README-ORIGINAL.md) y la licencia existente.

`build` ejecuta los unitarios. `integrationTest` exige Linux/MariaDB real y verifica inicialización, persistencia, privilegios, copia y restauración. GitHub Actions compila, publica ambos JAR como artefactos y ejecuta la integración en Docker; su resultado debe revisarse cuando se ejecute. Compartir economía o inventarios requiere plugins con soporte de datos compartidos propio; compartir SQL/LuckPerms no lo implementa por sí solo.
