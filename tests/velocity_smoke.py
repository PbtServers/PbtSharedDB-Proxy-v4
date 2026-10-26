"""Prueba real de carga y comandos contra un JAR oficial de Velocity, sin motor SQL."""
import argparse
import os
from pathlib import Path
import queue
import re
import shutil
import subprocess
import threading
import time
import uuid
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--velocity", type=Path, required=True)
parser.add_argument("--plugin", type=Path, required=True)
args = parser.parse_args()
velocity, plugin = args.velocity.resolve(strict=True), args.plugin.resolve(strict=True)
run_id = str(uuid.uuid4())
workspace = Path("build/velocity-smoke-work").resolve() / run_id
report = Path("build/reports/velocity-smoke").resolve() / (run_id + ".log")
report.parent.mkdir(parents=True, exist_ok=True)
workspace.mkdir(parents=True)
(workspace / "plugins").mkdir()
shutil.copy2(plugin, workspace / "plugins" / plugin.name)
with zipfile.ZipFile(velocity) as jar:
    config = jar.read("default-velocity.toml").decode("utf-8")
config, count = re.subn(r'(?m)^bind\s*=\s*"[^"]+"', 'bind = "127.0.0.1:0"', config)
assert count == 1, "No se pudo fijar escucha local de la prueba"
(workspace / "velocity.toml").write_text(config, encoding="utf-8")
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")) if "JAVA_HOME" in os.environ else shutil.which("java")
assert java, "Falta Java 25"
process = subprocess.Popen(
    [java, "-Dterminal.jline=false", "-Dterminal.ansi=false", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", str(velocity)],
    cwd=workspace, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    text=True, encoding="utf-8", errors="replace",
)
lines, captured = queue.Queue(), []


def read_output():
    for line in process.stdout:
        lines.put(line)


thread = threading.Thread(target=read_output, daemon=True)
thread.start()


def expect(fragment, timeout=45):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            line = lines.get(timeout=0.25)
        except queue.Empty:
            if process.poll() is not None:
                break
            continue
        captured.append(line)
        if fragment in line:
            return
    raise AssertionError(f"No se recibió: {fragment}")


def command(value):
    process.stdin.write(value + "\n")
    process.stdin.flush()


try:
    expect("Done (")
    assert any("Loaded plugin pbtsharedb 1.0.0-SNAPSHOT" in line for line in captured)
    assert any("127.0.0.1:" in line and "Listening on" in line for line in captured)
    command("pbtsharedb status")
    expect("Estado: detenido")
    command("pbtsharedb help")
    expect("PbtSharedDB: help")
    command("sharedb reload")
    expect("validada y recargada")
    command("shutdown")
    expect("Gestor MariaDB cerrado")
    assert process.wait(timeout=15) == 0
    assert not any(" ERROR]" in line for line in captured)
    print("Carga y comandos correctos en Velocity real; MariaDB permanece deshabilitado.")
finally:
    # Solo el proceso creado por esta prueba, nunca búsquedas por nombre/PID externo.
    if process.poll() is None:
        try:
            command("shutdown")
            process.wait(timeout=15)
        except (OSError, subprocess.TimeoutExpired):
            process.kill()
            process.wait(timeout=10)
    thread.join(timeout=2)
    while not lines.empty():
        captured.append(lines.get_nowait())
    report.write_text("".join(captured), encoding="utf-8")
