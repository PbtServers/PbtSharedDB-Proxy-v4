# Desarrollo

Java Toolchains 25, Gradle Wrapper 9.1.0 con SHA-256 fijado. No hace falta Gradle global; sí un JDK 25 instalado. Las dependencias directas están fijadas en build.gradle, las transitivas en los archivos `proxy/gradle.lockfile`, `client/gradle.lockfile` y `common/gradle.lockfile` y su contenido en gradle/verification-metadata.xml. La API oficial arrastra velocity-brigadier 1.0.0-SNAPSHOT: su contenido observado queda protegido por hash; si cambia, la verificación debe fallar y requerir revisión, no aceptar silenciosamente otro binario. El JAR incluye Connector/J, SnakeYAML y Commons Compress y sus dependencias transitivas; Velocity API se usa solo para compilar y la aporta el proxy. Velocity genera el descriptor mediante su annotation processor explícito para Java moderno.

```sh
./gradlew clean build
```

En Windows: `./gradlew.bat clean build`. Resultado: `build/distributions/PbtSharedDB-Proxy-1.0.0.jar`. Informes: `build/{proxy,client,common}/reports/tests/test/index.html`. No incluya runtime, secretos, logs o datos en commits.

También se genera `build/distributions/PbtSharedDB-Client-1.0.0.jar`; solo se distribuyen esos dos archivos. Las API de plataforma son `compileOnly`; SQL/YAML y las clases comunes se empaquetan dentro de los JAR. El artefacto de common es interno. El motor y sus pruebas se han movido a `proxy/src/` conservando implementaciones e historial.

## Estructura

- `PbtSharedDB`: entrada API Velocity, eventos asíncronos, ejecutor administrativo único y cierre.
- `ConfigurationManager`: YAML seguro y validación previa, compatibilidad de esquema sin sobrescritura.
- `RuntimeManager`: plataforma, herramientas, versión exacta, descarga oficial y extracción restringida.
- `ProcessManager`: estados, locks de datadir/JVM, inicialización y supervisión acotada de un único proceso.
- `DatabaseManager`: autenticación de socket, DDL validado, cuentas y comprobación JDBC TCP autenticada.
- `BackupManager`: dump lógico consistente, espacio, comprobación de salida y retención.
- `CommandManager`: API SimpleCommand, alias sharedb, permiso explícito o consola.
- `SafeFiles` y `NativeCommand`: archivos privados y ejecución sin shell.

Las operaciones de disco, procesos y SQL se serializan fuera de hilos críticos mediante un ejecutor dedicado. Los eventos de inicio/apagado usan EventTask con continuación para no bloquear el hilo que publica el evento. Status/info solo leen estado y nunca esperan el bloqueo del arranque. Se admite una operación de comando pendiente; no se acumula una cola ilimitada de backups/reinicios. El cierre espera a la operación previa: dimensione el plazo de parada del contenedor también para un backup en curso.

Estados: STOPPED → STARTING → READY → STOPPING → STOPPED; cualquier fallo operativo lleva a FAILED. No hay reintentos automáticos de reinicio; solo reintentos de disponibilidad acotados por startup-timeout. Un proceso que no termina conserva el bloqueo y no se sustituye por otro.

## Pruebas

El laboratorio compartido por CI y ejecución local está en
`tests/integration-linux.sh`; consulte [integración real](INTEGRACION-REAL.md)
para ejecutarlo con las consultas entre contenedores y distinguir los
resultados unitarios de la aceptación pendiente en Wings/LuckPerms.

`test` cubre configuración segura, esquema futuro preservado, duplicados, nombres SQL, hosts autorizados, credenciales estables, rutas, extracción de archivos maliciosos, puerto ocupado, códigos reales de salida nativa y estados sin motor. No se simulan conexiones SQL para afirmar funcionamiento de MariaDB.

`integrationTest` está separado, exige Linux y herramientas MariaDB 11.4.13 reales, y falla si faltan; no se omite silenciosamente. Comprueba inicialización sin root, SELECT 1 TCP, permisos mínimos, rechazo de root TCP, persistencia, copia lógica, restauración y exclusión de otra instancia. Puerto local de prueba 13316; no lo ocupe. Ejecución con Docker en Linux:

```sh
bash ./gradlew clean build --no-daemon
bash tests/integration-linux.sh
```

El laboratorio copia las fuentes a la imagen con propietario mysql y monta solo
los resultados/cache bajo build. No monte el repositorio del host en /work:
Gradle 9 exige que la raíz del proyecto sea escribible por su usuario.
El script prepara los permisos de build solo en esta copia desechable; nunca
se aplica al directorio del plugin en producción. La imagen usa mysql como
usuario de ejecución, no su entrypoint de servicio MariaDB. El test arranca
su propio mariadbd mediante ProcessBuilder. Reconstruya al cambiar fuentes.

GitHub Actions compila, ejecuta unitarios, publica el JAR como artefacto y prepara esta integración Docker, conservando los informes. Además activa PBT_REMOTE_PROBE=1 y conecta un segundo contenedor MariaDB cliente, en una red de pruebas aislada con IP explícita conocida, para consultar el registro de aplicación mediante una cuenta remota sin privilegios globales. Esas IP fijas son solo un fixture de pruebas, nunca una suposición sobre Wings. No publica releases ni despliega en Wings. Las etiquetas base de las imágenes Docker se fijan por versión pero no por digest; para auditoría reproducible registre sus digests en el despliegue.

La carga en Velocity oficial se comprueba con `python tests/velocity_smoke.py --velocity /ruta/velocity-4.3.0-44.jar --plugin build/distributions/PbtSharedDB-Proxy-1.0.0.jar`. La prueba exige el JAR de proxy ya descargado/verificado, genera una configuración con escucha loopback y puerto efímero y comprueba carga, status, ayuda, alias/recarga y cierre. El motor SQL permanece deshabilitado. Conserva un archivo .log bajo build/reports/velocity-smoke/; el entorno de trabajo privado queda fuera de los informes para no publicar forwarding.secret ni configuraciones. CI descarga y verifica el build oficial fijado antes de ejecutarla.

MariaDB Linux y las consultas desde otros contenedores han pasado en Actions,
incluyendo persistencia, parada del proceso gestionado y backup/restauración
lógica: [evidencias](EVIDENCIAS-LINUX-2026-10-26.md). Siguen pendientes apagado
y reinicio del contenedor con Wings, LuckPerms con jugadores en ese nodo y exposición/firewall.
Consulte [PTERODACTYL.md](PTERODACTYL.md) para aceptación manual.

Para las pruebas previas a RC1, ejecute `python3 tests/rc_integration.py`
después de build y del laboratorio anterior. No requiere nuevas dependencias
Gradle: los observadores se compilan con las API de los JAR oficiales fijados
por hash. Consulte [procedimiento y alcance](VALIDACION-RC1.md) y
[evidencias](EVIDENCIAS-RC1-2026-10-26.md). El job rc1 usa el artefacto build
del mismo commit y publica únicamente resultado.json, logs redactados y hash
de los datos comparados, sin volúmenes, claves ni dumps.

## Contribuciones

Mensajes administrativos, documentación y comentarios en español. Mantenga dependencias fijadas y verificadas en fuentes oficiales; evalúe seguridad del runtime antes de añadir otro artefacto o arquitectura. No use MariaDB4j: este proyecto gestiona directamente el servidor oficial y evita su abstracción y distribución de binarios adicionales. No se usan H2/SQLite como sustitutos.

Antes de cambiar el motor, pruebe restauración/migración y actualice hash, catálogo y documentación. No adopte un datadir ajeno ni cambie una política de fallo seguro para ocultar un error. No registre SQL sensible o causas JDBC completas. Adjunte resultados reales y declare expresamente qué plataformas no ha probado.

## Auditoría de dependencias del cliente

`python3 tests/verify_dependency_additions.py` compara los metadatos con `fd658f0`, exige que los 133 artefactos previos mantengan exactamente sus hashes y contrasta los 123 nuevos con Maven Central, PaperMC y Mojang. El informe queda en `build/reports/dependency-audit.json`. No sustituye la verificación estricta de Gradle ni modifica metadatos. No se desactiva ningún control. Los unitarios se excluyen de `integrationTest` mediante etiquetas; la exclusión de integración se aplica únicamente a la tarea `test`.
