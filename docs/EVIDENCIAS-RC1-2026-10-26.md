# Evidencias previas a RC1 — 26/10/2026

Trabajo limitado a `codex/integracion-linux-v1`, desde `c06f25a`. No se han
modificado ramas principales ni publicado una release/tag. Sigue SNAPSHOT.
Las [evidencias anteriores](EVIDENCIAS-LINUX-2026-10-26.md) se conservan como
histórico; el laboratorio previo no se sustituyó por mocks ni por otro motor.

## Fallo reproducido y corregido

[Actions 40309297145](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40309297145),
commit `0a79307`: build y ambas integraciones anteriores correctos. En el job
RC1 pasaron recreación del anfitrión, restauración en un volumen vacío y
comprobación de claves ausentes de los logs; falló la propagación a Paper.
En aquel primer JSON la prueba de LuckPerms quedó como «no ejecutada» aunque
se había intentado y fallado; el informe actual distingue explícitamente
«fallida» y «no ejecutada». No se contabiliza ese intento como LuckPerms correcto.

El log del proxy mostró `Failed to init storage implementation` y fallo al
inicializar la mensajería SQL antes del READY de PbtSharedDB. LuckPerms
inicializa en FIRST y cierra en LAST. Se corrigieron los eventos PbtSharedDB
con prioridades Short.MAX_VALUE y Short.MIN_VALUE, manteniendo EventTask y
su continuación: SQL debe estar autenticado antes de avanzar en el arranque,
y se conserva hasta que LuckPerms termine de cerrar el pool.

Referencias primarias de la decisión:

- [Bootstrap Velocity de LuckPerms](https://github.com/LuckPerms/LuckPerms/blob/master/velocity/src/main/java/me/lucko/luckperms/velocity/LPVelocityBootstrap.java).
- [Semántica de prioridad de Velocity](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/api/src/main/java/com/velocitypowered/api/event/Subscribe.java).
- [FIRST/MAX_VALUE-1 y LAST/MIN_VALUE+1](https://github.com/PaperMC/Velocity/blob/dev/3.0.0/proxy/src/main/java/com/velocitypowered/proxy/event/VelocityEventManager.java).

No se amplían privilegios SQL ni se desactiva autenticación, hashes, aislamiento
o protección de claves para hacer pasar LuckPerms. El mecanismo se ha probado
con las versiones indicadas; no ordena consumidores que accedan desde su
constructor ni otros listeners que usen exactamente la misma prioridad extrema.

## Primera ejecución completa correcta

[Actions 40310383074](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40310383074),
commit `112a979bb6a414c9c3bff9e4ea4e6fd84a7b2f5b`, ambos jobs correctos:

- 22 unitarios y dos ejecuciones del laboratorio anterior (tres tests Linux
  por ejecución, incluido rechazo de herramienta nativa ausente).
- Velocity real/PbtSharedDB: `docker stop`, cierre confirmado, sin socket/PID
  residual, eliminación del contenedor y recreación con igual volumen y claves.
- Volumen nuevo comprobado vacío antes de copiar configuración/JAR, cuentas
  nuevas, importación del dump, esquema/tablas/registros Unicode y binarios
  idénticos, CHECK TABLE correcto y nueva lectura tras otra recreación.
- LuckPerms oficial Velocity+Bukkit 5.5.88/build1675, Velocity 4.3.0/build44,
  Paper 1.21.8/build60, MariaDB 11.4.13. Comandos LP reales y dos observadores
  API de caché, sin SQL simulado ni recarga de storage desde el observador.
- Concesión en Velocity → TRUE en Paper; negación en Paper → FALSE en Velocity;
  nueva propagación tras reinicio SQL. Las cuentas son distintas, por IP,
  sobre una misma base/prefijo y sin privilegios globales.
- Ninguna clave SQL en logs de consola/inicialización/MariaDB; informes redactados.

[Artefacto aceptacion-rc1 12644088505](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40310383074/artifacts/12644088505),
descargado e inspeccionado. SHA-256 del ZIP calculado y coincidente con Actions:
`8346069262b2f02801a54e23cafb5f1fef0de6b3baaeab50dc780087fc2b9217`.
`resultado.json`: cinco pruebas superadas y error null.
Hash de esquema/datos comparados: `2e5c729fe8d557430921149910974ff36842a1ad91786b969cd33a7946790097`.

Tiempos UTC observados en los logs:

| Evidencia | Hora |
|---|---|
| SQL READY antes de LuckPerms | 10:51:54 |
| LuckPerms Velocity habilitado | 10:51:58 |
| Concesión proxy / caché TRUE Paper | 10:52:43 / 10:52:45 |
| Negación Paper / caché FALSE proxy | 10:52:45 / 10:52:49 |
| Nueva caché TRUE Paper tras reiniciar SQL | 10:52:57 |
| Pool Hikari cerrado antes de MariaDB | 10:52:59, orden de líneas 91 y 93 |

## Alcance y pendientes

## Ejecución final ampliada

[Actions 40311287584](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40311287584),
commit `c7ba348350c7e07104664ff7b294d1817d88e8dc`: ambos jobs correctos.
Los ZIP se descargaron y comprobaron; los XML confirman 22 unitarios y dos
ejecuciones de tres tests MariaDB, todos sin fallos, errores u omisiones.
El JSON RC confirma sus cinco criterios, incluyendo recreación del proxy con
Paper activo y comprobación de claves SQL y forwarding ausentes de logs.

| Artefacto | SHA-256 del ZIP, calculado y coincidente con Actions |
|---|---|
| [aceptacion-rc1 12645330808](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40311287584/artifacts/12645330808) | `15b5dc71447c589c52d8fc94002ba8a284df3db5a6265b5dc9a9df083e18b1d7` |
| [informes-pruebas 12644224279](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40311287584/artifacts/12644224279) | `fc6cbb6ddf00e880fa9d14baddae446eb6c4d003a971ce68f3c7c136a45b802e` |

JAR probado: SHA-256 `1ade10756835be07ab953e67863b8baa9d1355377839cc5a59054ae572c85e9d`.
Docker 28.0.4, Linux amd64, Temurin 25.0.4.1+1, MariaDB 11.4.13-ubu2404.
Imagen construida: `sha256:81a55a14bc6eee74a3888766b036fab5b2cf19508da06b401f5f40caca0305cc`.
La huella del fixture coincide con la indicada en la ejecución anterior.

El proxy recreado leyó TRUE a las 11:00:19 UTC y cambió a FALSE; Paper observó
FALSE a las 11:00:23 manteniéndose activo. Hikari cerró a las 11:00:23 y
MariaDB después, a las 11:00:24. Estos son resultados de Docker/CI, no de Wings.

## Alcance y pendientes

Se prueba la caché efectiva de permisos del grupo default con servidores
reales, sin jugadores conectados. No se ha probado login ni forwarding de
jugadores. Paper conserva online-mode=true y no hay puertos publicados al host.
No se ha arrancado Wings/Panel ni validado un Egg de producción. Docker stop
no equivale al comando Stop del Panel ni prueba SIGKILL/recuperación tras corte.

Los pasos exactos para la aceptación restante, versiones, contratos del Egg,
puerto/asignación, NAT, persistencia y restauración en un segundo servidor
están en [VALIDACION-RC1.md](VALIDACION-RC1.md). La [matriz](INTEGRACION-REAL.md)
mantiene los criterios Wings separados. No se declara listo para producción.
