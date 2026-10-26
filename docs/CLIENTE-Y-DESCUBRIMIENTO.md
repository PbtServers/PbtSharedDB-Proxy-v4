# Cliente Paper y descubrimiento autenticado

El multiproyecto conserva el motor existente en `proxy`, introduce `client`
para Paper 26.1.2 (API 26.1) y comparte protocolo, YAML y archivos privados
mediante `common`. No se distribuye common por separado ni se duplica el motor.
Java 25 es obligatorio. Los únicos instalables de `build/distributions/` son
`PbtSharedDB-Proxy-1.0.0.jar` y `PbtSharedDB-Client-1.0.0.jar`.
El número de archivo no constituye una publicación estable ni aceptación Wings.

## Preparación privada del proxy

Instale Proxy en Velocity 4.x y configure MariaDB como antes. Mantenga el
directorio `plugins/pbtsharedb/` en almacenamiento persistente. Declare una
cuenta de aplicación por backend y su IP real de origen, con permisos sobre
una única base. No utilice root ni cuentas de diagnóstico.

El primer arranque genera `discovery.yml` deshabilitado. Prepare un PKCS12
y un archivo privado con su contraseña mediante un canal administrativo;
no incluya la contraseña en argumentos, historial, logs ni repositorio.
Ejemplo desde ese directorio, con `discovery-tls-password.key` ya aprovisionado:

```sh
umask 077
keytool -genkeypair -alias discovery -keyalg RSA -keysize 2048 -validity 365 \
  -dname 'CN=proxy.interno' -ext 'SAN=DNS:proxy.interno,IP:10.0.0.10' \
  -storetype PKCS12 -keystore discovery-tls.p12 \
  -storepass:file discovery-tls-password.key
keytool -exportcert -alias discovery -keystore discovery-tls.p12 \
  -storepass:file discovery-tls-password.key -file discovery.der
sha256sum discovery.der
chmod 600 discovery-tls.p12 discovery-tls-password.key
```

Sustituya nombre/IP por el destino explícito realmente alcanzable del nodo.
Configure `discovery.yml` (ejemplo de red privada; aislamiento externo obligatorio):

```yaml
enabled: true
bind-host: 0.0.0.0
port: 3307
allocated-port: 3307
network-isolation-confirmed: true
advertised-sql-host: 10.0.0.10
keystore: discovery-tls.p12
keystore-password-file: discovery-tls-password.key
clients:
  - id: lobby
    username: lp_lobby
    user-host: 10.0.0.11
```

La pareja `username/user-host` debe existir en `config.yml` del motor.
El puerto HTTPS debe ser diferente del SQL; configure también los controles
remotos de MariaDB. Reinicie el proxy o recargue con MariaDB detenido.
El proxy genera `discovery-secrets/lobby.key` privado. Copie exclusivamente
ese token al backend por un canal administrativo privado. Nunca copie root.
No se exportan contraseñas SQL por HTTPS. Para rotar el token, detenga el
servicio, sustituya su archivo privado y el del backend y reinicie.

## Backend Paper

Instale Client en `plugins/` de Paper 26.1.2. Su primer inicio genera
`plugins/PbtSharedDBClient/config.yml`, deshabilitado. Configure:

```yaml
config-version: 1
enabled: true
bootstrap-url: https://proxy.interno:3307/v1/discovery
client-id: lobby
certificate-sha256: REEMPLAZAR_POR_64_HEXADECIMALES_DEL_CERTIFICADO_DER
token-file: discovery-token.key
sql-password-file: ''
```

Aprovisione `discovery-token.key` con el token anterior, permisos 0600.
La huella se obtiene por canal administrativo; se comprueban certificado,
vigencia y nombre/SAN. Se exige HTTPS, firma HMAC-SHA256 de solicitud y
respuesta, nonce único y reloj sincronizado (tolerancia 30 segundos).
No hay escaneo, multicast, búsqueda de rangos ni edición de otros plugins.

Ejecute desde consola `pbtsharedbclient discover`; en juego exige
`pbtsharedb.client.admin`. Comprueba el saludo MariaDB en el host/puerto
anunciado. Con la opción vacía la autenticación SQL queda **pendiente**.
Para verificar además `SELECT 1` autenticado, aprovisione manualmente la
contraseña de esa cuenta de aplicación en un archivo privado y configure
`sql-password-file: sql-password.key`. El cliente no obtiene esa contraseña
del descubrimiento. Las claves generadas por el gestor son URL-base64 de
256 bits. Nunca se incluyen en el archivo de salida.

El resultado es `connections/lobby.yml` dentro del directorio del cliente:
host, puerto, base, usuario, URL JDBC y nivel de verificación. Configure
manualmente LuckPerms u otros plugins siguiendo sus instrucciones oficiales.
El archivo no contiene contraseña. Ante token, certificado, SQL, red o estado
READY incorrectos, no se escribe un nuevo resultado; un archivo anterior puede
seguir existiendo y su fecha indica cuándo se comprobó por última vez.

## Wings y límites

Se necesitan dos asignaciones TCP adicionales en el contenedor Velocity:
SQL y HTTPS. Verifique Egg, imagen Java 25/glibc/bibliotecas, UID/permisos,
firewall y ruta realmente alcanzable entre contenedores aislados. No suponga
que las IP del laboratorio existen en Wings ni que `127.0.0.1` llega al proxy.
El administrador configura explícitamente bootstrap, SAN y destino SQL;
no se alteran Panel, redes, Egg ni asignaciones automáticamente.

HTTPS protege el descubrimiento; las sesiones SQL siguen requiriendo una red
privada aislada, pues este gestor aún no configura TLS de MariaDB.
Los plugins del mismo UID comparten acceso a sus archivos privados.
No hay alta disponibilidad. Al detener el proxy se pierde SQL.

## Pruebas reproducibles

`./gradlew clean build --no-daemon` ejecuta los unitarios de los tres módulos
y comprueba los dos JAR, sus descriptores, clases comunes y dependencias SQL/YAML.
`bash tests/integration-linux.sh` ejecuta el laboratorio existente y la nueva
integración TLS/HMAC, repetición rechazada, token/pin/contraseña incorrectos,
saludo real, `SELECT 1` y rechazo sin READY con MariaDB real.
`python3 tests/rc_integration.py` carga los JAR en Velocity oficial y Paper
26.1.2, comprueba salida sin secretos y rechazo sin sobrescritura, y conserva
la integración LuckPerms oficial existente sobre Paper 1.21.8.
Consulte la matriz y las evidencias para resultados efectivamente ejecutados;
la existencia de estos scripts no demuestra aceptación Wings.
