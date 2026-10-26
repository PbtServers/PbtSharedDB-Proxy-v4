# Desarrollo

Java Toolchains 25, Gradle Wrapper 9.1.0 con SHA-256 fijado. No hace falta Gradle global; sí un JDK 25 instalado. Las dependencias directas están fijadas en build.gradle, las transitivas en gradle.lockfile y su contenido en gradle/verification-metadata.xml. La API oficial arrastra velocity-brigadier 1.0.0-SNAPSHOT: su contenido observado queda protegido por hash; si cambia, la verificación debe fallar y requerir revisión, no aceptar silenciosamente otro binario. El JAR incluye Connector/J, SnakeYAML y Commons Compress y sus dependencias transitivas; Velocity API se usa solo para compilar y la aporta el proxy. Velocity genera el descriptor mediante su annotation processor explícito para Java moderno.

```sh
./gradlew clean build
```

En Windows: `./gradlew.bat clean build`. Resultado: `build/libs/PbtSharedDB-1.0.0-SNAPSHOT.jar`. Informes: `build/reports/tests/test/index.html`. No incluya runtime, secretos, logs o datos en commits.

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

`test` cubre configuración segura, esquema futuro preservado, duplicados, nombres SQL, hosts autorizados, credenciales estables, rutas, extracción de archivos maliciosos, puerto ocupado, códigos reales de salida nativa y estados sin motor. No se simulan conexiones SQL para afirmar funcionamiento de MariaDB.

`integrationTest` está separado, exige Linux y herramientas MariaDB 11.4.13 reales, y falla si faltan; no se omite silenciosamente. Comprueba inicialización sin root, SELECT 1 TCP, permisos mínimos, rechazo de root TCP, persistencia, copia lógica, restauración y exclusión de otra instancia. Puerto local de prueba 13316; no lo ocupe. Ejecución con Docker en Linux:

```sh
docker build -t pbtsharedb-integracion -f tests/Dockerfile .
mkdir -p build/integration-gradle
# Solo en una copia de trabajo de pruebas, el UID mysql necesita escribir resultados/cache.
chmod -R a+rwX build .gradle
docker run --rm -v "$PWD:/work" \
  -e GRADLE_USER_HOME=/work/build/integration-gradle pbtsharedb-integracion
```

Antes ejecute build para crear `.gradle` y build. El chmod anterior se limita a resultados/cache de esa copia de trabajo; nunca se aplica al directorio del plugin en producción. La imagen usa mysql como usuario de ejecución, no su entrypoint de servicio MariaDB. El test arranca su propio mariadbd mediante ProcessBuilder.

GitHub Actions compila, ejecuta unitarios, publica el JAR como artefacto y prepara esta integración Docker, conservando los informes. Además activa PBT_REMOTE_PROBE=1 y conecta un segundo contenedor MariaDB cliente, en una red de pruebas aislada con IP explícita conocida, para consultar el registro de aplicación mediante una cuenta remota sin privilegios globales. Esas IP fijas son solo un fixture de pruebas, nunca una suposición sobre Wings. No publica releases ni despliega en Wings. Las etiquetas base de las imágenes Docker se fijan por versión pero no por digest; para auditoría reproducible registre sus digests en el despliegue.

La carga en Velocity oficial se comprueba con `python tests/velocity_smoke.py --velocity /ruta/velocity-4.3.0-44.jar --plugin build/libs/PbtSharedDB-1.0.0-SNAPSHOT.jar`. La prueba exige el JAR de proxy ya descargado/verificado, genera una configuración con escucha loopback y puerto efímero y comprueba carga, status, ayuda, alias/recarga y cierre. El motor SQL permanece deshabilitado. Conserva un archivo .log bajo build/reports/velocity-smoke/; el entorno de trabajo privado queda fuera de los informes para no publicar forwarding.secret ni configuraciones. CI descarga y verifica el build oficial fijado antes de ejecutarla.

Pendiente de validación de infraestructura: clientes desde otros contenedores, MariaDB Linux, apagado con Wings, integración LuckPerms y exposición/firewall. No se deben convertir esas tareas en afirmaciones basadas solo en unitarios. Consulte [PTERODACTYL.md](PTERODACTYL.md) para aceptación manual.

## Contribuciones

Mensajes administrativos, documentación y comentarios en español. Mantenga dependencias fijadas y verificadas en fuentes oficiales; evalúe seguridad del runtime antes de añadir otro artefacto o arquitectura. No use MariaDB4j: este proyecto gestiona directamente el servidor oficial y evita su abstracción y distribución de binarios adicionales. No se usan H2/SQLite como sustitutos.

Antes de cambiar el motor, pruebe restauración/migración y actualice hash, catálogo y documentación. No adopte un datadir ajeno ni cambie una política de fallo seguro para ocultar un error. No registre SQL sensible o causas JDBC completas. Adjunte resultados reales y declare expresamente qué plataformas no ha probado.
