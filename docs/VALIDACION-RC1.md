# Procedimiento previo a v1.0.0-RC1

Los artefactos 1.0.0 continúan siendo candidatos de desarrollo, sin release estable. Este documento describe pruebas de laboratorio;
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
Tras reiniciar SQL, se repite la propagación. Además se elimina y recrea el
proxy con LuckPerms manteniendo Paper arrancado, se exige el permiso persistido
y se vuelve a comprobar otro cambio en Paper. La comprobación se hace sin
jugadores conectados: no acredita login, forwarding ni permisos de jugadores
en una sesión Minecraft real. No se desactiva la autenticación SQL o Minecraft.

Informes publicables: `build/reports/rc/resultado.json`, logs redactados y hash
de datos. El resultado distingue pruebas superadas de no ejecutadas. Las claves
SQL y el secreto de forwarding se comparan en memoria contra los logs de
consola, inicialización y MariaDB;
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
   con `docker inspect --format '{{json .Mounts}}' <ID_PROXY>` en el nodo.
   No publique el entorno del contenedor ni comparta ese datadir.
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

Comandos de inspección en el nodo (sustituya los identificadores, sin secretos):

```sh
PROXY_ID='ID_REAL_DEL_CONTENEDOR_PROXY'
WINGS_NETWORK='NOMBRE_REAL_DE_RED'
docker inspect --format '{{json .Mounts}}' "$PROXY_ID"
docker inspect --format '{{json .NetworkSettings.Ports}}' "$PROXY_ID"
docker inspect --format '{{json .NetworkSettings.Networks}}' "$PROXY_ID"
docker network inspect "$WINGS_NETWORK"
docker exec "$PROXY_ID" sh -ec 'id; pwd; /usr/sbin/mariadbd --no-defaults --version; /usr/bin/mariadb --no-defaults --version'
docker exec "$PROXY_ID" sh -ec 'stat -c "%a %U" plugins/pbtsharedb/data plugins/pbtsharedb/secrets plugins/pbtsharedb/secrets/*.key'
```

Para la comprobación entre contenedores, guarde el archivo cliente del ejemplo
de [PTERODACTYL.md](PTERODACTYL.md) como 0600 en cada backend. Ejecute dentro
del backend autorizado:

```sh
mariadb --defaults-file=/ruta/privada/cliente.cnf --protocol=tcp --connect-timeout=5 \
  -e 'SELECT USER(),CURRENT_USER(),DATABASE(); SHOW TABLES;'
```

En las consolas Minecraft, después de conectar un jugador ficticio no operador:

```text
# Velocity (sustituir UUID por el del jugador de prueba)
lpv creategroup rcaceptacion
lpv user UUID parent add rcaceptacion
lpv group rcaceptacion permission set pbt.rc1.wings true
# Backend: comprobar el permiso efectivo y negarlo
lp user UUID permission check pbt.rc1.wings
lp group rcaceptacion permission set pbt.rc1.wings false
# Velocity: exigir resultado false; repetir tras Stop/Start del Panel
lpv user UUID permission check pbt.rc1.wings
```

No use reload/networksync para ocultar un fallo de propagación automática.
Al terminar, retire al usuario del grupo y elimine el grupo de prueba. Guarde
solo la salida saneada y el UUID ficticio, sin dumps ni configuración privada.

## Ampliación obligatoria para el cliente Paper 26.1

1. Instale el JAR Client probado en un backend Paper 26.1.2 con Java 25. Registre versión, hash, imagen, Egg, asignaciones y montaje persistente.
2. Reserve una asignación TCP HTTPS distinta de SQL en el proxy. Confirme firewall privado de ambos puertos y el origen real de cada backend. Configure certificado con SAN del bootstrap, pin y token privado según [la guía](CLIENTE-Y-DESCUBRIMIENTO.md). No imprima ni adjunte claves.
3. Ejecute `pbtsharedbclient discover` en consola. Exija archivo propio con metadatos correctos, sin contraseña; con clave SQL de aplicación aprovisionada manualmente exija `authenticated-select-1`. Configure los otros plugins manualmente y compruebe una lectura/escritura real.
4. Cambie temporalmente token, huella y contraseña SQL de aplicación por valores de prueba incorrectos, cada uno por separado; exija rechazo, archivo anterior intacto y ningún secreto en logs. Restaure los archivos privados correctos después de cada caso. Compruebe también rechazo con el proxy detenido y desde un contenedor no autorizado.
5. Detenga, reinicie y recree ambos contenedores mediante el procedimiento soportado de Wings conservando sus montajes. Repita descubrimiento y SELECT 1; compruebe datos y permisos LuckPerms según el procedimiento anterior. Registre fecha, versiones y salidas saneadas. Esta acta sigue pendiente hasta ejecutarse en un nodo real.
