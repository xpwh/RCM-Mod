# Offline animation studio (first-person shotgun)

Renders the first-person shotgun animations without starting the game, through the same
`ShotgunMotion` code the game uses (scripted scenes: aim + shot + pump, hip fire, loading,
dry click, draw, sprint, walk, landing, idle inspect).

```
./gradlew compileClientJava
CP="build/classes/java/client:build/classes/java/main:<minecraft + libraries classpath>"
javac -proc:none -cp "$CP" -d /tmp/studio tools/anim_studio/Studio.java
java -cp "/tmp/studio:$CP" Studio src/main/resources/assets/ballisticmissiles/textures/entity/structures.png <outdir>
python3 tools/anim_studio/render.py <previewdir> <outdir>/*.bin   # GIF + contact sheet per scene
```

## C4 and its firing device (`C4Studio.java`)

The same studio for C4: scenes through `C4Motion` (charge_idle, charge_place, device_idle, device_fire,
device_select). It reads the Blender meshes straight from `src/client/resources/.../meshes` and colours them from
the baked base-colour textures; compile it next to `Studio.java` and run `java C4Studio <outdir> [scenes]`, then
`render.py` on the `c_*.bin` files.
