"""Compile the shared test-only QA1/QA2 backend without frontend or external account configuration."""
import os
from pathlib import Path
import subprocess

backend = Path(__file__).resolve().parents[1]
environment = {key: value for key, value in os.environ.items() if not key.upper().startswith(
    ('SPRING_', 'SPOTPULSE_', 'GOOGLE_', 'CLOUDINARY_', 'TELEGRAM_', 'VITE_'))
    and key.upper() not in {'DATABASE_URL', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
wrapper = [str(backend / 'mvnw.cmd')] if os.name == 'nt' else ['sh', str(backend / 'mvnw')]
subprocess.run([*wrapper, '-B', '-ntp', 'test-compile', 'dependency:build-classpath',
                '-Dmdep.outputFile=target/e2e-classpath.txt'], cwd=backend, env=environment, check=True)
