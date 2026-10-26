# Copias y restauración

`pbtsharedb backup` usa el `mariadb-dump` oficial 11.4.13 conectado por socket con autenticación del UID. Solo exporta las bases declaradas, nunca mysql ni cuentas/secretos. Incluye tablas, datos y triggers por defecto; no exporta rutinas ni eventos. No copie archivos de un datadir activo como sustituto de esta copia.

Se utiliza `--lock-all-tables --quick --hex-blob --databases`. El bloqueo global de lectura dura toda la copia; evita escrituras/DDL concurrentes y ofrece consistencia también fuera de InnoDB. No se promete un backup sin interrupciones. El timeout corta el cliente de dump y libera su sesión/bloqueo. Programe copias en una ventana de mantenimiento; no hay planificador automático en esta versión.

Antes de copiar se consulta el tamaño de tablas y se exige espacio libre para el doble de esa estimación (mínimo 64 MiB) más `minimum-free-mib`. Es una estimación, no una garantía del tamaño del SQL; datos binarios pueden expandirse y el espacio puede consumirse simultáneamente. El código de salida y stderr se comprueban; cualquier diagnóstico impide dar la copia por válida. El `.partial` se renombra atómicamente a `.sql` solo al completar. Se retienen las últimas N copias con el patrón propio del plugin y no se siguen enlaces de datos/configuración.

Los archivos son privados 0600 bajo backups/, el directorio 0700. Un dump no contiene contraseñas SQL del gestor, pero sí puede contener datos personales o secretos almacenados por las aplicaciones. No lo publique ni lo adjunte a incidencias. Conserve otra copia cifrada fuera del volumen/nodo, y pruebe periódicamente la restauración. La retención local no protege de borrar el servidor en el Panel.

## Restauración lógica en un entorno aislado

La prueba `MariaDbIntegrationTest` implementa esta secuencia con MariaDB real: inicialización, INSERT, dump, reinicio, verificación de persistencia, DROP de tabla de prueba, restauración del dump y comprobación del registro. Esa prueba no se ejecutó localmente por falta de Linux/Docker; no se afirma restauración validada en producción.

1. Detenga clientes y proxy, conserve íntegro el directorio actual. No restaure encima de producción sin validar. Prepare una copia aislada de la imagen, volumen y plugin con el mismo motor 11.4.13.
2. Declare las bases/usuarios necesarios. Arranque el gestor para inicializar un datadir vacío y espere READY. Copie el dump por un canal privado, compruebe su procedencia e integridad (mantenga un SHA-256 externo al exportar).
3. Dentro del contenedor, como **el mismo usuario sin root que Velocity**, use el cliente oficial con socket absoluto. La cuenta administrativa coincide con `id -un`:

```sh
mariadb --no-defaults --protocol=socket \
  --socket=/home/container/plugins/pbtsharedb/run/mariadb.sock \
  --user="$(id -un)" < /ruta/privada/copia.sql
```

4. Exija código de salida 0. Verifique tablas, recuentos, un registro conocido y funcionalidad de cada aplicación. Reinicie el gestor y repita una lectura. Restaure también la configuración de clientes y secretos desde un respaldo privado separado si necesita conservar credenciales anteriores.
5. Solo tras validar, planifique el cambio en producción, manteniendo una copia del estado previo y los clientes detenidos durante la importación. Los dumps contienen CREATE DATABASE/USE/DROP TABLE de sus bases: importar puede sobrescribir tablas existentes. No ejecute SQL de origen no confiable.

No hay comando de restauración en Minecraft ni recuperación automática destructiva. La restauración física, actualización de motor y recuperación de corrupción InnoDB quedan bajo administración especializada; nunca borre ibdata/redo logs para «arreglar» un arranque.
