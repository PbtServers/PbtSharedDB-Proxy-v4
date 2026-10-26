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
    docker run --rm --user root -v "$PWD/build/remote-probe:/probe" --entrypoint /bin/sh mariadb:11.4.13 -c 'rm -f /probe/client.cnf'
  fi
}
trap cleanup EXIT
docker build -t pbtsharedb-integracion -f tests/Dockerfile .
mkdir -p build/integration-gradle
# No aceptar señales/resultados de una ejecución anterior en un runner reutilizado.
rm -f build/remote-probe/ready build/remote-probe/passed build/remote-probe/result build/remote-probe/client.cnf
chmod -R a+rwX build
docker network create --subnet 172.30.85.0/24 pbtsharedb-pruebas
docker run -d --name pbtsharedb-integracion --network pbtsharedb-pruebas --ip 172.30.85.10 \
  -v "$PWD/build:/work/build" -e PBT_REMOTE_PROBE=1 \
  -e GRADLE_USER_HOME=/work/build/integration-gradle pbtsharedb-integracion
for intento in $(seq 1 120); do
  if test -f build/remote-probe/ready; then break; fi
  if test "$(docker inspect -f '{{.State.Running}}' pbtsharedb-integracion)" != true; then
    docker logs pbtsharedb-integracion
    exit 1
  fi
  sleep 1
done
test -f build/remote-probe/ready
docker run --rm --network pbtsharedb-pruebas --ip 172.30.85.11 --user mysql \
  -v "$PWD/build/remote-probe:/probe" --entrypoint /bin/sh mariadb:11.4.13 -ec \
  'mariadb --defaults-file=/probe/client.cnf --protocol=tcp --connect-timeout=5 -e "SELECT texto FROM dato WHERE id=1" > /probe/result; if mariadb --defaults-file=/probe/client.cnf --protocol=tcp --connect-timeout=5 --user=root -e "SELECT 1"; then exit 1; fi'
docker run --rm --network pbtsharedb-pruebas --ip 172.30.85.12 --user mysql \
  -v "$PWD/build/remote-probe:/probe" --entrypoint /bin/sh mariadb:11.4.13 -ec \
  'if mariadb --defaults-file=/probe/client.cnf --protocol=tcp --connect-timeout=5 -e "SELECT 1"; then exit 1; fi; touch /probe/passed'
codigo=$(timeout 600 docker wait pbtsharedb-integracion)
docker logs pbtsharedb-integracion
test "$codigo" = 0
