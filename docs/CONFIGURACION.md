# Configuración

El primer arranque genera `plugins/pbtsharedb/config.yml`. Se preserva cualquier archivo existente. YAML se carga con constructor seguro, sin claves duplicadas, con límites de tamaño y alias. Una configuración inválida impide iniciar MariaDB. No se reescriben secretos al recargar.

| Opción | Significado y restricciones |
|---|---|
| `config-version` | 1. Versiones distintas se rechazan sin sobrescribir. No hay migración destructiva automática |
| `enabled` | false por defecto. Debe ser true para iniciar manual o automáticamente |
| `auto-start` | false. Con enabled=true inicia durante el evento de inicialización asíncrono del proxy |
| `runtime` | `preinstalled` o `portable` |
| `binary-path` | Ruta absoluta de mariadbd; preinstalled permite directorios del sistema o `/opt/mariadb/bin` |
| `tools-directory` | Directorio absoluto de mariadb y mariadb-dump de la misma versión |
| `install-db-path` | Ruta absoluta de mariadb-install-db; también admite `/opt/mariadb/scripts` |
| `automatic-install` | false. Solo portable, x86_64 glibc, artefacto oficial 11.4.13 fijado en código |
| `data-directory` | `data` o subdirectorio suyo, siempre bajo el plugin, sin enlaces simbólicos |
| `host` | `127.0.0.1` por defecto; alternativamente `0.0.0.0` con controles adicionales |
| `port` | 3306; rango 1024–65535 |
| `remote-access` | Confirmación explícita necesaria para host=0.0.0.0 |
| `network-isolation-confirmed` | Confirmación administrativa de firewall/red ya configurados; el plugin no puede verificarlos por completo |
| `allocated-port` | Debe coincidir con port en modo remoto. El plugin no consulta el Panel ni comprueba NAT externo |
| `startup-timeout` | 90 segundos; 5–600 por fase de arranque, más inicialización y aprovisionamiento |
| `shutdown-timeout` | 60 segundos; 5–600 por espera; puede haber segunda espera tras SIGTERM |
| `initialization.enabled` | true. Solo inicializa un datadir vacío; no adopta ni actualiza datos existentes |
| `databases` | Lista de bases de aplicación, vacía por defecto; prohíbe bases de sistema y test |
| `users` | Lista de mapas name, host y database; cuenta independiente por servidor/aplicación/host |
| `backups.retention` | 7; 1–365 copias completas locales |
| `backups.timeout` | 600 segundos; 5–86400 |
| `backups.minimum-free-mib` | 512; 64–1048576. Además exige margen calculado de datos |
| `logs.error-file` | mariadb.log, nombre simple terminado en .log, dentro del plugin |

La imagen debe contener MariaDB **11.4.13** para servidor y herramientas. No se cambia de versión silenciosamente ni se actualiza el datadir. Portable ignora las rutas preinstalled, aunque deben mantener una sintaxis válida.

Ejemplo de aprovisionamiento (sustituya las direcciones por las realmente observadas en el nodo):

```yaml
databases: [luckperms]
users:
  - name: lp_proxy
    host: 127.0.0.1
    database: luckperms
  - name: lp_lobby
    host: 172.18.0.12
    database: luckperms
  - name: lp_survival
    host: 172.18.0.13
    database: luckperms
  - name: lp_creative
    host: 172.18.0.14
    database: luckperms
```

Nombres: letra ASCII inicial y hasta 64 caracteres de letras, dígitos y `_`; usuarios hasta 32. No se admiten comillas, guiones, opciones de consola ni patrones `%` en hosts. Host SQL es IPv4 explícita; DNS, CIDR e IPv6 quedan pendientes. Cada nombre+host aparece una sola vez y recibe acceso a una sola base. Una base puede compartirse entre varias cuentas. Los nombres reservados y el usuario del sistema no se pueden aprovisionar.

Las contraseñas se generan como 32 bytes aleatorios y se guardan en `secrets/<usuario>-<IPv4>.key`. El usuario `pbt_health@127.0.0.1` solo recibe USAGE y sirve para SELECT 1 autenticado. No comparta `health.key`.

La recarga exige MariaDB detenido. Secuencia: `stop`, editar config, `reload`, `start`. El arranque reafirma los permisos declarados y las contraseñas existentes. Quitar usuarios de YAML no los borra: se rechaza el arranque si aparecen cuentas no declaradas. Un administrador debe revocarlas/eliminarlas por el socket antes de quitar su declaración. No se borran bases automáticamente.

Opciones internas: socket/PID bajo run/, sin resolución DNS, sin LOCAL INFILE, sin binlog/general log/slow log, secure-file-priv limitado al directorio privado file-io/, 100 conexiones, buffer InnoDB 128 MiB y flush de transacción durable. Las cuentas de aplicación no reciben FILE. No se usa el valor NULL de MySQL, cuyo comportamiento no es equivalente en MariaDB. No se permite inyectar opciones nativas libres. El límite de memoria Docker debe reservar margen para Java, MariaDB, clientes y buffers por conexión. Los logs requieren rotación externa mientras MariaDB está detenido.
