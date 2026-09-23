#!/usr/bin/env python3
"""Create a GP4 project without putting the complete file list in argv."""

from __future__ import annotations

import argparse
from datetime import datetime
from pathlib import Path
from xml.sax.saxutils import quoteattr


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--content-id", required=True)
    args = parser.parse_args()

    root = args.root.resolve()
    files: list[str] = []
    directories: set[str] = set()
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if path.resolve() == args.out.resolve() or path.name == ".stamp":
            continue
        if path.is_file():
            # is_file() follows links, so symlinked JDK image entries are
            # materialized at their target path in the package as well.
            files.append(relative)
        elif path.is_dir():
            directories.add(relative)

    tree: dict[str, dict] = {}
    for directory in sorted(directories):
        current = tree
        for part in directory.split("/"):
            current = current.setdefault(part, {})

    def render_directory(name: str, children: dict[str, dict], indent: str) -> list[str]:
        if not children:
            return [f'{indent}<dir targ_name={quoteattr(name)} />']
        lines = [f'{indent}<dir targ_name={quoteattr(name)}>']
        for child_name in sorted(children):
            lines.extend(render_directory(child_name, children[child_name], indent + "\t"))
        lines.append(f"{indent}</dir>")
        return lines

    directory_lines: list[str] = []
    for name in sorted(tree):
        directory_lines.extend(render_directory(name, tree[name], "\t\t"))

    file_lines = [
        f"\t\t<file targ_path={quoteattr(path)} "
        f"orig_path={quoteattr(path)} />"
        for path in files
    ]

    xml = [
        '<?xml version="1.0"?>',
        '<psproject xmlns:xsd="http://www.w3.org/2001/XMLSchema" '
        'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
        'fmt="gp4" version="1000">',
        "\t<volume>",
        "\t\t<volume_type>pkg_ps4_app</volume_type>",
        "\t\t<volume_id>PS4VOLUME</volume_id>",
        f"\t\t<volume_ts>{datetime.now():%Y-%m-%d %H:%M:%S}</volume_ts>",
        f'\t\t<package content_id={quoteattr(args.content_id)} '
        'passcode="00000000000000000000000000000000" '
        'storage_type="digital50" app_type="full" />',
        '\t\t<chunk_info chunk_count="1" scenario_count="1">',
        "\t\t\t<chunks>",
        '\t\t\t\t<chunk id="0" layer_no="0" label="Chunk #0" />',
        "\t\t\t</chunks>",
        '\t\t\t<scenarios default_id="0">',
        '\t\t\t\t<scenario id="0" type="sp" initial_chunk_count="1" '
        'label="Scenario #0">0</scenario>',
        "\t\t\t</scenarios>",
        "\t\t</chunk_info>",
        "\t</volume>",
        '\t<files img_no="0">',
        *file_lines,
        "\t</files>",
        "\t<rootdir>",
        *directory_lines,
        "\t</rootdir>",
        "</psproject>",
        "",
    ]
    args.out.write_text("\n".join(xml), encoding="utf-8")


if __name__ == "__main__":
    main()
