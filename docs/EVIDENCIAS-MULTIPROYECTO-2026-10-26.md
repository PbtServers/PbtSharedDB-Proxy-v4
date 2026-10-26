# Evidencias de la migración multiproyecto

Rama `codex/integracion-linux-v1`; no se han modificado ramas principales ni
publicado una release. Las evidencias anteriores se conservan en sus documentos.

## Correcciones y ejecución local

- `1b82350`: migración conservando fuentes e historial a proxy/client/common;
  corrección de variable JDBC duplicada en `DiscoveryClient.discover`.
- La exclusión de la etiqueta `integration` se aplica solo a `test`, evitando
  excluir también los casos de `integrationTest`.
- Build ejecutado en Windows/Java 25: `gradlew.bat clean build --no-daemon
  --console=plain`, sin generación de metadatos ni desactivación de verificación.
  Los tres módulos compilan; 29 unitarios (22 proxy, 3 cliente, 4 comunes),
  cero fallos, errores u omisiones según XML.
- `python tests/verify_dependency_additions.py`: 133 artefactos anteriores
  conservan exactamente sus SHA-256; 123 nuevos descargados y contrastados
  con repositorios oficiales Maven Central/PaperMC/Mojang. Locks por módulo.
- `python -m py_compile tests/rc_integration.py tests/velocity_smoke.py
  tests/verify_dependency_additions.py` y `bash -n tests/integration-linux.sh`
  ejecutados correctamente. Estas comprobaciones de sintaxis no ejecutan Docker.

## Primera ejecución Linux

[Actions 40315948796](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40315948796),
commit `1b82350`, job build `121548459986` correcto:

- Build estricto, 29 unitarios y auditoría oficial correctos.
- Carga real de Proxy en Velocity 4.3.0 build 44 y comandos correctos.
- Dos ejecuciones del laboratorio Docker: cada una tiene tres tests del motor
  y un test compuesto del cliente con MariaDB 11.4.13 real. Los cuatro XML
  descargados tienen cero fallos, errores y omisiones.
- La integración del cliente verifica HTTPS con certificado fijado, HMAC de
  solicitud/respuesta, rechazo de repetición, token/pin incorrectos, saludo
  MariaDB real, `SELECT 1` con cuenta de aplicación, clave SQL incorrecta,
  archivo sin secretos y rechazo cuando el motor deja de estar READY.

Artefactos descargados y comprobados:

| Artefacto | ID | SHA-256 del ZIP |
|---|---|---|
| informes-pruebas | 12647445147 | `9756b347c7060ff95609122dcc0ac9373583ab4e727b7139366fdf345c3e6153` |
| PbtSharedDB-1.0.0-plugins | 12645639649 | `7f5a5071c37aa8132061b54795c6f285c98a94dbd1ab0ac90fcce60e7d57bbeb` |

El ZIP de plugins contiene exactamente los dos instalables:

| Archivo | Bytes | SHA-256 |
|---|---|---|
| PbtSharedDB-Proxy-1.0.0.jar | 3859122 | `defe2a902cce3fec96f7a94e62fa5aca91b2bd479c38fa33d64197d812ff30d6` |
| PbtSharedDB-Client-1.0.0.jar | 1112565 | `b1aa9481c0eba7bea61990c9e9c8012aec9b426ec19168261fcf9b987b3008a5` |

Se inspeccionaron ambos ZIP/JAR: protocolo común, SafeFiles, Connector/J y
SnakeYAML incluidos; descriptor correcto en cada plataforma; las API Paper
y Velocity no se empaquetan. El cliente no incluye el motor del proxy.
Common solo produce un artefacto interno al grafo de Gradle, sin distribución.

El job RC de esa ejecución falló antes de completar sus criterios: una variable
local `fingerprint` ocultaba la función homónima que compara tablas y registros.
No se declara superada esa ejecución RC. `1de7a21` renombra la variable a
`certificate_fingerprint`, conserva la comparación y repite CI completo.

## Ejecución completa correcta

[Actions 40316432019](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40316432019),
commit `1de7a21dcbbc8c8188a925b687b24961f059c582`, ambos jobs correctos.
Se descargaron e inspeccionaron los tres artefactos finales, no solo el estado
de Actions. Los XML repiten 29 unitarios y dos ejecuciones de cuatro casos de
integración, sin fallos, errores u omisiones. Los JAR tienen exactamente los
mismos hashes y tamaños que la tabla anterior.

| Artefacto final | ID | SHA-256 del ZIP descargado |
|---|---|---|
| aceptacion-rc1 | 12647700522 | `32faa91c609cf79f18bbbdf1feb64e0735c8a408f9cae0923db4c5b52650ff84` |
| informes-pruebas | 12647242229 | `332855bb4260a4820784aa732f46107da81b35c7a7f168ff84081cafbfb64364` |
| PbtSharedDB-1.0.0-plugins | 12646338649 | `b76db340879b248454ffa0146eecf5bda2d8eaba46a24a013b8dc48335cba09c` |

`resultado.json` registra `error: null` y seis criterios `superada`:

1. `recreacion_contenedor`: detener, eliminar y recrear el anfitrión con el
   mismo volumen, conservando claves, tablas y registros.
2. `restauracion_volumen_vacio`: volumen comprobado vacío, inicialización
   nueva y restauración lógica; esquema/registros, CHECK TABLE y otra
   recreación con lectura correcta. No se restauran cuentas root del origen.
3. `luckperms_bidireccional`: LuckPerms oficial 5.5.88 build 1675 en Velocity
   4.3.0 build 44 y Paper 1.21.8 build 60, escritura SQL y propagación efectiva
   TRUE/FALSE en ambas cachés del grupo default, sin simular JDBC.
4. `reconexion_luckperms`: reinicio SQL y recreación del proxy manteniendo
   Paper activo, con nueva propagación y lectura de permisos.
5. `cliente_paper26_descubrimiento`: Client cargado en Paper 26.1.2 build 74
   oficial, en otro contenedor; HTTPS/HMAC y `SELECT 1` autenticado con cuenta
   de aplicación. Archivo propio sin secretos; token y pin incorrectos
   rechazados sin sobrescribirlo; configuración ajena sin cambios.
6. `logs_sin_secretos`: búsqueda de claves SQL, tokens, contraseña PKCS12 y
   forwarding.secret en consolas y registros MariaDB, informes saneados.

El log `pbt-rc-53090abd9a-cliente26.log` acredita carga y
`Conexión generada sin contraseña: SELECT 1 autenticado`, seguidas por dos
rechazos `No se generan datos nuevos`. Los logs del proxy y Paper contienen
los cambios `RC_CACHE ... TRUE/FALSE` de LuckPerms.

Entorno registrado: Docker 28.0.4, Linux amd64, imagen del laboratorio
`sha256:4602d81414116e49faafdf54125bb89925644e399ec7b66ec4ea01b2b266a4cd`,
Temurin 25.0.4.1+1, MariaDB 11.4.13 Ubuntu 24.04. No se publican volúmenes,
dumps ni archivos de claves. Para repetir en Linux con Docker/JDK 25:

```sh
bash ./gradlew clean build --no-daemon
python3 tests/verify_dependency_additions.py
bash tests/integration-linux.sh
bash tests/integration-linux.sh
python3 tests/rc_integration.py
git diff --check
```

## Límites y aceptación Wings

No se han ejecutado Wings ni Panel. Docker no acredita Egg, asignaciones/NAT,
firewall real, montaje del nodo, Stop/Start del Panel ni jugadores conectados.
LuckPerms se comprueba en el escenario oficial existente con Paper 1.21.8;
la prueba Client usa Paper 26.1.2 separadamente. No se afirma LuckPerms en
Paper 26.1.2. La aceptación restante está en [VALIDACION-RC1.md](VALIDACION-RC1.md)
y la [matriz](INTEGRACION-REAL.md), con dos puertos y pruebas de errores del
descubrimiento. Los archivos de versión 1.0.0 siguen siendo candidatos.
