# Procedimiento previo a v1.0.0-RC1

La versión continúa en SNAPSHOT. Este documento describe pruebas de laboratorio;
los resultados se registran por commit en la matriz de aceptación. Docker no
acredita por sí mismo el contrato de ejecución de Wings.

## Laboratorio RC reproducible

En Linux con Docker, Python 3 y JDK 25, desde una copia de trabajo desechable:

```sh
bash ./gradlew clean build --no-daemon
bash tests/integration-linux.sh
python3 tests/rc_integration.py
```

El job `rc1` descarga el JAR del job `build` del mismo workflow. El laboratorio
usa la imagen de tests existente, procesos mysql sin root y una red exclusiva
172.30.86.0/24, sin publicar puertos al host. Reserva nombres de recursos con
un UUID por ejecución. Los auxiliares root solo preparan la propiedad de los
volúmenes nuevos; no arrancan MariaDB ni los servidores.

La prueba arranca **Velocity real y PbtSharedDB** con auto-start. Inserta dos
registros Unicode/binarios y una relación con clave foránea, compara esquema,
tablas y registros y ejecuta CHECK TABLE. Detiene el anfitrión con `docker stop`,
exige confirmación de cierre del gestor y ausencia de socket/PID, elimina el
contenedor y crea otro sobre el mismo volumen. Compara también las credenciales.

Después genera un backup con el comando del plugin. Comprueba que el segundo
volumen está completamente vacío antes de prepararlo; el gestor inicializa
cuentas nuevas, se importa el dump con el cliente oficial y se comparan los
mismos datos/esquema/integridad. Se recrea otra vez ese contenedor y se repite
la lectura. No se copian secretos ni archivos físicos del datadir al destino.

Para LuckPerms se usan los JAR oficiales Velocity y Bukkit 5.5.88/build 1675,
Velocity 4.3.0/build 44 y Paper 1.21.8/build 60. Las URL y SHA-256 están fijados
en `tests/rc-artifacts.json`: Paper/Velocity se contrastaron con Fill oficial;
LuckPerms se descargó de las URL de `https://metadata.luckperms.net/data/all`,
y se calcularon una vez sus hashes por HTTPS. No se afirma que LuckPerms
publique un checksum independiente en esos metadatos. CI no acepta otro hash.
El primer arranque de Paper acepta su EULA en el volumen desechable y descarga
sus bibliotecas; LuckPerms carga sus dependencias oficiales por su propio loader.

Los observadores de `tests/rc-probe/` se compilan contra las API que llevan esos
artefactos. Consultan la caché real del grupo default y no llaman al storage:
un comando LuckPerms en Velocity concede `pbt.rc1.propagacion`, Paper debe
observar TRUE; Paper lo cambia a false y Velocity debe observar FALSE.
Tras reiniciar SQL, se repite la propagación. La comprobación se hace sin
jugadores conectados: no acredita login, forwarding ni permisos de jugadores
en una sesión Minecraft real. No se desactiva la autenticación SQL o Minecraft.

Informes publicables: `build/reports/rc/resultado.json`, logs redactados y hash
de datos. El resultado distingue pruebas superadas de no ejecutadas. Las claves
se comparan en memoria contra los logs de consola, inicialización y MariaDB;
si aparecen, la prueba falla y se redactan antes de escribir el informe. No se
publican volúmenes, dumps ni archivos de configuración privados. Los recursos
creados por el run se retiran al finalizar. La caída forzada (SIGKILL) no forma
parte de esta prueba y no debe confundirse con `docker stop` ordenado.

## Aceptación en un nodo Pterodactyl real

Ejecute con servidores de prueba y datos ficticios. No cambie la red del nodo
de producción para eludir un rechazo. Conserve un acta con versiones, hashes,
horas UTC, comandos ejecutados y resultados; nunca contraseñas.

1. Registre versiones de Panel/Wings/Docker, export del Egg y digest de la
   imagen. Compruebe el UID real con `id`, el WORKDIR `/home/container`,
   entrypoint y comando STARTUP de Velocity. El Dockerfile de tests no sustituye
   el contrato de Egg/SFTP. Verifique límites de memoria/CPU/PID, noexec/seccomp
   y bibliotecas con `ldd /usr/sbin/mariadbd` y `mariadbd --no-defaults --version`.
2. Copie el JAR probado y genere config con el motor deshabilitado. Detenga el
   proxy. Asegure que `plugins/pbtsharedb` pertenece al UID del servidor sobre
   su volumen persistente (directorios 0700, claves 0600). Registre el montaje
   con `docker inspect <ID_PROXY>` en el nodo. No comparta ese datadir.
3. Reserve en el Panel una asignación TCP SQL exclusiva (ejemplo 3307).
   Configure port/allocated-port=3307 y acceso remoto solo después de comprobar
   red privada y firewall. Registre asignación, IP privada alcanzable y NAT.
   Inspeccione `docker network inspect <RED_WINGS>` y las IP de origen; no use
   las IP fijas del laboratorio ni el loopback de un backend.
4. Declare base y cuentas por IP observada, active auto-start/enabled y arranque
   desde el Panel. Exija status READY y SELECT 1 autenticado, sin errores de
   bibliotecas, escritura, bloqueo o puerto. Desde cada backend use cliente
   oficial con defaults-file 0600: `SELECT USER(),CURRENT_USER(),DATABASE();`.
   Compruebe rechazo de root remoto, contenedor ajeno y acceso público. No abra
   hosts `%` ni quite reglas para que el test pase.
5. Con LuckPerms real en Velocity y backend, configure misma base/prefijo y
   cuentas independientes, messaging-service sql. Con un grupo/UUID ficticio,
   conceda un nodo, compruebe su permiso efectivo desde ambos servidores y
   retire el nodo desde el backend. Verifique el cambio en Velocity. Pruebe
   también jugadores conectados y forwarding de la versión real del Egg.
6. Registre tablas/registros, pare desde el Panel y compruebe en el log el cierre
   de MariaDB antes del timeout real de Wings. Dimensione ese plazo para backup
   en curso + apagado Java/SQL. Arranque y relea datos/permisos. Recree el
   contenedor conservando el volumen mediante el procedimiento soportado de su
   nodo (no reinstale ni borre el servidor desde el Panel) y repita la lectura.
7. Genere backup con clientes detenidos/en mantenimiento, cópielo fuera del
   nodo por canal privado y calcule su SHA-256. En un segundo servidor/volumen
   de prueba vacío, inicialice con el mismo motor y cuentas nuevas, importe con
   el cliente oficial por socket y compruebe tablas, recuentos, CHECK TABLE y
   permiso ficticio desde LuckPerms. Reinicie y repita. Use el procedimiento
   completo de [copias/restauración](COPIAS-DE-SEGURIDAD.md).
8. Adjunte el acta a la matriz. Cualquier paso fallido o no ejecutado bloquea
   su correspondiente criterio. Sin ese acta no se declara Wings validado ni
   la versión lista para producción. No se publica una release estable aquí.
