# Validación de integración de v1.0.0

Estado de esta revisión (26/10/2026): [Actions de dfcf60d](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40304776222)
confirma la verificación SHA-256, `clean build` y carga real de Velocity en
Ubuntu 24.04.5/Temurin 25.0.4+1. Tras corregir los permisos del laboratorio,
su coordinación privada y el reinicio con conexiones TCP en TIME_WAIT,
[Actions de 6911107](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40306280695)
completó el laboratorio MariaDB real: 20 unitarios y dos tests de integración,
sin fallos, errores ni omisiones, comprobados en los XML descargados.
Consulte [las evidencias y los fallos corregidos](EVIDENCIAS-LINUX-2026-10-26.md).
La [ejecución final de 78e7b89](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40306775835)
también terminó correctamente y repitió el laboratorio dos veces consecutivas
en el mismo checkout: dos XML de integración con dos tests cada uno y cero
fallos, errores u omisiones; 20 unitarios correctos.

En el equipo Windows no hay Docker disponible en PATH y
`wsl --list --quiet` informa que WSL no está instalado. La validación SQL
se ha ejecutado en Actions; Wings y LuckPerms real siguen pendientes.
La versión sigue siendo SNAPSHOT.

Ejecutado en Windows en esta revisión: `gradlew.bat clean build --no-daemon
--console=plain`, correcto, con 20 pruebas unitarias, cero fallos y cero
omisiones. También pasan `bash -n tests/integration-linux.sh` y
`git diff --check`. La comprobación de sintaxis no ejecuta Docker.

## Laboratorio Linux reproducible

En una copia de trabajo desechable, con Docker, Bash, GNU timeout y JDK 25:

```sh
bash ./gradlew clean build --no-daemon
bash tests/integration-linux.sh
```

CI utiliza el mismo script. Reserva la red 172.30.85.0/24 y los nombres
`pbtsharedb-integracion` y `pbtsharedb-pruebas`; rechaza recursos existentes.
No publica puertos al host. El servidor y los clientes SQL se ejecutan como
mysql; la limpieza de la credencial compartida usa un contenedor efímero root.
La preparación y limpieza de build usan contenedores efímeros root; las
credenciales de coordinación se eliminan antes de ampliar permisos de resultados.
No se amplían permisos del datadir ni secretos del plugin. Las fuentes se
copian durante docker build a /work con propietario mysql; no se monta el
repositorio del host sobre ese directorio. Reconstruya la imagen al cambiar código.
Las imágenes están fijadas por versión, no por digest: registre los digests
de `docker image inspect` junto al informe para identificar los binarios usados.

El script borra señales antiguas, comprueba ready dentro del contenedor como
mysql, limita las esperas y exige salida cero de
JUnit y consulta desde el cliente autorizado. Comprueba rechazo de root y de
otro origen. La suite comprueba arranque, privilegios, exclusión de un segundo
gestor, credenciales estables y persistencia usando una nueva instancia Java.
Después del dump elimina la tabla, restaura con el cliente oficial, comprueba
el texto Unicode y vuelve a detener/arrancar antes de leerlo otra vez.
Esto no prueba reinicio del contenedor, caída forzada ni restauración en un
volumen vacío. Informes: `build/reports/tests/integrationTest/`.
CI repite el laboratorio en la misma copia de trabajo con `--rerun-tasks` y
conserva además los XML/HTML de la primera ejecución con sufijo `-primera`.

## Aceptación obligatoria en Wings y LuckPerms

Ejecute en un nodo aislado con datos ficticios. Registre versiones exactas de
Panel, Wings, Docker, imagen/digest, Velocity, LuckPerms y JAR del plugin/hash.
No publique secretos, dumps ni configuraciones privadas como artefactos.

| Prueba | Evidencia exigida | Estado actual |
|---|---|---|
| SHA-256 y build Linux | Actions 40304776222, job 121511073969, paso 5 | Superada en dfcf60d |
| Carga de Velocity Linux sin SQL | Mismo job, paso 6 | Superada en dfcf60d |
| Inicialización y JDBC TCP autenticado | Actions 40306280695, XML MariaDbIntegrationTest | Superada en 6911107 |
| Persistencia tras detener/crear otro gestor | Mismo test, credenciales/marca estables y texto Unicode | Superada en 6911107 |
| Apagado gestionado y nuevo arranque | Mismo test, stop completado y reinicio real sobre igual datadir | Superada en 6911107; no acredita Wings |
| Backup y restauración lógica | Mismo test, DROP TABLE, importación, lectura y nuevo reinicio | Superada en 6911107 |
| SQL entre contenedores del laboratorio | Cliente 172.30.85.11 lee el dato; root y origen .12 rechazados | Superada en 6911107; no acredita NAT Wings |
| Repetición del laboratorio sin señales antiguas | Actions 40306775835, ambos informes JUnit y pasos 8/9 correctos | Superada en 78e7b89 |
| Reinicio desde Panel | Registro antes/después, mismo volumen y lectura del dato confirmado | Pendiente |
| Parada Wings | MariaDB termina antes del timeout, siguiente arranque READY | Pendiente |
| Restauración limpia | Dump externo, nuevo volumen, aprovisionar cuentas, importar y comparar datos | Pendiente |
| LuckPerms real | Versiones, creación de tablas y permiso leído en proxy y backends | Pendiente |
| Reconexión LuckPerms | Permiso leído/escrito después de restart SQL y restart del proxy | Pendiente |
| Aislamiento Wings | Consulta autorizada y rechazo de origen ajeno y acceso público | Pendiente |

Una base llamada luckperms en JUnit no acredita compatibilidad con el plugin.
En las instancias reales use la misma base/prefijo, cuentas independientes y
un UUID/grupo de prueba; conceda un nodo, compruebe propagación, haga backup,
modifique el nodo y restaure en mantenimiento con todos los clientes detenidos.
Compruebe el nodo restaurado tras reconectar todos los clientes. Siga las
guías [LuckPerms](LUCKPERMS.md), [Wings](PTERODACTYL.md) y
[restauración](COPIAS-DE-SEGURIDAD.md). No cierre la aceptación hasta adjuntar
resultados reales; Docker bridge no acredita la configuración NAT de Wings.
