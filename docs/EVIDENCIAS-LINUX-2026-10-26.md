# Evidencias Linux del 26 de octubre de 2026

Este documento conserva el estado histórico hasta `78e7b89`. Para recreación
del anfitrión, volumen vacío y LuckPerms oficial, consulte las
[evidencias posteriores de RC1](EVIDENCIAS-RC1-2026-10-26.md).

Las ejecuciones enlazadas identifican el commit exacto. Un paso correcto no
convierte en correcto el job global ni acredita pruebas posteriores omitidas.

## Verificación SHA-256 confirmada

[Actions 40304776222](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40304776222),
commit `dfcf60d93cc776da5ffbab5a3aa1bbae26f9341e`, job `121511073969`:

- Ubuntu 24.04.5 x86_64, Temurin 25.0.4+1 y Gradle Wrapper 9.1.0.
- `bash ./gradlew clean build --no-daemon`: correcto en 46 s,
  incluyendo `:compileJava`, `:test` y `:check`, con verificación activa.
- Carga y comandos de Velocity 4.3.0 build 44: correctos, motor SQL deshabilitado.
- Laboratorio Docker: fallido antes de JUnit porque `/work`, montado desde el
  host, no era escribible por mysql. No constituye validación SQL.

## Fallos encontrados al ejecutar el laboratorio

1. `4de64e7` copia las fuentes a `/work` con propietario mysql y monta solo
   build. [Actions 40305392382](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40305392382)
   superó el build y Velocity y ejecutó dos tests SQL, uno fallido: el runner
   no podía ver la señal privada `ready` dentro de un directorio 0700/mysql.
   La espera de la prueba remota agotó su plazo antes de backup/restauración.
2. `90456c5` consulta esa señal con `docker exec` bajo mysql, sin ampliar sus
   permisos. [Actions 40305877479](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40305877479)
   ejecutó las consultas entre contenedores: el autorizado leyó el dato,
   root fue rechazado con 1045 y el origen 172.30.85.12 fue rechazado con 1130
   (el cliente lo muestra dentro de un diagnóstico 2002 de handshake).
   JUnit volvió a fallar: `BindException` al reiniciar después del backup,
   en `MariaDbIntegrationTest.java:55`. La restauración aún no se ejecutó.
3. `6911107` habilita SO_REUSEADDR en la sonda de puerto Linux para permitir
   direcciones con conexiones cerradas en TIME_WAIT. Conserva el rechazo de
   listeners activos; añade una prueba con listener reutilizable y consultas
   tanto a 127.0.0.1 como a 0.0.0.0. Es el comportamiento previsto por
   [ServerSocket.setReuseAddress de Java 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/ServerSocket.html#setReuseAddress(boolean)).

No se deshabilitaron hashes ni se cambiaron versiones, credenciales, permisos
de producción, hosts autorizados o autenticación para hacer pasar las pruebas.
Los cambios de permisos afectan solo al espacio desechable de build del laboratorio.

## Ejecución SQL completa superada

[Actions 40306280695](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40306280695),
commit `691110776707eb000a4639de7deac486fd310a8d`, job `121516069481`:

- Build Linux y carga de Velocity correctos; laboratorio SQL correcto en 30 s.
- XML verificados: 20 unitarios y dos tests de integración; cero fallos,
  errores y omisiones. `neverReinitializesAnExistingUnownedDirectory` y
  `realInitializationPersistencePrivilegesBackupAndRestore` ejecutados.
- Inicialización real sin root, disponibilidad JDBC, privilegios mínimos,
  lectura entre contenedores y rechazo root/origen no autorizado.
- Dump lógico real, parada del proceso gestionado, nuevo gestor sobre el
  mismo datadir, credenciales/marca estables y dato Unicode persistente.
- Eliminación de tabla, restauración mediante cliente oficial, comparación
  del texto y lectura posterior a otra parada/arranque real.
- Exclusión de un gestor competidor sin detener el proceso propietario.

[Artefacto informes-pruebas 12642406792](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40306280695/artifacts/12642406792):
ZIP descargado y SHA-256 calculado, coincidente con el publicado por Actions:
`dd88c7f6197760095e9de1f80f1f3a15a081c7370fc638584f0612f4b43c38c8`.
Contiene los XML bajo test-results/ y los informes HTML bajo reports/tests/.
Los artefactos de Actions tienen caducidad; conserve una copia privada si
necesita auditar después de su vencimiento.

Imágenes identificadas en el log de construcción:

- MariaDB 11.4.13: `sha256:250e1609e93c0e6aecf64bfbab2d84d5e89d2481540fe77447ff6c855c3b1873`.
- Temurin 25-jdk-jammy: `sha256:07a4ee373cd86f337e62cb8149f1e0a9f08aaf3a18c27509a493dda119fb480c`.

## Repetición en el mismo checkout confirmada

[Actions 40306775835](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40306775835),
commit `78e7b89e149d7b5b5609097c7bef6af138b1e428`, job `121517713358`,
conclusión global **success**. Build y Velocity correctos. El laboratorio se
ejecutó dos veces consecutivas (36 s y 24 s), con `--rerun-tasks`, sin usar
resultados de tests previos. Ambos recorridos SQL y la limpieza terminaron
correctamente. El segundo vuelve a inicializar un fixture nuevo: no representa
persistencia de un datadir entre contenedores; esa aceptación sigue pendiente.

Se corrigió la limpieza para eliminar solo los cuatro archivos de coordinación
conocidos y su directorio vacío desde un contenedor efímero root, sin borrado
recursivo. La preparación retira primero las credenciales residuales y después
ajusta los permisos de build, incluso si sus resultados pertenecen a mysql.
Los procesos Java/MariaDB y los clientes continúan ejecutándose como mysql.

[Artefacto informes-pruebas 12642912687](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40306775835/artifacts/12642912687):
ZIP descargado; SHA-256 calculado y publicado coincidentes:
`ebe844cae89071cee5c0f2e43d84bc61d46f09b60476f8a06820041cdc108bda`.

XML examinados directamente dentro del ZIP:

| Ruta bajo test-results/ | Tests | Fallos/errores/omisiones |
|---|---|---|
| test/ (cuatro suites) | 20 | 0/0/0 |
| integrationTest-primera/ | 2 | 0/0/0 |
| integrationTest/ | 2 | 0/0/0 |

Se conservaron además ambos informes HTML. Las imágenes base tienen los mismos
digests indicados arriba; la imagen del laboratorio construida tiene ID
`sha256:c943429a01f99df9a5b02dceaaa156a30084bed4d95caa0e03ad6d75a8e249e2`.
Las consultas remotas se ejecutaron y los rechazos root/origen ajeno aparecen
en ambos recorridos del log. No se exportaron credenciales ni dumps.

Comprobaciones locales finales: build con 20 unitarios, `bash -n` del script
y `git diff --check`, correctos. No se contabilizan los intentos fallidos
anteriores como tests SQL superados.

## Límites de aceptación

El equipo local Windows sigue sin Docker ni WSL instalado. La ejecución local
de `bash tests/integration-linux.sh` se intentó y falló por Docker ausente;
las pruebas SQL se ejecutan en el runner Linux de Actions.

No existe un test automatizado que cargue LuckPerms real en este repositorio.
El nombre de base `luckperms` es solo un fixture SQL. No se han ejecutado
propagación de permisos, mensajería ni reconexión del plugin LuckPerms.
Tampoco se dispone de un nodo Wings/Panel: reinicio del contenedor, timeout
de Wings, NAT/firewall de producción y restauración en volumen vacío siguen
pendientes. Consulte la [matriz de aceptación](INTEGRACION-REAL.md).
