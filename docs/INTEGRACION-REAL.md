# Validación de integración de v1.0.0

Estado de esta revisión (26/10/2026): no hay Docker disponible en PATH y
`wsl --list --quiet` informa que WSL no está instalado. No se ha ejecutado
MariaDB en Linux, Wings ni LuckPerms en este equipo. Compilar los tests
de integración no equivale a ejecutarlos. La versión sigue siendo SNAPSHOT.

Ejecutado en Windows en esta revisión: `gradlew.bat clean build --no-daemon
--console=plain`, correcto, con 19 pruebas unitarias, cero fallos y cero
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
Solo se amplían permisos de build y .gradle en esta copia desechable.
Las imágenes están fijadas por versión, no por digest: registre los digests
de `docker image inspect` junto al informe para identificar los binarios usados.

El script borra señales antiguas, limita las esperas y exige salida cero de
JUnit y consulta desde el cliente autorizado. Comprueba rechazo de root y de
otro origen. La suite comprueba arranque, privilegios, exclusión de un segundo
gestor, credenciales estables y persistencia usando una nueva instancia Java.
Después del dump elimina la tabla, restaura con el cliente oficial, comprueba
el texto Unicode y vuelve a detener/arrancar antes de leerlo otra vez.
Esto no prueba reinicio del contenedor, caída forzada ni restauración en un
volumen vacío. Informes: `build/reports/tests/integrationTest/`.

## Aceptación obligatoria en Wings y LuckPerms

Ejecute en un nodo aislado con datos ficticios. Registre versiones exactas de
Panel, Wings, Docker, imagen/digest, Velocity, LuckPerms y JAR del plugin/hash.
No publique secretos, dumps ni configuraciones privadas como artefactos.

| Prueba | Evidencia exigida | Estado actual |
|---|---|---|
| MariaDB Linux y SQL entre contenedores | Informe JUnit y salida del laboratorio | Pendiente |
| Reinicio desde Panel | Registro antes/después, mismo volumen y lectura del dato confirmado | Pendiente |
| Parada Wings | MariaDB termina antes del timeout, siguiente arranque READY | Pendiente |
| Restauración limpia | Dump externo, nuevo volumen, aprovisionar cuentas, importar y comparar datos | Pendiente |
| LuckPerms real | Versiones, creación de tablas y permiso leído en proxy y backends | Pendiente |
| Reconexión LuckPerms | Permiso leído/escrito después de restart SQL y restart del proxy | Pendiente |
| Aislamiento Wings | Consulta autorizada y rechazo de origen ajeno y acceso público | Pendiente |

Una tabla llamada luckperms en JUnit no acredita compatibilidad con el plugin.
En las instancias reales use la misma base/prefijo, cuentas independientes y
un UUID/grupo de prueba; conceda un nodo, compruebe propagación, haga backup,
modifique el nodo y restaure en mantenimiento con todos los clientes detenidos.
Compruebe el nodo restaurado tras reconectar todos los clientes. Siga las
guías [LuckPerms](LUCKPERMS.md), [Wings](PTERODACTYL.md) y
[restauración](COPIAS-DE-SEGURIDAD.md). No cierre la aceptación hasta adjuntar
resultados reales; Docker bridge no acredita la configuración NAT de Wings.
