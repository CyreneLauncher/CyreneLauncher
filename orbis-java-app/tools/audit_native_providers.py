#!/usr/bin/env python3
"""Audit desktop SELF import ownership against genuine ELF definitions."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
from orbis_native_modules import DESKTOP_MODULES, native_modules


def output(*args):
    return subprocess.check_output(list(map(str, args)), text=True)


def symbols(path):
    undefined, defined = set(), set()
    for line in output('readelf', '--dyn-syms', '-W', path).splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(':').isdigit():
            continue
        name = fields[7].split('@', 1)[0]
        (undefined if fields[6] == 'UND' else defined).add(name)
    return undefined, defined


def nid(name):
    salt = bytes.fromhex('518D64A635DED8C1E6B039B1C3E55230')
    digest = hashlib.sha1(name.encode() + salt).digest()[:8][::-1]
    return base64.b64encode(digest).decode().replace('/', '-').rstrip('=')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--readoelf', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    names = ('jvm', *native_modules(True))
    tables = {name: symbols(args.native / f'lib{name}.so') for name in names}
    alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-'
    findings, errors = [], []
    for name in DESKTOP_MODULES:
        oelf = args.native / f'lib{name}.oelf'
        sony = output(args.readoelf, '-d', '-s', oelf)
        providers = re.findall(r'\(SCE_NEEDED_MODULE\).*\[([^\]]+)\]', sony)
        raw = output('readelf', '-d', oelf)
        ids = [int(value, 16) >> 48 for value in re.findall(
            r'\(Operating System specific: 6100000f\)\s+(0x[0-9a-f]+)', raw)]
        if len(ids) != len(providers):
            raise ValueError(f'Cannot decode generated provider IDs: {oelf}')
        modules = dict(zip(ids, providers))
        imports = {}
        for line in sony.splitlines():
            fields = line.split()
            if len(fields) < 8 or fields[6] != 'UND' or '#' not in fields[7]:
                continue
            hashed, library, module = fields[7].split('#')
            module_id = 0
            for character in module:
                module_id = module_id * 64 + alphabet.index(character)
            imports[hashed] = modules.get(module_id)
        for symbol in sorted(tables[name][0]):
            provider = imports.get(nid(symbol))
            genuine = [f'lib{candidate}' for candidate in names
                       if symbol in tables[candidate][1]]
            expected = genuine or ['libkernel']
            good = provider in expected
            findings.append(dict(module=name, symbol=symbol, provider=provider,
                                 genuine_providers=expected, accepted=good))
            if not good:
                errors.append(f'{name}: {symbol}: provider {provider}, expected {expected}')
    args.output.write_text(json.dumps({'schema': 1, 'imports': findings,
                                      'errors': errors}, indent=2, sort_keys=True) + '\n')
    for error in errors:
        print(error)
    return bool(errors)


if __name__ == '__main__':
    raise SystemExit(main())
