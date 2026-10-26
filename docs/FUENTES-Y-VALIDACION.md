# Fuentes y validación

Consulta realizada el 26 de octubre de 2026. Las versiones se fijan por disponibilidad comprobada, no por el nombre del repositorio «Proxy v4».

| Componente | Selección y evidencia |
|---|---|
| Velocity API | 4.3.0; POM descargado por HTTPS desde el repositorio oficial y compilación correcta |
| Java | Toolchain 25; entorno local Zulu 25.0.4.1 |
| Gradle | 9.1.0, primera versión con soporte completo de Java 25; ZIP oficial y SHA-256 comprobados |
| MariaDB Server | 11.4.13; manifiesto oficial x86_64 verificado; integración Docker Linux correcta en Actions de 6911107 |
| Connector/J | 3.5.6; POM Maven Central y resolución de Gradle comprobados |
| SnakeYAML | 2.5; POM Maven Central y resolución comprobados |
| Commons Compress | 1.28.0; resolución Maven Central y pruebas de extracción |
| JUnit Jupiter | 5.13.4; resolución Maven Central y ejecución local |

Fuentes oficiales:

- [Creación de plugins Velocity, dependencia API 4.3.0](https://docs.papermc.io/velocity/dev/creating-your-first-plugin/).
- [Eventos, comandos y tareas Velocity](https://docs.papermc.io/velocity/dev/).
- [Gradle 9.1.0 y Java 25](https://docs.gradle.org/9.1.0/release-notes.html).
- [JDK 25](https://openjdk.org/projects/jdk/25/).
- [Distribuciones portables MariaDB sin root](https://mariadb.com/docs/server/server-management/install-and-upgrade-mariadb/installing-mariadb/binary-packages/installing-mariadb-binary-tarballs).
- [Inicialización MariaDB y cuentas con autenticación por socket](https://mariadb.com/docs/server/clients-and-utilities/deployment-tools/mariadb-install-db).
- [Variables del servidor MariaDB, secure_file_priv](https://mariadb.com/docs/server/server-management/variables-and-modes/server-system-variables).
- [mariadb-dump y bloqueo global](https://mariadb.com/docs/server/clients-and-utilities/backup-restore-and-import-clients/mariadb-dump).
- [Manifiesto SHA-256 oficial fijado](https://archive.mariadb.org/mariadb-11.4.13/bintar-linux-systemd-x86_64/sha256sums.txt).
- [Wings 1.x: configuración](https://docs.pterodactyl.io/v1/wings/configuration).
- [Wings 2.x: configuración y red](https://docs.pterodactyl.io/v2/wings/configuration).
- [Modelo de contenedores Pterodactyl](https://docs.pterodactyl.io/v2/project/how-it-works).
- [Configuración de referencia de LuckPerms](https://github.com/LuckPerms/LuckPerms/blob/master/bukkit/src/main/resources/config.yml).

La página de descargas de Velocity mostraba 4.1.2-SNAPSHOT en el índice consultado, mientras la documentación y el repositorio Maven proporcionan 4.3.0. La consulta directa a [Fill, builds de Velocity 4.3.0](https://fill.papermc.io/v3/projects/velocity/versions/4.3.0/builds) confirmó el build 44 STABLE (2026-10-18), SHA-256 `85be73b90f295acd8d5d45a00ad10bac791d4b72b7554172612a89565ebaaa8f`. Se descargó, verificó y ejecutó en Java 25. No se afirma compatibilidad de carga con todas las versiones 4.x: debe probarse en la versión exacta del proxy de producción.

El runtime portable contiene exactamente el SHA-256 `3cebf63914df3154b7cb6d548337eb2be42c773aeb641110446b72e2746cfed7`. Solo descarga esa URL HTTPS, sin redirecciones; no acepta URL/hash editables. No hay catálogo ARM inventado: aarch64 requiere herramientas preinstaladas compatibles. El paquete marcado systemd se ejecuta directamente, sin invocar systemd; sus bibliotecas deben estar presentes igualmente.

## Evidencia local, Linux y límites

El equipo local es Windows y no tiene Docker ni WSL instalado. El build actual
pasa con 20 pruebas unitarias; el intento local del laboratorio falla por Docker
ausente y no se contabiliza como validación SQL. También se comprobó localmente
la carga y comandos de Velocity 4.3.0 build 44 con el motor deshabilitado.

[Actions de dfcf60d](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40304776222)
confirmó el build con verificación SHA-256 activa y la carga real de Velocity
en Ubuntu 24.04.5/Temurin 25.0.4+1. Su fallo posterior en el laboratorio se
corrigió sin ejecutar MariaDB como root ni ampliar permisos de sus secretos.

[Actions de 6911107](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40306280695)
completó además el laboratorio MariaDB real: 20 unitarios y dos tests SQL,
sin fallos ni omisiones, verificados en los XML descargados. Comprueba
inicialización, JDBC, privilegios, consultas entre contenedores, persistencia
tras detener y crear otro gestor, apagado gestionado, dump y restauración
lógica con lectura posterior a otro reinicio. Consulte los hashes, artefactos
y fallos corregidos en [las evidencias Linux](EVIDENCIAS-LINUX-2026-10-26.md).

No se han ejecutado Wings ni LuckPerms real. Tampoco se han validado reinicio
del contenedor, caída forzada, NAT/firewall de producción ni restauración en
volumen vacío. La [matriz de aceptación](INTEGRACION-REAL.md) mantiene esos
puntos pendientes. Un laboratorio Docker correcto no equivale a aceptación
de infraestructura o aprobación de producción.
