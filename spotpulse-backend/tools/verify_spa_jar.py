"""Verify the assembled SPA JAR without Docker, cloud accounts, or persistent application data."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import zipfile


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


@contextmanager
def isolated_directory():
    root = Path(tempfile.mkdtemp(prefix='spotpulse-jar-smoke-')).resolve()
    if root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith('spotpulse-jar-smoke-'):
        raise RuntimeError('Refusing an unsafe temporary smoke directory')
    try:
        yield root
    finally:
        # Bounded cleanup; avoid tempfile's recursive permission-repair loop on Windows file locks.
        for attempt in range(20):
            try:
                if root.resolve() != root:
                    raise RuntimeError('Temporary smoke directory identity changed')
                shutil.rmtree(root)
                break
            except PermissionError:
                if attempt == 19:
                    raise RuntimeError('Temporary smoke directory is still in use after process shutdown') from None
                time.sleep(.25)


def verify(jar, dist, output):
    jar, dist, output = jar.resolve(), dist.resolve(), output.resolve()
    with zipfile.ZipFile(jar) as archive:
        index = archive.read('BOOT-INF/classes/static/app/index.html').decode('utf-8')
        assert 'SpotPulse test SPA fixture' not in index
        assert 'BOOT-INF/classes/static/app/assets/test-app.js' not in archive.namelist()
        files = [file for file in dist.rglob('*') if file.is_file()]
        assert files
        for file in files:
            assert archive.read('BOOT-INF/classes/static/app/' + file.relative_to(dist).as_posix()) == file.read_bytes()
        assets = set(re.findall(r'/app/assets/[-A-Za-z0-9_.]+', index))
        assert len(assets) >= 2
        for asset in assets:
            assert 'BOOT-INF/classes/static' + asset in archive.namelist()

    output.mkdir(parents=True, exist_ok=True)
    (output / 'jar-smoke-summary.json').unlink(missing_ok=True)
    environment = dict(os.environ)
    for key in list(environment):
        if key.upper().startswith(('SPRING_', 'SPOTPULSE_', 'GOOGLE_', 'CLOUDINARY_', 'TELEGRAM_')) or key.upper() in {
            'DATABASE_URL', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'
        }:
            environment.pop(key)
    java = shutil.which('java')
    if not java:
        raise RuntimeError('Java 25 must be available on PATH')
    probe = subprocess.run([java, '-XshowSettings:properties', '-version'], env=environment,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', timeout=20,
                           creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    home = re.search(r'(?m)^\s*java\.home = (.+)$', probe.stdout)
    if probe.returncode or not home or not re.search(r'(?m)^\s*java\.specification\.version = 25\s*$', probe.stdout):
        raise RuntimeError('Java 25 runtime detection failed')
    # Bypass launchers such as Oracle javapath, which can leave a JVM child alive after termination.
    java = str(Path(home.group(1).strip()) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
    if not Path(java).is_file():
        raise RuntimeError('Actual Java runtime executable was not found')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with isolated_directory() as root:
        work = root / 'runtime'
        work.mkdir()
        java_temporary = root / 'tmp'
        java_temporary.mkdir()
        if any((parent / '.git').exists() for parent in (work, *work.parents)):
            raise RuntimeError('The smoke runtime must be outside a Git checkout')
        arguments = [java, '-Djava.io.tmpdir=' + str(java_temporary), '-jar', str(jar), '--spring.profiles.active=local',
                     '--spring.datasource.url=jdbc:h2:mem:jar-smoke', '--spring.datasource.hikari.jdbc-url=jdbc:h2:mem:jar-smoke',
                     '--spring.datasource.driver-class-name=org.h2.Driver', '--spring.datasource.username=sa', '--spring.datasource.password=',
                     '--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect', '--spring.jpa.hibernate.ddl-auto=create-drop',
                     '--spring.jpa.show-sql=false', '--server.port=0', '--server.address=127.0.0.1',
                     '--server.forward-headers-strategy=native', '--server.tomcat.remoteip.protocol-header=x-forwarded-proto',
                     '--server.servlet.session.cookie.secure=true',
                     '--spotpulse.auth.frontend-origin=https://jar-smoke.example.test', '--spotpulse.auth.ui-base-path=/app',
                     '--spotpulse.images.storage-provider=local', '--spotpulse.images.directory=' + str(root / 'images'),
                     '--spotpulse.telegram.enabled=false']
        log_path = output / 'jar-smoke-runtime.log'
        with log_path.open('w', encoding='utf-8') as log:
            process = subprocess.Popen(arguments, cwd=work, env=environment, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            try:
                deadline = time.monotonic() + 180
                port = None
                while time.monotonic() < deadline:
                    if process.poll() is not None:
                        raise RuntimeError('Packaged JAR exited before readiness; inspect the isolated smoke log')
                    text = log_path.read_text(encoding='utf-8', errors='replace')
                    match = re.search(r'Tomcat started on port (\d+)', text)
                    if match:
                        port = int(match.group(1))
                        break
                    time.sleep(.25)
                if port is None:
                    raise RuntimeError('Packaged JAR readiness timed out')

                def get(path):
                    request = urllib.request.Request('http://127.0.0.1:' + str(port) + path, headers={'X-Forwarded-Proto': 'https'})
                    try:
                        response = opener.open(request, timeout=15)
                    except urllib.error.HTTPError as error:
                        response = error
                    with response:
                        return response.status, response.headers, response.read().decode('utf-8')

                status, headers, body = get('/health')
                assert status == 200 and json.loads(body) == {'status': 'UP'}
                assert 'no-store' in headers.get('Cache-Control', '')
                assert headers.get('Strict-Transport-Security')
                status, headers, _ = get('/')
                assert status == 302 and headers.get('Location') == '/app/spaces'
                for route in ['/app/spaces', '/app/rooms/1', '/app/owner/spaces/new', '/app/account']:
                    status, _, body = get(route)
                    assert status == 200 and body == index
                for asset in assets:
                    assert get(asset)[0] == 200
                status, _, body = get('/spaces')
                assert status == 200 and '<html' in body and 'id="root"' not in body
                assert get('/api/spaces?skip=0&limit=1')[0] == 200
                status, _, body = get('/app/assets/not-found.js')
                assert status == 404 and body != index
                summary = {'result': 'PASS', 'packaged_spa_files': len(files), 'referenced_assets_checked': len(assets),
                           'runtime_outside_checkout': True, 'database': 'isolated in-memory H2', 'external_credentials': False}
            finally:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
    (output / 'jar-smoke-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path('target/spotpulse-0.0.1-SNAPSHOT.jar'))
    parser.add_argument('--dist', type=Path, default=Path('../spotpulse-frontend/dist'))
    parser.add_argument('--output', type=Path, default=Path('target/c2-c3-verification'))
    options = parser.parse_args()
    verify(options.jar, options.dist, options.output)
