#!/usr/bin/env python3
"""Build-time launcher secrets: the Microsoft application (client) ID baked into orbis-launcher.jar.

The value comes from the ORBIS_MSA_CLIENT_ID environment variable (a GitHub Actions secret in CI) or
from the untracked secrets.env at the launcher repository root. It is stored in the JAR encoded, so it
is neither in the sources nor a plain string in the archive. This only keeps it from casual discovery:
a public client ID is sent with every sign-in request and cannot be truly hidden.
"""
import os
import re
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parents[2]
SECRETS_FILE = REPOSITORY / 'secrets.env'
VARIABLE = 'ORBIS_MSA_CLIENT_ID'
# Must match MicrosoftAuthClient.EMBEDDED and MicrosoftAuthClient.KEY.
RESOURCE = 'orbis/launcher/msa-client.bin'
KEY = bytes.fromhex('5f1ca7e2093bd4718e26c05ba4f3196d')
UUID = re.compile(r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}')


def _from_file(path):
    if not path.is_file():
        return ''
    for line in path.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line.startswith(VARIABLE + '='):
            return line.split('=', 1)[1].strip().strip('"\'')
    return ''


def client_id(secrets_file=SECRETS_FILE):
    """The configured ID, or '' when neither the environment nor secrets.env provides one."""
    value = os.environ.get(VARIABLE, '').strip() or _from_file(Path(secrets_file))
    if value and not UUID.fullmatch(value):
        raise SystemExit(f'{VARIABLE} is not an application (client) ID GUID')
    return value


def encode(value):
    data = value.encode('ascii')
    return bytes(b ^ KEY[i % len(KEY)] ^ (i * 31 & 0xff) for i, b in enumerate(data))


def resource(secrets_file=SECRETS_FILE):
    """(archive path, bytes) for the JAR, or None for a development build without an ID."""
    value = client_id(secrets_file)
    return (RESOURCE, encode(value)) if value else None
