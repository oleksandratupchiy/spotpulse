"""Compile test-only QA2 sources; never launch the regular application or use cloud settings."""
import os
from pathlib import Path
import subprocess

backend = Path(__file__).resolve().parents[1]
environment = {key: value for key, value in os.environ.items() if not key.upper().startswith(
    ('SPRING_', 'SPOTPULSE_', 'GOOGLE_', 'CLOUDINARY_', 'TELEGRAM_', 'VITE_'))
    and key.upper() not in {'DATABASE_URL', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
environment.update({'VITE_API_BASE_URL': '/api', 'VITE_AUTH_BASE_URL': ''})
subprocess.run(['npm.cmd' if os.name == 'nt' else 'npm', 'run', 'build:deployment'],
               cwd=backend.parent / 'spotpulse-frontend', env=environment, check=True)
wrapper = [str(backend / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(backend / 'mvnw')]
subprocess.run([*wrapper, '-B', '-ntp', '-Pspa', 'test-compile', 'dependency:build-classpath',
                '-Dmdep.outputFile=target/e2e-classpath.txt'], cwd=backend, env=environment, check=True)
