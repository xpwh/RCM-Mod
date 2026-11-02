"""Bump the mod version in gradle.properties (semantic versioning: MAJOR.MINOR.PATCH).

usage: python3 tools/bump_version.py major|minor|patch

  major  a big update: a whole new system or a large rework (new weapon family, volumetric clouds, ...)
  minor  a new feature or a clearly visible improvement (a new effect, a new setting, a new animation)
  patch  a fix or a small tweak (a bug, wrong timing, a texture or a sound adjusted)

Prints the new version. The jar is named after it (build/libs/ballistic-missiles-<version>.jar).
"""
import os
import re
import sys

PROPS = os.path.join(os.path.dirname(__file__), "..", "gradle.properties")


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("major", "minor", "patch"):
        print(__doc__)
        sys.exit(1)
    text = open(PROPS).read()
    m = re.search(r"(?m)^version=(\d+)\.(\d+)\.(\d+)\s*$", text)
    if not m:
        sys.exit("no version=MAJOR.MINOR.PATCH line in gradle.properties")
    major, minor, patch = map(int, m.groups())
    kind = sys.argv[1]
    if kind == "major":
        major, minor, patch = major + 1, 0, 0
    elif kind == "minor":
        minor, patch = minor + 1, 0
    else:
        patch += 1
    new = "%d.%d.%d" % (major, minor, patch)
    text = text[:m.start()] + "version=" + new + text[m.end():]
    open(PROPS, "w").write(text)
    print(new)


if __name__ == "__main__":
    main()
