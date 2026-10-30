"""Owned, loopback-only QA2 runtime. Never accepts a target URL or database connection."""
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import tempfile
import time


def main():
    backend = Path(__file__).resolve().parents[1]
    port = int(os.environ.get('SPOTPULSE_E2E_PORT', '18089'))
    suite = os.environ.get('SPOTPULSE_E2E_SUITE', 'qa2')
    if suite not in {'qa1', 'qa2'}:
        raise RuntimeError('Unknown isolated test suite')
    if not 1024 <= port <= 65535:
        raise RuntimeError('Invalid isolated port')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', port))  # fail rather than reuse another application
    environment = {key: value for key, value in os.environ.items() if not key.upper().startswith(
        ('SPRING_', 'SPOTPULSE_', 'GOOGLE_', 'CLOUDINARY_', 'TELEGRAM_', 'VITE_'))
        and key.upper() not in {'DATABASE_URL', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
    java = shutil.which('java')
    probe = subprocess.run([java, '-XshowSettings:properties', '-version'], env=environment,
                           capture_output=True, text=True, timeout=20)
    home = re.search(r'(?m)^\s*java\.home = (.+)$', probe.stderr)
    if not home or not re.search(r'(?m)^\s*java\.specification\.version = 25\s*$', probe.stderr):
        raise RuntimeError('QA2 requires Java 25')
    java = str(Path(home.group(1).strip()) / 'bin' / ('java.exe' if os.name == 'nt' else 'java'))
    dependencies = (backend / 'target/e2e-classpath.txt').read_text().strip()
    classpath = os.pathsep.join([str(backend / 'target/classes'), str(backend / 'target/test-classes'), dependencies])
    root = Path(tempfile.mkdtemp(prefix='spotpulse-e2e-')).resolve()
    if root.parent != Path(tempfile.gettempdir()).resolve():
        raise RuntimeError('Unsafe QA2 temporary directory')
    output = backend / 'target' / suite
    output.mkdir(parents=True, exist_ok=True)
    stop_file, active_file = output / 'stop-requested', output / 'active'
    stop_file.unlink(missing_ok=True)
    process = None
    def stop(_signum, _frame):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, stop)
    try:
        with (output / 'backend.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([java, '-cp', classpath, 'com.spotpulse.e2e.E2eApplication', str(root), str(port)],
                                       cwd=backend, env=environment, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            active_file.write_text('Owned isolated QA2 runtime', encoding='utf-8')
            print(f'Isolated backend starting on loopback; runtime log: target/{suite}/backend.log', flush=True)
            while process.poll() is None:
                if stop_file.exists():
                    raise KeyboardInterrupt
                time.sleep(.25)
            if process.returncode:
                raise RuntimeError(f'Isolated backend exited; inspect target/{suite}/backend.log')
    except KeyboardInterrupt:
        pass
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        # Exact owned root checked before recursive deletion. No developer directories are accepted.
        if root.parent != Path(tempfile.gettempdir()).resolve() or not root.name.startswith('spotpulse-e2e-') or root.is_symlink():
            raise RuntimeError('Refusing unsafe QA2 cleanup')
        for attempt in range(20):
            try:
                shutil.rmtree(root)
                break
            except PermissionError:
                if attempt == 19:
                    raise
                time.sleep(.25)
        active_file.unlink(missing_ok=True)
        stop_file.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
