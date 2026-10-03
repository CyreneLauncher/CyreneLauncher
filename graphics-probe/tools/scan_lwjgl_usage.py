#!/usr/bin/env python3
"""Report exact LWJGL member references in a client and selected dependencies."""
import argparse
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

import classfile

GLFW = 'org/lwjgl/glfw/GLFW'


def scan(paths):
    users = defaultdict(set)
    for path in paths:
        with zipfile.ZipFile(path) as jar:
            for name in sorted(jar.namelist()):
                if name.endswith('.class'):
                    for identity in classfile.references(jar.read(name)):
                        if identity[1].startswith('org/lwjgl/'):
                            users[identity].add(f'{Path(path).name}:{name}')
    return users


def compiled_methods(path):
    audit = json.loads(Path(path).read_text())
    entries = audit['implemented']
    if not isinstance(entries, list) or not all(isinstance(item, str) and ' ' in item
                                                for item in entries):
        raise ValueError('invalid compiled facade audit implemented methods')
    return {tuple(item.split(' ', 1)) for item in entries}


def report(users, implemented=None):
    references = [dict(kind=kind, owner=owner, name=name, descriptor=descriptor,
                       users=sorted(classes))
                  for (kind, owner, name, descriptor), classes in sorted(users.items())]
    missing = [dict(owner=owner, name=name, descriptor=descriptor)
               for kind, owner, name, descriptor in sorted(users)
               if owner == GLFW and kind == 'method' and implemented is not None
               and (name, descriptor) not in implemented]
    return {'references': references, 'missing_glfw': missing}


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('jar')
    parser.add_argument('--dependency', action='append', default=[], metavar='JAR')
    parser.add_argument('--facade-audit', help='compiled facade-audit.json')
    parser.add_argument('--json', help='write deterministic reference table')
    args = parser.parse_args(argv)
    users = scan([args.jar, *args.dependency])
    implemented = compiled_methods(args.facade_audit) if args.facade_audit else None
    result = report(users, implemented)
    by_owner = defaultdict(list)
    for item in result['references']:
        by_owner[item['owner']].append(item)
    for owner, members in sorted(by_owner.items()):
        print(f'{owner} ({len(members)})')
        for item in members:
            separator = '' if item['kind'] == 'method' else ' '
            print(f"  {item['kind']:6} {item['name']}{separator}{item['descriptor']}"
                  f"  [{len(item['users'])}]")
    if implemented is not None:
        called = {(i['name'], i['descriptor']) for i in result['references']
                  if i['owner'] == GLFW and i['kind'] == 'method'}
        print(f'\nGLFW methods called: {len(called)}; implemented by facade: '
              f"{len(called) - len(result['missing_glfw'])}; missing: {len(result['missing_glfw'])}")
        for item in result['missing_glfw']:
            print(f"  missing {item['name']} {item['descriptor']}")
    if args.json:
        Path(args.json).write_text(json.dumps(result, indent=2) + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
