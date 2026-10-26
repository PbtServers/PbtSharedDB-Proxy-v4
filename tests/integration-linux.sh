#!/usr/bin/env bash
set -Eeuo pipefail
cd "$(dirname "$0")/.."
command -v docker >/dev/null || { echo 'El laboratorio exige Docker con contenedores Linux.' >&2; exit 1; }
docker info >/dev/null
if docker container inspect pbtsharedb-integracion >/dev/null 2>&1 || docker network inspect pbtsharedb-pruebas >/dev/null 2>&1; then
  echo 'Ya existe un recurso del laboratorio; no se modifica. Revise su propietario.' >&2
  exit 1
fi
cleanup() {
  docker logs pbtsharedb-integracion 2>/dev/null || true
  docker rm -f pbtsharedb-integracion >/dev/null 2>&1 || true
  docker network rm pbtsharedb-pruebas >/dev/null 2>&1 || true
  if test -d build/remote-probe; then
    # Retirar solo archivos conocidos y el directorio vacío, sin borrado recursivo.
    # El runner no puede recorrer 0700/mysql, tampoco en la siguiente ejecución.
    docker run --rm --user root -v "$PWD/build:/results" --entrypoint /bin/sh mariadb:11.4.13 -ec \
      'rm -f /results/remote-probe/client.cnf /results/remote-probe/ready /results/remote-probe/passed /results/remote-probe/result; rmdir /results/remote-probe'
  fi
}
trap cleanup EXIT
docker build -t pbtsharedb-integracion -f tests/Dockerfile .
mkdir -p build/integration-gradle
# No aceptar señales/resultados de una ejecución anterior en un runner reutilizado.
# La preparación también debe funcionar con resultados del UID mysql anterior.
# Eliminar primero la credencial privada, antes de ampliar permisos de resultados.
docker run --rm --user root -v "$PWD/build:/results" --entrypoint /bin/sh pbtsharedb-integracion -ec \
  'if test -d /results/remote-probe; then rm -f /results/remote-probe/client.cnf /results/remote-probe/ready /results/remote-probe/passed /results/remote-probe/result; rmdir /results/remote-probe; fi; chmod -R a+rwX /results'
docker network create --subnet 172.30.85.0/24 pbtsharedb-pruebas
docker run -d --name pbtsharedb-integracion --network pbtsharedb-pruebas --ip 172.30.85.10 \
  -v "$PWD/build:/work/build" -e PBT_REMOTE_PROBE=1 \
  -e GRADLE_USER_HOME=/work/build/integration-gradle pbtsharedb-integracion
for intento in $(seq 1 600); do
  # La coordinación es 0700/mysql: el UID del runner no debe leer credenciales.
  if docker exec pbtsharedb-integracion test -f /work/build/remote-probe/ready 2>/dev/null; then break; fi
  if test "$(docker inspect -f '{{.State.Running}}' pbtsharedb-integracion)" != true; then
    docker logs pbtsharedb-integracion
    exit 1
  fi
  sleep 1
done
docker exec pbtsharedb-integracion test -f /work/build/remote-probe/ready
docker run --rm --network pbtsharedb-pruebas --ip 172.30.85.11 --user mysql \
  -v "$PWD/build/remote-probe:/probe" --entrypoint /bin/sh mariadb:11.4.13 -ec \
  'mariadb --defaults-file=/probe/client.cnf --protocol=tcp --connect-timeout=5 -e "SELECT texto FROM dato WHERE id=1" > /probe/result; if mariadb --defaults-file=/probe/client.cnf --protocol=tcp --connect-timeout=5 --user=root -e "SELECT 1"; then exit 1; fi'
docker run --rm --network pbtsharedb-pruebas --ip 172.30.85.12 --user mysql \
  -v "$PWD/build/remote-probe:/probe" --entrypoint /bin/sh mariadb:11.4.13 -ec \
  'if mariadb --defaults-file=/probe/client.cnf --protocol=tcp --connect-timeout=5 -e "SELECT 1"; then exit 1; fi; touch /probe/passed'
codigo=$(timeout 600 docker wait pbtsharedb-integracion)
docker logs pbtsharedb-integracion
test "$codigo" = 0
