# LuckPerms entre Velocity, Lobby, Survival y Creative

Estado de validación: MariaDB real y las consultas SQL entre contenedores
han pasado en [Actions](EVIDENCIAS-LINUX-2026-10-26.md). La suite utiliza una
base llamada luckperms, pero no carga el plugin: no acredita creación de sus
tablas, propagación, mensajería SQL ni reconexión. No hay pruebas automatizadas
de LuckPerms disponibles en este repositorio; esos puntos siguen pendientes.

Instale la edición Velocity de LuckPerms en el proxy y la edición adecuada para cada backend. La configuración de referencia oficial consultada admite `storage-method: mariadb` y `messaging-service: sql` (esta mensajería usa la misma base). Verifique esas opciones en la versión exacta instalada. No se modifica LuckPerms ni se implementa un puente de permisos propio.

Declare la base `luckperms` y cuatro usuarios independientes en config.yml de PbtSharedDB, con las IP origen realmente observadas. Inicie MariaDB y lea por acceso administrativo privado el archivo `.key` correspondiente a cada usuario. No use root. Comparta la misma base y `table-prefix` en las cuatro instancias, con contraseña propia para cada una.

Ejemplo de **fragmento** para `plugins/LuckPerms/config.yml` en Lobby (combine con el archivo generado por su versión; no lo sustituya por un fragmento):

```yaml
server: lobby
storage-method: mariadb
data:
  address: 'DIRECCION_PRIVADA_DEL_NODO:3307'
  database: luckperms
  username: lp_lobby
  password: 'PEGAR_AQUI_SOLO_LA_CREDENCIAL_PRIVADA_DE_LOBBY'
  table-prefix: 'luckperms_'
  pool-settings:
    maximum-pool-size: 5
    minimum-idle: 1
    connection-timeout: 10000
messaging-service: sql
sync-minutes: -1
auto-push-updates: true
```

En Survival y Creative cambie server/username/password. En Velocity use `server: proxy` y `lp_proxy`; solo en ese mismo contenedor puede usar `127.0.0.1:3307` con la cuenta host 127.0.0.1. Los backends usan la dirección privada realmente alcanzable, nunca su loopback. Si el proxy y los backends quieren permisos globales, conceda los nodos sin contexto server; use contextos solo para permisos particulares.

Después de conectar, cree un grupo o permiso de prueba y verifique su propagación en los cuatro servidores. Use `lp networksync` según la edición instalada. Revise errores de conexión y que no existan prefijos distintos o datos locales H2 que todavía no se hayan migrado. Cambiar storage-method no migra automáticamente permisos previos: siga la exportación/importación oficial de su versión y conserve la copia previa.

LuckPerms puede intentar conectarse antes de que MariaDB esté listo en el propio proxy. Verifique su reconexión tras READY y su comportamiento al reiniciar MariaDB. No se ofrece orden de inicialización garantizado ni se ha ejecutado una prueba con LuckPerms localmente. Pruebe en un nodo aislado antes de usar esta arquitectura como dependencia de permisos.

Compartir permisos no comparte economía o inventarios. Esas funciones requieren plugins con soporte SQL compartido, mecanismos de concurrencia y esquema propios. Use una base y cuentas separadas por aplicación; no modifique tablas de LuckPerms para guardar esos datos.

Fuente: [configuración original de LuckPerms](https://github.com/LuckPerms/LuckPerms/blob/master/bukkit/src/main/resources/config.yml), secciones de almacenamiento MariaDB y mensajería SQL consultadas durante la implementación.
