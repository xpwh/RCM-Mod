# Credits

## Sounds

Most sounds in this mod are synthesized by the scripts in `tools/`. The following are real recordings:

| File | Source | Author | License |
| --- | --- | --- | --- |
| `assets/ballisticmissiles/sounds/a10/gun.ogg`, `a10/gun_tail.ogg`, `a10/flyby.ogg` | ["A-10.ogg"](https://freesound.org/people/qubodup/sounds/205582/) (A-10 Warthog flyby and shooting, extracted from a US Government video) | qubodup | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) - the GAU-8 burst at 79.25-81.7 s, the rumble after it and the pass at 12.75-19.75 s cut out, mono, bass EQ and compression (`tools/import_a10_freesound.py`) |
| `assets/ballisticmissiles/sounds/missile/ignition.ogg`, `missile/crackle.ogg`, `air_defense/launch.ogg` | [NASA: SpaceX CLPS IM-2 launch, pad microphones](https://images.nasa.gov/details/KSC-20250226-AU-ILW01-0002-SpaceX_CLPS_IM-2_Live_Launch_Coverage_PadMic-1_PadMic-2) | NASA / Kennedy Space Center | Public domain (NASA media) - cut, filtered, looped / sped up |
| `assets/ballisticmissiles/sounds/missile/ignition_sub.ogg`, `missile/engine.ogg` | [NASA: Artemis II launch, pad camera site 6](https://images.nasa.gov/details/KSC-20260401-AU-LMM01-0001-Artemis_II_Live_Launch_Coverage_Pad_CS6) | NASA / Kennedy Space Center | Public domain (NASA media) - cut, low-passed, looped |
| `assets/ballisticmissiles/sounds/explosion/shock_*.ogg` (all shock wave cracks), `nuke/near.ogg`, `nuke/mid.ogg` | ["Explosion-LS100155.ogg"](https://commons.wikimedia.org/wiki/File:Explosion-LS100155.ogg) | Fg2 | Public domain - cut, slowed down / layered / filtered per explosion type, mixed with synthesized low end and echoes |
| `assets/ballisticmissiles/sounds/missile/incoming.ogg`, `explosion/near.ogg`, `explosion/mid.ogg`, `explosion/far.ogg` | ["Missile Impact" (#1592)](https://soundbible.com/1592-Missile-Impact.html) | SoundBible.com | Public domain - fly-in and impact split, mono, punch, sub rumble and echoes added, distance variants filtered |

`tools/import_real_sounds.py` downloads these sources and reproduces the processing.
