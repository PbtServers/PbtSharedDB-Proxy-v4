"""Aceptación RC en Docker real: volumen, restauración limpia y LuckPerms oficial.

Ejecutar después de clean build e integration-linux.sh, en Linux con Docker/JDK25.
Solo se publican informes sin secretos; los volúmenes pertenecen a este run.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT)
REPORT = ROOT / "build/reports/rc"
ASSETS = ROOT / "build/rc-downloads"
REPORT.mkdir(parents=True, exist_ok=True)
ASSETS.mkdir(parents=True, exist_ok=True)
PREFIX = "pbt-rc-" + uuid.uuid4().hex[:10]
NETWORK = PREFIX + "-net"
IMAGE = "pbtsharedb-integracion"
DBROOT = "/state/velocity/plugins/pbtsharedb"
SOCKET = DBROOT + "/run/mariadb.sock"
MANIFEST = json.loads((ROOT / "tests/rc-artifacts.json").read_text())
SECRETS = set()
HOSTS, VOLUMES = [], []
RESULTS = {name: "no ejecutada" for name in (
    "recreacion_contenedor", "restauracion_volumen_vacio", "luckperms_bidireccional",
    "reconexion_luckperms", "cliente_paper26_descubrimiento", "logs_sin_secretos")}
NETWORK_CREATED = False
ENVIRONMENT = {}


def redact(text):
    for secret in sorted(SECRETS, key=len, reverse=True):
        text = text.replace(secret, "[CREDENCIAL RETIRADA]")
    return text


def run(args, data=None, timeout=180, check=True):
    result = subprocess.run(args, input=data, text=True, encoding="utf-8", errors="replace",
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if check and result.returncode:
        # Nunca volcar argumentos ni stdin: pueden contener SQL/configuración privada.
        raise RuntimeError(f"{args[0]} terminó con {result.returncode}: " + redact(result.stderr[-4000:]))
    return result


def docker(*args, **kwargs):
    return run(["docker", *args], **kwargs)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def passed(name):
    RESULTS[name] = "superada"
    print("RC OK: " + name, flush=True)


def begin(name):
    RESULTS[name] = "en ejecución"


def download_assets():
    for name, artifact in MANIFEST.items():
        target = ASSETS / (name + ".jar")
        if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != artifact["sha256"]:
            request = urllib.request.Request(artifact["url"], headers={"User-Agent": "PbtSharedDB-RC-integration/1.0"})
            with urllib.request.urlopen(request, timeout=120) as response:
                content = response.read()
            require(hashlib.sha256(content).hexdigest() == artifact["sha256"], "SHA-256 incorrecto: " + name)
            target.write_bytes(content)
        require(hashlib.sha256(target.read_bytes()).hexdigest() == artifact["sha256"], "Hash de artefacto incorrecto")


def volume_exec(volume, *command, data=None, user="mysql", timeout=180):
    return docker("run", "--rm", "--interactive", "--user", user, "--mount", f"type=volume,src={volume},dst=/state",
                  "-v", f"{ASSETS}:/assets:ro", "-v", f"{ROOT / 'build/distributions'}:/plugin:ro",
                  "-v", f"{ROOT / 'tests/rc-probe'}:/probe-src:ro", "--entrypoint", command[0],
                  IMAGE, *command[1:], data=data, timeout=timeout)


def write(volume, path, content):
    volume_exec(volume, "bash", "-ec", 'umask 077; mkdir -p "$(dirname "$1")"; cat > "$1"',
                "rc-write", path, data=content)


def new_volume(suffix):
    name = PREFIX + "-" + suffix
    docker("volume", "create", "--label", "pbtsharedb.rc=" + PREFIX, name)
    VOLUMES.append(name)
    require(not volume_exec(name, "find", "/state", "-mindepth", "1", "-print", "-quit", user="root").stdout.strip(),
            "El volumen debe estar completamente vacío antes de preparar el fixture")
    volume_exec(name, "bash", "-ec", "chown mysql:mysql /state; chmod 700 /state", user="root")
    return name


def prepare_proxy(volume):
    volume_exec(volume, "bash", "-ec", "mkdir -p /state/velocity/plugins/pbtsharedb; "
                "cp /assets/velocity.jar /state/velocity/server.jar; cp /plugin/PbtSharedDB-Proxy-1.0.0.jar /state/velocity/plugins/")
    with zipfile.ZipFile(ASSETS / "velocity.jar") as archive:
        config = archive.read("default-velocity.toml").decode()
    config, count = re.subn(r'(?m)^bind\s*=\s*"[^"]+"', 'bind = "127.0.0.1:0"', config)
    require(count == 1, "No se pudo aislar Velocity")
    config, count = re.subn(r'(?m)^lobby\s*=\s*"[^"]+"', 'lobby = "172.30.86.11:25565"', config)
    require(count == 1, "No se pudo configurar el backend Paper del laboratorio")
    write(volume, "/state/velocity/velocity.toml", config)
    config = (ROOT / "proxy/src/main/resources/config.yml").read_text(encoding="utf-8")
    for before, after in (("enabled: false", "enabled: true"), ("auto-start: false", "auto-start: true"),
                          ("host: 127.0.0.1", "host: 0.0.0.0"), ("remote-access: false", "remote-access: true"),
                          ("network-isolation-confirmed: false", "network-isolation-confirmed: true"),
                          ("databases: []", "databases: [luckperms]"),
                          ("\nusers: []", "\nusers:\n  - name: lp_proxy\n    host: 127.0.0.1\n    database: luckperms\n"
                           "  - name: lp_backend\n    host: 172.30.86.11\n    database: luckperms\n"
                           "  - name: lp_client\n    host: 172.30.86.12\n    database: luckperms")):
        config = config.replace(before, after)
    write(volume, DBROOT + "/config.yml", config)
    tls_password = secrets.token_urlsafe(32)
    SECRETS.add(tls_password)
    write(volume, DBROOT + "/discovery-tls-password.key", tls_password)
    volume_exec(volume, "keytool", "-genkeypair", "-alias", "discovery", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "30", "-dname", "CN=172.30.86.10", "-ext", "SAN=IP:172.30.86.10", "-storetype", "PKCS12",
                "-keystore", DBROOT + "/discovery-tls.p12", "-storepass:file", DBROOT + "/discovery-tls-password.key")
    write(volume, DBROOT + "/discovery.yml", "enabled: true\nbind-host: 0.0.0.0\nport: 3307\nallocated-port: 3307\n"
          "network-isolation-confirmed: true\nadvertised-sql-host: 172.30.86.10\nkeystore: discovery-tls.p12\n"
          "keystore-password-file: discovery-tls-password.key\nclients:\n  - id: lobby\n    username: lp_client\n    user-host: 172.30.86.12\n")


class Host:
    def __init__(self, suffix, volume, paper=False, ip=None):
        self.name, self.volume, self.paper = PREFIX + "-" + suffix, volume, paper
        self.lines = []
        self.process = None
        self.removed = False
        self.ip = ip or ("172.30.86.11" if paper else "172.30.86.10")
        HOSTS.append(self)

    def start(self):
        directory = "/state/paper" if self.paper else "/state/velocity"
        command = ["docker", "run", "--name", self.name, "--init", "--interactive", "--stop-timeout", "150",
                   "--network", NETWORK, "--ip", self.ip,
                   "--mount", f"type=volume,src={self.volume},dst=/state", "--workdir", directory,
                   "--entrypoint", "java", IMAGE, "-Xms256M", "-Xmx1024M", "-XX:ActiveProcessorCount=2",
                   "-Dterminal.jline=false", "-Dterminal.ansi=false", "-Dfile.encoding=UTF-8", "-jar", "server.jar"]
        if self.paper:
            command.append("--nogui")
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                        text=True, encoding="utf-8", errors="replace")
        self.reader = threading.Thread(target=lambda: self.lines.extend(self.process.stdout), daemon=True)
        self.reader.start()
        self.expect("Done (", timeout=300)
        if not self.paper:
            self.expect("(READY)", timeout=120)
            self.collect_secrets()

    def expect(self, text, start=0, timeout=90):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if text in "".join(self.lines[start:]):
                return
            if self.process.poll() is not None:
                raise RuntimeError("Contenedor terminó antes de recibir: " + text)
            time.sleep(.2)
        raise AssertionError("Tiempo agotado esperando: " + text)

    def command(self, command, expected=None, timeout=90):
        start = len(self.lines)
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()
        if expected:
            self.expect(expected, start, timeout)

    def sql(self, statement, check=True):
        return docker("exec", "-i", self.name, "mariadb", "--no-defaults", "--protocol=socket", "--socket=" + SOCKET,
                      "--user=mysql", "--default-character-set=utf8mb4", "--batch", "--skip-column-names", "luckperms",
                      data=statement, check=check).stdout.strip()

    def collect_secrets(self):
        values = docker("exec", self.name, "bash", "-ec", f"for key in {DBROOT}/secrets/*.key {DBROOT}/discovery-secrets/*.key; do test ! -f \"$key\" || cat \"$key\"; done").stdout.splitlines()
        require(values and all(re.fullmatch(r"[A-Za-z0-9_-]{43}", x) for x in values), "Credenciales no válidas")
        SECRETS.update(values)
        forwarding = docker("exec", self.name, "bash", "-ec",
                            "if test -f /state/velocity/forwarding.secret; then cat /state/velocity/forwarding.secret; fi").stdout.strip()
        if forwarding:
            require(len(forwarding) >= 8, "Secreto de forwarding inesperado")
            SECRETS.add(forwarding)
        permissions = docker("exec", self.name, "bash", "-ec",
                             f"stat -c '%a' {DBROOT}/secrets; stat -c '%a' {DBROOT}/secrets/*.key").stdout.splitlines()
        require(permissions[0] == "700" and all(x == "600" for x in permissions[1:]), "Permisos privados incorrectos")

    def stop(self, signal=False):
        if signal:
            docker("stop", "--time", "150", self.name, timeout=180)
        else:
            self.command("stop" if self.paper else "shutdown")
        self.process.wait(timeout=180)
        self.reader.join(timeout=5)
        state = json.loads(docker("inspect", "--format", "{{json .State}}", self.name).stdout)
        require(not state["OOMKilled"] and state["ExitCode"] in (0, 143), "Parada forzada u OOM")
        if not self.paper:
            require("Gestor MariaDB cerrado" in "".join(self.lines), "Sin cierre confirmado del gestor")
            residual = volume_exec(self.volume, "bash", "-ec",
                                   f"test ! -e {SOCKET}; test ! -e {DBROOT}/run/mariadb.pid", timeout=30)
            require(residual.returncode == 0, "Socket/PID residual tras la parada")
        docker("rm", self.name)
        self.removed = True


def fingerprint(host):
    tables = host.sql("SHOW TABLES;")
    rows = host.sql("SELECT id,HEX(texto),HEX(payload) FROM rc_records ORDER BY id; SELECT * FROM rc_links ORDER BY id;")
    schema = host.sql("SHOW CREATE TABLE rc_records; SHOW CREATE TABLE rc_links;")
    integrity = host.sql("CHECK TABLE rc_records,rc_links;")
    require(integrity.count("status\tOK") == 2, "CHECK TABLE no confirmó integridad")
    return hashlib.sha256((tables + "\n" + rows + "\n" + schema).encode()).hexdigest()


def lp_config(address, username, password, server):
    return f"""server: {server}
storage-method: mariadb
data:
  address: '{address}:3306'
  database: luckperms
  username: {username}
  password: '{password}'
  table-prefix: 'luckperms_'
  pool-settings:
    maximum-pool-size: 3
    minimum-idle: 1
    connection-timeout: 10000
messaging-service: sql
sync-minutes: -1
auto-push-updates: true
"""


def compile_probe(volume, paper=False):
    if paper:
        source, folder = "PaperProbe", "/state/paper"
        classpath = '"$CP"'
        prefix = f"CP=$(find {folder}/libraries -name '*.jar' -printf '%p:'); CP=$CP{folder}/plugins/LuckPerms.jar; "
        descriptor = "name: RcProbe\nversion: '1'\nmain: com.pbtservers.rcprobe.PaperProbe\napi-version: '1.21'\ndepend: [LuckPerms]\n"
        descriptor_name = "plugin.yml"
    else:
        source, folder = "VelocityProbe", "/state/velocity"
        classpath = f"{folder}/server.jar:{folder}/plugins/LuckPerms.jar"
        prefix = ""
        descriptor = json.dumps({"id": "rcprobe", "name": "Observador RC", "version": "1",
                                 "main": "com.pbtservers.rcprobe.VelocityProbe", "dependencies": [{"id": "luckperms", "optional": False}]})
        descriptor_name = "velocity-plugin.json"
    classes = "/state/probe-classes"
    volume_exec(volume, "bash", "-ec", prefix + f"mkdir -p {classes}; javac -proc:none -encoding UTF-8 -cp {classpath} "
                f"-d {classes} /probe-src/Snapshot.java /probe-src/{source}.java")
    write(volume, classes + "/" + descriptor_name, descriptor)
    volume_exec(volume, "jar", "--create", "--file", folder + "/plugins/RcProbe.jar", "-C", classes, ".")


def security_reports():
    require(HOSTS and SECRETS, "No hay un arranque y credenciales para comprobar los logs")
    begin("logs_sin_secretos")
    leaked = False
    for host in HOSTS:
        output = "".join(host.lines)
        if not host.paper:
            output += volume_exec(host.volume, "bash", "-ec",
                                  f"for log in {DBROOT}/*.log; do test ! -f \"$log\" || cat \"$log\"; done").stdout
        leaked |= any(secret in output for secret in SECRETS)
        (REPORT / (host.name + ".log")).write_text(redact(output), encoding="utf-8")
    require(not leaked, "Se detectó una credencial en los logs (informes redactados)")
    passed("logs_sin_secretos")


def main():
    global NETWORK_CREATED
    docker("info")
    download_assets()
    docker("build", "-t", IMAGE, "-f", "tests/Dockerfile", ".", timeout=600)
    ENVIRONMENT["docker_server"] = docker("version", "--format", "{{.Server.Version}}").stdout.strip()
    ENVIRONMENT["imagen"] = docker("image", "inspect", "--format", "{{.Id}} {{.Os}} {{.Architecture}}", IMAGE).stdout.strip()
    ENVIRONMENT["java"] = docker("run", "--rm", "--entrypoint", "java", IMAGE, "-version").stderr.strip()
    ENVIRONMENT["mariadb"] = docker("run", "--rm", "--entrypoint", "mariadbd", IMAGE, "--no-defaults", "--version").stdout.strip()
    docker("network", "create", "--subnet", "172.30.86.0/24", NETWORK)
    NETWORK_CREATED = True
    begin("recreacion_contenedor")
    original = new_volume("original")
    prepare_proxy(original)
    first = Host("original", original)
    first.start()
    first.sql("""CREATE TABLE rc_records(id INT PRIMARY KEY,texto VARCHAR(100) NOT NULL,payload VARBINARY(32)) ENGINE=InnoDB;
CREATE TABLE rc_links(id INT PRIMARY KEY,record_id INT,FOREIGN KEY(record_id) REFERENCES rc_records(id)) ENGINE=InnoDB;
INSERT INTO rc_records VALUES (1,'persistencia ñ 漢字',UNHEX('0001FF')), (2,'restauración',UNHEX('CAFE'));
INSERT INTO rc_links VALUES(1,2);
""")
    expected = fingerprint(first)
    keys_before = first.sql("SELECT 1;")  # Confirmación SQL antes de detener el contenedor.
    credential = docker("exec", first.name, "cat", DBROOT + "/secrets/lp_proxy-127.0.0.1.key").stdout
    first.stop(signal=True)
    second = Host("recreado", original)
    second.start()
    require(first.name != second.name and first.removed, "El contenedor anterior no se eliminó")
    require(keys_before == "1" and fingerprint(second) == expected, "Registros distintos tras recrear el contenedor")
    require(docker("exec", second.name, "cat", DBROOT + "/secrets/lp_proxy-127.0.0.1.key").stdout == credential,
            "Credencial modificada tras recrear")
    passed("recreacion_contenedor")
    begin("restauracion_volumen_vacio")
    second.command("pbtsharedb backup", "Copia consistente creada")
    backup_path = docker("exec", second.name, "bash", "-ec", f"ls {DBROOT}/backups/*.sql").stdout.strip()
    dump = docker("exec", second.name, "cat", backup_path).stdout
    require(dump and not any(secret in dump for secret in SECRETS), "Dump vacío o contiene secretos SQL")
    second.stop()
    fresh = new_volume("restauracion")
    prepare_proxy(fresh)
    restored = Host("restaurado", fresh)
    restored.start()
    require(restored.sql("SHOW TABLES;") == "", "La base recién inicializada no está vacía")
    restored.sql(dump)
    require(fingerprint(restored) == expected, "Tablas/esquema/registros distintos al restaurar")
    new_credential = docker("exec", restored.name, "cat", DBROOT + "/secrets/lp_proxy-127.0.0.1.key").stdout
    require(new_credential != credential, "Se han restaurado secretos en lugar de aprovisionar cuentas nuevas")
    restored.stop()
    live = Host("restaurado-reinicio", fresh)
    live.start()
    require(fingerprint(live) == expected, "Restauración no persiste tras recrear otro contenedor")
    passed("restauracion_volumen_vacio")
    begin("luckperms_bidireccional")
    proxy_password = new_credential.strip()
    backend_password = docker("exec", live.name, "cat", DBROOT + "/secrets/lp_backend-172.30.86.11.key").stdout.strip()
    live.stop()
    volume_exec(fresh, "bash", "-ec", "cp /assets/lp_velocity.jar /state/velocity/plugins/LuckPerms.jar")
    write(fresh, "/state/velocity/plugins/luckperms/config.yml", lp_config("127.0.0.1", "lp_proxy", proxy_password, "proxy"))
    compile_probe(fresh)
    proxy = Host("luckperms-proxy", fresh)
    proxy.start()
    proxy.expect("RC_CACHE velocity UNDEFINED")
    require("Failed to init storage" not in "".join(proxy.lines) and "Exception occurred whilst enabling SQL messaging" not in "".join(proxy.lines),
            "LuckPerms no inicializó almacenamiento/mensajería")
    backend_volume = new_volume("backend")
    volume_exec(backend_volume, "bash", "-ec", "mkdir -p /state/paper/plugins/LuckPerms; "
                "cp /assets/paper.jar /state/paper/server.jar; cp /assets/lp_bukkit.jar /state/paper/plugins/LuckPerms.jar")
    write(backend_volume, "/state/paper/eula.txt", "eula=true\n")
    write(backend_volume, "/state/paper/server.properties", "server-ip=0.0.0.0\nserver-port=25565\nonline-mode=true\n"
          "level-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-players=2\n")
    write(backend_volume, "/state/paper/plugins/LuckPerms/config.yml", lp_config("172.30.86.10", "lp_backend", backend_password, "backend"))
    warmup = Host("paper-preparacion", backend_volume, paper=True)
    warmup.start()
    warmup.stop()
    compile_probe(backend_volume, paper=True)
    backend = Host("paper", backend_volume, paper=True)
    backend.start()
    backend.expect("RC_CACHE paper UNDEFINED")
    docker("exec", proxy.name, "bash", "-ec", "timeout 5 bash -c 'exec 3<>/dev/tcp/172.30.86.11/25565'")
    proxy.command("lpv group default permission set pbt.rc1.propagacion true")
    proxy.expect("RC_CACHE velocity TRUE")
    backend.expect("RC_CACHE paper TRUE")
    before = len(proxy.lines)
    backend.command("lp group default permission set pbt.rc1.propagacion false")
    backend.expect("RC_CACHE paper FALSE")
    proxy.expect("RC_CACHE velocity FALSE", before)
    require("pbt.rc1.propagacion" in proxy.sql("SELECT permission FROM luckperms_group_permissions;"), "LuckPerms no escribió su tabla SQL")
    passed("luckperms_bidireccional")
    begin("reconexion_luckperms")
    proxy.command("pbtsharedb restart", "MariaDB reiniciado y disponible", timeout=180)
    before = len(backend.lines)
    proxy.command("lpv group default permission set pbt.rc1.propagacion true")
    backend.expect("RC_CACHE paper TRUE", before, timeout=120)
    proxy.stop(signal=True)
    proxy = Host("luckperms-proxy-recreado", fresh)
    proxy.start()
    proxy.expect("RC_CACHE velocity TRUE")
    before = len(backend.lines)
    proxy.command("lpv group default permission set pbt.rc1.propagacion false")
    proxy.expect("RC_CACHE velocity FALSE")
    backend.expect("RC_CACHE paper FALSE", before, timeout=120)
    passed("reconexion_luckperms")
    begin("cliente_paper26_descubrimiento")
    client_volume = new_volume("cliente26")
    volume_exec(client_volume, "bash", "-ec", "mkdir -p /state/paper/plugins/PbtSharedDBClient; "
                "cp /assets/paper_client.jar /state/paper/server.jar; cp /plugin/PbtSharedDB-Client-1.0.0.jar /state/paper/plugins/")
    write(client_volume, "/state/paper/eula.txt", "eula=true\n")
    write(client_volume, "/state/paper/server.properties", "server-ip=0.0.0.0\nserver-port=25565\nonline-mode=true\n"
          "level-type=minecraft:flat\ngenerate-structures=false\nview-distance=2\nsimulation-distance=2\nmax-players=2\n")
    token = docker("exec", proxy.name, "cat", DBROOT + "/discovery-secrets/lobby.key").stdout.strip()
    sql_key = docker("exec", proxy.name, "cat", DBROOT + "/secrets/lp_client-172.30.86.12.key").stdout.strip()
    SECRETS.update((token, sql_key))
    volume_exec(fresh, "keytool", "-exportcert", "-alias", "discovery", "-keystore", DBROOT + "/discovery-tls.p12",
                "-storepass:file", DBROOT + "/discovery-tls-password.key", "-file", "/state/discovery.cer")
    certificate_fingerprint = volume_exec(fresh, "sha256sum", "/state/discovery.cer").stdout.split()[0]
    client_root = "/state/paper/plugins/PbtSharedDBClient"
    client_config = (f"config-version: 1\nenabled: true\nbootstrap-url: https://172.30.86.10:3307/v1/discovery\nclient-id: lobby\n"
                     f"certificate-sha256: {certificate_fingerprint}\ntoken-file: discovery-token.key\nsql-password-file: sql-password.key\n")
    write(client_volume, client_root + "/config.yml", client_config)
    write(client_volume, client_root + "/discovery-token.key", token)
    write(client_volume, client_root + "/sql-password.key", sql_key)
    write(client_volume, "/state/paper/plugins/Ajeno/config.yml", "no modificar\n")
    client_host = Host("cliente26", client_volume, paper=True, ip="172.30.86.12")
    client_host.start()
    client_host.expect("Cliente cargado")
    client_host.command("pbtsharedbclient discover", "Conexión generada sin contraseña: SELECT 1 autenticado")
    output = docker("exec", client_host.name, "cat", client_root + "/connections/lobby.yml").stdout
    require("verification: authenticated-select-1" in output and not any(secret in output for secret in SECRETS), "Exportación incorrecta o con secretos")
    bad_token = secrets.token_urlsafe(32)
    SECRETS.add(bad_token)
    write(client_volume, client_root + "/discovery-token.key", bad_token)
    client_host.command("pbtsharedbclient discover", "No se generan datos nuevos")
    require(docker("exec", client_host.name, "cat", client_root + "/connections/lobby.yml").stdout == output, "Un token inválido modificó el archivo")
    write(client_volume, client_root + "/discovery-token.key", token)
    write(client_volume, client_root + "/config.yml", client_config.replace(certificate_fingerprint, "0" * 64))
    client_host.command("pbtsharedbclient discover", "No se generan datos nuevos")
    require(docker("exec", client_host.name, "cat", client_root + "/connections/lobby.yml").stdout == output, "Un certificado inválido modificó el archivo")
    require(docker("exec", client_host.name, "cat", "/state/paper/plugins/Ajeno/config.yml").stdout == "no modificar\n", "El cliente modificó configuración ajena")
    client_host.stop()
    passed("cliente_paper26_descubrimiento")
    backend.stop()
    proxy.stop(signal=True)
    security_reports()
    (REPORT / "datos.sha256").write_text(expected + "\n", encoding="utf-8")


error = None
try:
    main()
except Exception as exc:
    error = redact(str(exc))
    print("RC FALLÓ: " + error, flush=True)
finally:
    # Capturar y redactar antes de retirar solo los recursos creados por este run.
    if error:
        try:
            security_reports()
        except Exception:
            pass
    for host in HOSTS:
        if not host.removed:
            docker("rm", "-f", host.name, check=False)
    if NETWORK_CREATED:
        docker("network", "rm", NETWORK, check=False)
    for volume in VOLUMES:
        docker("volume", "rm", volume, check=False)
    for name, status in RESULTS.items():
        if status == "en ejecución":
            RESULTS[name] = "fallida"
    (REPORT / "resultado.json").write_text(json.dumps({"commit": os.getenv("GITHUB_SHA", "local"),
        "pruebas": RESULTS, "error": error, "artefactos": MANIFEST, "entorno": ENVIRONMENT,
        "plugins_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT / "build/distributions").glob("*.jar")}}, ensure_ascii=False, indent=2), encoding="utf-8")
sys.exit(1 if error else 0)
