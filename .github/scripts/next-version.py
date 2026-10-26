#!/usr/bin/env python3
"""Print the next stable X.Y.Z version from the repository's release tags."""

import re
import subprocess
import sys


# Examples: v15.0.0 -> 15.1.0; v15.7.0 -> 16.0.0.
# With no matching tag, base 15.0.0 produces the first stable version, 15.1.0.
VERSION_PATTERN = re.compile(r"v?(\d+)\.(\d+)\.(\d+)")


def parse_version(value: str) -> tuple[int, int, int]:
    match = VERSION_PATTERN.fullmatch(value)
    if match is None:
        raise SystemExit(f"Expected a three-part numeric version, got: {value!r}")
    return tuple(int(part) for part in match.groups())


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: next-version.py <baseVersionName>")

    base_version = parse_version(sys.argv[1])
    tag_output = subprocess.run(
        ["git", "tag", "--list"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    tagged_versions = [
        parse_version(tag)
        for tag in tag_output.splitlines()
        if tag.startswith("v") and VERSION_PATTERN.fullmatch(tag)
    ]

    major, minor, _patch = max(tagged_versions, default=base_version)
    next_minor = minor + 1
    if next_minor == 8:
        print(f"{major + 1}.0.0")
    else:
        print(f"{major}.{next_minor}.0")


if __name__ == "__main__":
    main()
