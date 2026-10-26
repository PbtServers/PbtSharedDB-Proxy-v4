# Instalación en Pterodactyl / Wings

La [integración Docker Linux](EVIDENCIAS-LINUX-2026-10-26.md) ya ha pasado
con MariaDB real, persistencia tras parada/arranque del gestor, SQL entre
contenedores y copia/restauración lógica. Se ejecutó en un runner de Actions,
sin Panel ni Wings. No acredita reinicio del contenedor, asignaciones/NAT,
firewall o plazo de parada de Wings; consulte la [matriz](INTEGRACION-REAL.md).
El [laboratorio RC1](EVIDENCIAS-RC1-2026-10-26.md) añade recreación del
contenedor, restauración en volumen vacío y LuckPerms oficial. El procedimiento
exacto pendiente en un nodo real está en [VALIDACION-RC1.md](VALIDACION-RC1.md).

Cada servidor (Velocity, Lobby, Survival, Creative) está en su propio contenedor. MariaDB se ejecuta como proceso hijo dentro del contenedor Velocity; no hay Egg SQL separado. `127.0.0.1` desde Lobby apunta a Lobby. No sirve como dirección SQL del proxy para los backends.

## Imagen compatible y persistencia

El administrador del nodo debe preparar una imagen con Java 25, Linux x86_64 o aarch64 y MariaDB Server/Client **11.4.13**. Debe incluir `mariadbd`, `mariadb`, `mariadb-dump`, `mariadb-install-db`, `/usr/bin/id` y, para portable, `/usr/bin/getconf` y glibc. Los scripts de instalación necesitan /bin/sh y utilidades de la distribución. Todas las herramientas deben poder ejecutarse con el UID de Velocity, sin apt/root/systemd en tiempo de ejecución.

No instale paquetes al arrancar desde el plugin. Construya la imagen previamente mediante el flujo habitual de Wings. Un punto de partida de laboratorio es [tests/Dockerfile](../tests/Dockerfile), que combina una imagen oficial MariaDB 11.4.13 con Java 25 y ejecuta pruebas como mysql. Ese Dockerfile no es un Egg ni una imagen lista para el contrato de arranque/SFTP de Wings: la imagen de producción debe respetar el UID, directorio `/home/container`, entrypoint y variables de su Egg Velocity. No sustituya el Egg a ciegas.

En x86_64 glibc puede usar `runtime: portable` y `automatic-install: true`. El paquete oficial fijado se descarga bajo `plugins/pbtsharedb/runtime/`. Requiere espacio para el comprimido, extracción temporal y runtime final, además de datos y copias. La variante systemd no significa que el plugin invoque systemd; sigue necesitando las bibliotecas enlazadas del paquete. En Alpine/musl o ARM use herramientas nativas preinstalled. Si falla la carga compruebe en la imagen:

```sh
id
uname -m
getconf GNU_LIBC_VERSION
ldd /usr/sbin/mariadbd
/usr/sbin/mariadbd --no-defaults --version
/usr/bin/mariadb --no-defaults --version
/usr/bin/mariadb-dump --no-defaults --version
```

`not found` puede referirse al cargador ELF ausente aunque exista el archivo. Las dependencias concretas varían: glibc/musl, libaio, liburing, OpenSSL, libstdc++ y ncurses según el paquete. Corrija la imagen y vuelva a desplegar; el plugin no puede instalar bibliotecas del sistema ni superar seccomp/noexec, límites de PID o memoria. Su diagnóstico ejecuta las herramientas realmente, por lo que no confunde un archivo presente con un binario operativo.

El directorio de trabajo de Velocity debe estar en el volumen persistente de Wings. Conserve íntegro `plugins/pbtsharedb/`: datos, secretos y marca de propiedad/versionado. Un simple reinicio conserva el volumen, pero reinstalar/borrar un servidor desde el Panel puede destruirlo. Mantenga copias externas privadas. No use dos proxies sobre el mismo datadir.

## Puerto y red

1. Reserve un puerto TCP adicional exclusivo en el servidor Velocity, por ejemplo 3307. No reutilice el puerto del proxy Minecraft ni el del Panel SQL. Este paso se hace en el Panel, nunca desde el plugin.
2. Determine una dirección privada del nodo o ruta de red realmente alcanzable desde todos los contenedores autorizados. Verifique la asignación, NAT y firewall del host. No presuponga DNS interno o IP permanente de contenedores. Una asignación de loopback puede ser remapeada por Wings a la interfaz gateway de su red; verifique la versión y configuración local.
3. En el nodo, inspeccione `docker network inspect pterodactyl_nw` y el `docker inspect` de los contenedores. En Wings 1.x revise `/etc/pterodactyl/config.yml`, `docker.network.name`, `network_mode`, `interface` y `enable_icc`. Wings 2.x documenta los mismos controles básicos. El nombre de red puede ser distinto en su instalación. Aplique cambios siguiendo la documentación de su versión y reinicie los servidores afectados.
4. La comunicación debe permitir TCP backend→SQL, sin abrir a contenedores ajenos. `enable_icc: true` habilita comunicación entre contenedores de esa red, pero no autoriza por aplicación. Complete aislamiento con reglas del host (incluya el tráfico publicado Docker/DOCKER-USER o mecanismo equivalente de su instalación). No use host networking para evitar un problema de configuración.
5. Configure `host: 0.0.0.0`, `port: 3307`, `allocated-port: 3307`, `remote-access: true` y `network-isolation-confirmed: true` solo después de comprobar esos controles. El plugin comprueba coincidencia declarada y disponibilidad local del puerto, pero no puede comprobar la asignación real del Panel.
6. Registre para cada usuario la IP origen que MariaDB verá después de NAT. `SELECT USER(), CURRENT_USER();` desde una conexión autorizada ayuda a comprobarlo; si se rechaza, revise los diagnósticos locales y la traducción de red. No amplíe el host a `%`. Si la IP cambia, actualice la cuenta mediante mantenimiento con la base detenida/asegurada.
7. Pruebe desde un contenedor autorizado con cliente oficial. Guarde usuario/contraseña en un archivo privado 0600, no en argumentos:

```ini
[client]
host=DIRECCION_PRIVADA_ALCANZABLE
port=3307
user=lp_lobby
password=CREDENCIAL_DEL_ARCHIVO_KEY
database=luckperms
```

```sh
mariadb --defaults-file=/ruta/privada/cliente.cnf --protocol=tcp \
  -e 'SELECT 1, USER(), CURRENT_USER();'
```

Compruebe también rechazo desde un contenedor no autorizado y desde Internet. No hay automatización que modifique Docker, Egg, Panel o firewall.

## Instalación del plugin

Copie el JAR de build/libs a plugins/ del proxy Java 25 compatible con API 4.3.0. Arranque una vez para generar YAML, detenga, configure y arranque. El primer arranque inicializa solo un datadir vacío, sin cuentas remotas root ni base test. Revise `status` y los registros privados. Solo READY significa consulta autenticada completada. Configure los clientes después de aprovisionar sus credenciales.

En el primer despliegue aprovisione MariaDB para obtener las claves antes de
configurar LuckPerms. PbtSharedDB usa prioridad de evento anterior a FIRST para
iniciar SQL y posterior a LAST para cerrarlo. Ese orden, la reconexión SQL y
la recreación del proxy se han probado con LuckPerms 5.5.88/Velocity 4.3.0 en CI.
La versión, comando STARTUP y parada del Egg real deben comprobarse en el nodo;
otros consumidores que accedan desde constructores no quedan ordenados por
estos eventos. No se afirma que Wings esté validado por esas pruebas Docker.

## Apagado y recuperación

Al detener Velocity se detiene MariaDB. El evento de apagado espera en un ejecutor dedicado: solicita SHUTDOWN por socket y, si vence la espera, SIGTERM únicamente al Process creado. No usa PID leídos para matar procesos ni SIGKILL. Configure la ventana de parada de Wings/Docker para superar ambas esperas, las operaciones previas y el apagado del proxy. Un kill forzado del contenedor impide garantizar apagado limpio; InnoDB deberá recuperar transacciones al siguiente arranque. Una caída del proxy deja a todos los backends sin SQL.

No hay reinicio automático ante fallo: evita bucles y requiere diagnóstico. `status` detecta una salida posterior del proceso. Para socket/PID residual, detenga el proxy y compruebe dentro del contenedor con `ps` que **ningún** mariadbd usa ese datadir. Solo entonces retire manualmente el socket/PID de `plugins/pbtsharedb/run/`; no borre archivos de data/. El bloqueo Java no convierte un proceso iniciado fuera del plugin en propio; los bloqueos nativos InnoDB complementan la exclusión.

Si la inicialización falla y quedan archivos sin marca `.pbtsharedb-owned`, el siguiente intento se rechaza. Conserve el directorio y logs para diagnóstico; restaure en un nuevo directorio vacío o investigue manualmente. No añada la marca a una base ajena para eludir el control. Una actualización de versión del motor exige plan de backup, prueba de restauración y migración explícita; no se hace automáticamente.

## Aceptación antes de producción

Compruebe en un nodo de pruebas: carga real del JAR sin errores; READY; creación y lectura de datos; persistencia tras reinicio; conexión desde otro contenedor permitido; rechazo remoto root/no autorizado; LuckPerms en los cuatro servidores; copia/restauración; parada limpia antes del timeout de Wings. Estas comprobaciones de infraestructura no se han ejecutado en el equipo Windows de desarrollo.
