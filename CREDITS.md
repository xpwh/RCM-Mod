# Credits

## Sounds

Most sounds in this mod are synthesized by the scripts in `tools/`. The following are real recordings:

| File | Source | Author | License |
| --- | --- | --- | --- |
| `assets/ballisticmissiles/sounds/a10/gun.ogg` | ["BRRRRRRT.wav"](https://commons.wikimedia.org/wiki/File:BRRRRRRT.wav), originally [freesound #127737](https://freesound.org/people/nicStage/sounds/127737/) | nicStage | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) - trimmed to 8 s, mono, EQ, compression and fade-out applied |
| `assets/ballisticmissiles/sounds/missile/ignition.ogg`, `missile/crackle.ogg`, `air_defense/launch.ogg` | [NASA: SpaceX CLPS IM-2 launch, pad microphones](https://images.nasa.gov/details/KSC-20250226-AU-ILW01-0002-SpaceX_CLPS_IM-2_Live_Launch_Coverage_PadMic-1_PadMic-2) | NASA / Kennedy Space Center | Public domain (NASA media) - cut, filtered, looped / sped up |
| `assets/ballisticmissiles/sounds/missile/ignition_sub.ogg`, `missile/engine.ogg` | [NASA: Artemis II launch, pad camera site 6](https://images.nasa.gov/details/KSC-20260401-AU-LMM01-0001-Artemis_II_Live_Launch_Coverage_Pad_CS6) | NASA / Kennedy Space Center | Public domain (NASA media) - cut, low-passed, looped |
| `assets/ballisticmissiles/sounds/explosion/shock_*.ogg` (all shock wave cracks), `explosion/near.ogg`, `explosion/mid.ogg`, `explosion/far.ogg`, `nuke/near.ogg`, `nuke/mid.ogg` | ["Explosion-LS100155.ogg"](https://commons.wikimedia.org/wiki/File:Explosion-LS100155.ogg) | Fg2 | Public domain - cut, slowed down / layered / filtered per explosion type, mixed with synthesized low end and echoes |

`tools/import_real_sounds.py` downloads these sources and reproduces the processing.
