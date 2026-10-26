# Corrección de verificación de dependencias (26/10/2026)

El [job fallido de Actions](https://github.com/PbtServers/PbtSharedDB-Proxy-v4/actions/runs/40303741844)
detuvo `:compileJava` al verificar
`com.google.guava:guava-parent:33.7.2-jre`, archivo
`guava-parent-33.7.2-jre.pom`, del repositorio MavenRepo. Se revisó el log del
job 121507633290. El informe HTML citado por ese log no estaba disponible:
la ejecución no conservó artefactos descargables.

En `gradle/verification-metadata.xml` faltaba por completo la entrada para
ese POM. No había un SHA-256 esperado diferente que sustituir. El POM de
la caché Gradle coincide con la descarga directa desde Maven Central y con
el archivo `.sha256` publicado allí:

- [POM oficial](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/33.7.2-jre/guava-parent-33.7.2-jre.pom).
- [SHA-256 publicado](https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/33.7.2-jre/guava-parent-33.7.2-jre.pom.sha256).
- SHA-256 calculado: `a81570d53c8710355e0216a4405afa93834b17c4cdd0ed9aa8beede3a132bbbe`.

La resolución local con `--refresh-dependencies` descubrió otras tres entradas
necesarias ausentes. Los informes HTML locales bajo
`build/reports/dependency-verification/` indicaron **Checksums are missing from
verification metadata**, no discrepancias. Se descargó cada archivo desde
`https://repo.maven.apache.org/maven2/org/junit/junit-bom/`, se calculó su
SHA-256 y se contrastó con su correspondiente archivo `.sha256` oficial:

| Artefacto | SHA-256 |
|---|---|
| 5.13.1/junit-bom-5.13.1.module | `33c07ab9724790a6e5859ba07d69117ac530439724545a81c4179e3272c75de8` |
| 5.7.0/junit-bom-5.7.0.module | `25de454b3add67654d6691b53de7593b502967b5ff549547b104d7961f1a52bd` |
| 5.13.4/junit-bom-5.13.4.pom | `d7a08a99b2502f0bb68cd4e1f984f0bf69324aaa208bd0f73366c03fc3548a42` |

Solo se añadieron esas cuatro entradas de integridad. Se conservaron los
hashes anteriores, la verificación de metadatos, las versiones y el lockfile.
No se usó generación masiva de checksums ni excepciones de confianza.

## Pruebas ejecutadas

En Windows con JDK 25 se ejecutó el equivalente del wrapper solicitado:

```powershell
.\gradlew.bat clean build --no-daemon --console=plain
.\gradlew.bat clean build --no-daemon --refresh-dependencies --console=plain
git diff --check
```

El primer build pasó después de añadir Guava y los dos `.module` JUnit.
El segundo detectó el POM JUnit 5.13.4 ausente; tras contrastarlo y añadirlo,
la repetición completa con dependencias refrescadas terminó correctamente.
Resultado final: 19 pruebas unitarias, cero fallos, errores u omisiones.
`git diff --check` sin errores. Los informes finales están en
`build/reports/tests/test/`; `clean` elimina los informes de verificación de
los intentos anteriores.

No se ha ejecutado Actions con este commit ni las pruebas Linux/MariaDB,
Wings o LuckPerms. Esta corrección valida la compilación local con integridad
activa; no acredita el resto del pipeline ni la integración SQL.
