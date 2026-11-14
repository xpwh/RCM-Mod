# Ballistic Missiles (Fabric 1.21.11 mod)

## Version: bump it with every update

Every update that is committed and handed over (the jar sent to the user) gets a version bump,
chosen by the size of the change, **without being asked**:

```
python3 tools/bump_version.py major   # big update: a whole new system or a large rework
python3 tools/bump_version.py minor   # new feature or clearly visible improvement
python3 tools/bump_version.py patch   # fix or small tweak
```

- Bump once per update (one request = one bump), in the same commit as the change, and name the new
  version in the commit message and in the summary to the user (e.g. "Version 7.2.0").
- The jar is `build/libs/ballistic-missiles-<version>.jar`; send that one.
- Several small fixes in one go: one patch bump. A feature plus fixes: one minor bump.

## Workflow

- Build: `JAVA_HOME=/opt/jdk25 ./gradlew build --no-daemon --console=plain -q`
- The user tests in game themselves; never start the game or a server.
- Commit, push to the working branch, send the jar with a short German summary.
- Shaders cannot be tested in game here: validate GLSL offline (`glslangValidator`, with the
  `#moj_import` includes inlined) before shipping.
