"""Contrasta SHA-256 nuevos con repositorios oficiales; no modifica metadatos."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

NS = {'v': 'https://schema.gradle.org/dependency-verification'}


def entries(data):
    root = ET.fromstring(data)
    config = root.find('v:configuration', NS)
    assert config.find('v:verify-metadata', NS).text == 'true'
    assert config.find('v:trusted-artifacts', NS) is None
    result = {}
    for component in root.findall('v:components/v:component', NS):
        for artifact in component.findall('v:artifact', NS):
            key = tuple(component.attrib[n] for n in ('group', 'name', 'version')) + (artifact.attrib['name'],)
            result[key] = {s.attrib['value'] for s in artifact.findall('v:sha256', NS)}
            assert result[key], f'Sin SHA-256: {key}'
    return result


def verify(item):
    (group, name, version, filename), hashes = item
    assert len(hashes) == 1, f'Nuevo artefacto ambiguo: {item[0]}'
    relative = '/'.join((group.replace('.', '/'), name, version, filename))
    base = ('https://repo.papermc.io/repository/maven-public/' if group.startswith(('io.papermc', 'com.velocitypowered', 'net.md-5'))
            else 'https://libraries.minecraft.net/' if group.startswith('com.mojang')
            else 'https://repo.maven.apache.org/maven2/')
    url = base + relative
    request = urllib.request.Request(url, headers={'User-Agent': 'PbtSharedDB-integrity-audit/1.0'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read()
    except Exception as error:
        raise RuntimeError(f'No se pudo contrastar {url}: {error}') from error
    actual = hashlib.sha256(data).hexdigest()
    assert actual in hashes, f'SHA-256 diferente del repositorio oficial: {relative}'
    return {'artifact': ':'.join((group, name, version, filename)), 'sha256': actual, 'source': url}


if __name__ == '__main__':
    baseline = sys.argv[1] if len(sys.argv) > 1 else 'fd658f0'
    old = entries(subprocess.check_output(['git', 'show', baseline + ':gradle/verification-metadata.xml']))
    current = entries(Path('gradle/verification-metadata.xml').read_bytes())
    for key, hashes in old.items():
        assert current.get(key) == hashes, f'Se alteró un SHA-256 previo: {key}'
    additions = [(key, hashes) for key, hashes in current.items() if key not in old]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        verified = list(pool.map(verify, additions))
    output = Path('build/reports/dependency-audit.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'baseline': baseline, 'preserved_artifacts': len(old), 'verified': verified}, indent=2) + '\n')
    print(f'{len(old)} artefactos previos intactos; {len(verified)} nuevos contrastados con repositorios oficiales. Informe: {output}')
