"""Writes the blast post effect variants: post effect uniforms are fixed per JSON file, so the client
switches between strength levels and animation phases instead of updating a uniform."""
import json
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "client", "resources", "assets", "ballisticmissiles", "post_effect")
LEVELS = 5
PHASES = 4

os.makedirs(OUT, exist_ok=True)
for level in range(1, LEVELS + 1):
    for phase in range(PHASES):
        effect = {
            "targets": {"swap": {}},
            "passes": [
                {
                    "vertex_shader": "minecraft:core/screenquad",
                    "fragment_shader": "ballisticmissiles:post/blast",
                    "inputs": [{"sampler_name": "In", "target": "minecraft:main", "bilinear": True}],
                    "output": "swap",
                    "uniforms": {
                        "BlastConfig": [
                            {"name": "Strength", "type": "float", "value": round(level / LEVELS, 3)},
                            {"name": "Phase", "type": "float", "value": float(phase)},
                        ]
                    },
                },
                {
                    "vertex_shader": "minecraft:core/screenquad",
                    "fragment_shader": "minecraft:post/blit",
                    "inputs": [{"sampler_name": "In", "target": "swap"}],
                    "output": "minecraft:main",
                    "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
                },
            ],
        }
        with open(os.path.join(OUT, f"blast_{level}_{phase}.json"), "w", newline="\r\n") as f:
            json.dump(effect, f, indent="\t")
            f.write("\n")
print("written", LEVELS * PHASES, "post effects")
