"""Writes the JSON resources (lang, models, blockstates, recipes, loot) for the content added in 1.2."""
import json
import os

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
ASSETS = os.path.join(ROOT, "assets", "ballisticmissiles")
DATA = os.path.join(ROOT, "data")


def write(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent="\t", ensure_ascii=False)
        f.write("\n")


NEW_MISSILES = ["incendiary_missile", "anti_radar_missile", "emp_missile", "tactical_nuke"]

DE = {
    "item.ballisticmissiles.incendiary_missile": "Brandbomben-Rakete",
    "item.ballisticmissiles.anti_radar_missile": "Anti-Radar-Rakete",
    "item.ballisticmissiles.emp_missile": "EMP-Rakete",
    "item.ballisticmissiles.tactical_nuke": "Taktische Atomrakete",
    "item.ballisticmissiles.mobile_launcher": "Mobiler Raketenwerfer (LKW)",
    "entity.ballisticmissiles.incendiary_missile": "Brandbomben-Rakete",
    "entity.ballisticmissiles.anti_radar_missile": "Anti-Radar-Rakete",
    "entity.ballisticmissiles.emp_missile": "EMP-Rakete",
    "entity.ballisticmissiles.tactical_nuke": "Taktische Atomrakete",
    "entity.ballisticmissiles.mobile_launcher": "Mobiler Raketenwerfer",
    "block.ballisticmissiles.missile_silo": "Raketensilo",
    "tooltip.ballisticmissiles.incendiary_missile": "Zerlegt sich über dem Ziel in 40 brennende Brandsätze",
    "tooltip.ballisticmissiles.anti_radar_missile": "Marschflug – sucht sich das nächste Radar / die nächste Flugabwehr am Ziel",
    "tooltip.ballisticmissiles.emp_missile": "Höhenexplosion – EMP legt Radar, Flugabwehr, Silos, Raketen und Redstone lahm",
    "tooltip.ballisticmissiles.tactical_nuke": "☢ Kleiner Atomsprengkopf – kleiner Krater, Atompilz",
    "tooltip.ballisticmissiles.designator_1": "Rechtsklick: anvisierten Block als Ziel (auch hinter der Sichtweite bis zum Horizont)",
    "tooltip.ballisticmissiles.designator_2": "Schleichen + Rechtsklick: Zielcomputer (Karte, Speicher, Spieler, Fernstart)",
    "tooltip.ballisticmissiles.designator_3": "Rakete / Silo / LKW damit anklicken: Countdown starten / abbrechen",
    "tooltip.ballisticmissiles.designator_4": "Startrampe anklicken oder Silo / LKW schleichend anklicken: für Fernstart verknüpfen",
    "tooltip.ballisticmissiles.designator_memory": "%s gespeicherte Ziele, %s verknüpfte Startsysteme",
    "tooltip.ballisticmissiles.truck_1": "Fahrbarer Werfer für taktische Raketen und Marschflugkörper",
    "tooltip.ballisticmissiles.truck_2": "Rechtsklick: einsteigen (WASD fahren) – Rakete in der Hand: aufladen",
    "tooltip.ballisticmissiles.truck_3": "Mit dem Zielmarkierer anklicken: aufrichten und starten",
    "message.ballisticmissiles.intercept_hit": "ABGEFANGEN: %s zerstört",
    "message.ballisticmissiles.intercept_miss": "VERFEHLT: %s fliegt weiter – Nachschuss!",
    "message.ballisticmissiles.sam_launch": "Abfangrakete gestartet → %s (Magazin %s/%s)",
    "message.ballisticmissiles.ad_status": "Flugabwehr: %s/%s Raketen | %s, Reichweite %s | Abschüsse %s, Fehlschüsse %s",
    "message.ballisticmissiles.ad_linked": "mit Radar gekoppelt",
    "message.ballisticmissiles.ad_autonomous": "autonom (kein Radar in 96 Blöcken)",
    "message.ballisticmissiles.jammed": "⚡ ELEKTRONIK AUSGEFALLEN (EMP) – noch %s s",
    "message.ballisticmissiles.radar_track": "RADAR %s %s aus %s – %s Blöcke, Höhe %s, Einschlag ≈ %s / %s (±%s) in %s s",
    "message.ballisticmissiles.radar_more": " (+%s weitere)",
    "message.ballisticmissiles.links_full": "Maximal %s Startsysteme verknüpfbar.",
    "message.ballisticmissiles.linked": "⛓ Verknüpft: %s (%s Startsysteme)",
    "message.ballisticmissiles.unlinked": "Verknüpfung gelöst: %s (%s Startsysteme)",
    "message.ballisticmissiles.memory_full": "Zielspeicher voll (%s).",
    "message.ballisticmissiles.target_saved": "Ziel gespeichert: %s",
    "message.ballisticmissiles.player_unknown": "Spieler %s nicht gefunden (andere Dimension?)",
    "message.ballisticmissiles.no_launchers": "Kein Startsystem ausgewählt.",
    "message.ballisticmissiles.remote_sent": "Startbefehl an %s Startsystem(e) gesendet …",
    "message.ballisticmissiles.remote_unreachable": "keine Verbindung",
    "message.ballisticmissiles.remote_gone": "existiert nicht mehr",
    "message.ballisticmissiles.remote_empty": "keine Rakete auf der Rampe",
    "message.ballisticmissiles.remote_busy": "bereits im Startablauf",
    "message.ballisticmissiles.remote_launching": "COUNTDOWN LÄUFT",
    "message.ballisticmissiles.remote_refused": "Start verweigert (Ziel zu nah?)",
    "message.ballisticmissiles.remote_idle": "kein laufender Start",
    "message.ballisticmissiles.silo_empty": "Silo leer – Rakete in der Hand rechtsklicken zum Laden",
    "message.ballisticmissiles.silo_full": "Das Silo ist schon beladen.",
    "message.ballisticmissiles.silo_counting": "☢ Silo: %s – COUNTDOWN LÄUFT",
    "message.ballisticmissiles.silo_loaded": "Silo bereit: %s",
    "message.ballisticmissiles.truck_empty": "Der Werfer hat keine Rakete geladen.",
    "message.ballisticmissiles.truck_erecting": "⚠ Werfer richtet auf – Ziel %s (%s Blöcke). Besatzung aussteigen!",
    "message.ballisticmissiles.truck_too_big": "Zu groß für den LKW – Interkontinentalraketen gehören ins Silo oder auf die Rampe.",
    "message.ballisticmissiles.truck_full": "Der Werfer ist schon beladen.",
    "message.ballisticmissiles.truck_loaded": "Rakete auf den Werfer geladen.",
    "message.ballisticmissiles.emp_hit": "ELEKTROMAGNETISCHER PULS – Elektronik ausgefallen!",
    "message.ballisticmissiles.long_range": "⌖ Fernpeilung: Ziel am Horizont in ca. %s Blöcken (Bodenhöhe wird beim Start ermittelt)",
    "radar.ballisticmissiles.class.unknown": "UNBEKANNT",
    "radar.ballisticmissiles.class.ballistic": "BALLISTISCH",
    "radar.ballisticmissiles.class.cruise": "MARSCHFLUGKÖRPER",
    "radar.ballisticmissiles.class.hypersonic": "HYPERSCHALL",
    "radar.ballisticmissiles.class.reentry": "WIEDEREINTRITTSKÖRPER",
    "radar.ballisticmissiles.compass.n": "Norden",
    "radar.ballisticmissiles.compass.ne": "Nordosten",
    "radar.ballisticmissiles.compass.e": "Osten",
    "radar.ballisticmissiles.compass.se": "Südosten",
    "radar.ballisticmissiles.compass.s": "Süden",
    "radar.ballisticmissiles.compass.sw": "Südwesten",
    "radar.ballisticmissiles.compass.w": "Westen",
    "radar.ballisticmissiles.compass.nw": "Nordwesten",
    "launcher.ballisticmissiles.pad": "Rampe %s/%s",
    "launcher.ballisticmissiles.silo": "Silo %s/%s",
    "launcher.ballisticmissiles.truck": "LKW %s/%s",
    "screen.ballisticmissiles.target": "ZIELCOMPUTER",
    "screen.ballisticmissiles.radar": "RADAR – LAGEBILD",
    "screen.ballisticmissiles.radar_jammed": "⚡ STÖRUNG – EMP (%s s)",
    "screen.ballisticmissiles.radar_range": "Reichweite %s · Umlauf %s s",
    "screen.ballisticmissiles.radar_emp": "Elektronik durch EMP ausgefallen",
    "screen.ballisticmissiles.radar_clear": "Keine Kontakte",
    "screen.ballisticmissiles.legend_radar": "Radar",
    "screen.ballisticmissiles.legend_sam": "Flugabwehr",
    "screen.ballisticmissiles.legend_silo": "Silo",
    "screen.ballisticmissiles.tab_target": "Ziel",
    "screen.ballisticmissiles.tab_memory": "Speicher",
    "screen.ballisticmissiles.tab_players": "Spieler",
    "screen.ballisticmissiles.tab_launch": "Start",
    "screen.ballisticmissiles.here": "Hier",
    "screen.ballisticmissiles.map_center": "Kartenmitte",
    "screen.ballisticmissiles.last_death": "Todespunkt",
    "screen.ballisticmissiles.confirm": "Setzen",
    "screen.ballisticmissiles.name": "Name",
    "screen.ballisticmissiles.save": "Speichern",
    "screen.ballisticmissiles.memory_empty": "Zielspeicher leer – Ziel setzen, Namen eingeben, Speichern.",
    "screen.ballisticmissiles.no_players": "Keine anderen Spieler online.",
    "screen.ballisticmissiles.target_player": "Position als Ziel übernehmen",
    "screen.ballisticmissiles.player_targeted": "Ziel: aktuelle Position von %s",
    "screen.ballisticmissiles.no_links": "Keine Startsysteme verknüpft – Rampe / Silo / LKW mit dem Zielmarkierer anklicken.",
    "screen.ballisticmissiles.fire": "FEUER (%s)",
    "screen.ballisticmissiles.fire_confirm": "BESTÄTIGEN – FEUER (%s)",
    "screen.ballisticmissiles.fired": "Startbefehl an %s Startsystem(e) gesendet!",
    "screen.ballisticmissiles.abort": "Abbruch",
    "screen.ballisticmissiles.target_sent": "Ziel gesetzt: %s, %s, %s",
    "screen.ballisticmissiles.map_scale": "%s Blöcke",
    "screen.ballisticmissiles.look_ahead": "In Blickrichtung (Blöcke):",
    "screen.ballisticmissiles.launch_hint": "Klick = auswählen · FEUER zweimal drücken",
    "screen.ballisticmissiles.hint": "Karte anklicken = Ziel · Ziehen = verschieben · Mausrad = Zoom",
}

EN = {
    "item.ballisticmissiles.incendiary_missile": "Incendiary Missile",
    "item.ballisticmissiles.anti_radar_missile": "Anti-Radiation Missile",
    "item.ballisticmissiles.emp_missile": "EMP Missile",
    "item.ballisticmissiles.tactical_nuke": "Tactical Nuclear Missile",
    "item.ballisticmissiles.mobile_launcher": "Mobile Missile Launcher (Truck)",
    "entity.ballisticmissiles.incendiary_missile": "Incendiary Missile",
    "entity.ballisticmissiles.anti_radar_missile": "Anti-Radiation Missile",
    "entity.ballisticmissiles.emp_missile": "EMP Missile",
    "entity.ballisticmissiles.tactical_nuke": "Tactical Nuclear Missile",
    "entity.ballisticmissiles.mobile_launcher": "Mobile Missile Launcher",
    "block.ballisticmissiles.missile_silo": "Missile Silo",
    "tooltip.ballisticmissiles.incendiary_missile": "Opens above the target into 40 burning incendiary bomblets",
    "tooltip.ballisticmissiles.anti_radar_missile": "Cruise flight – homes onto the nearest radar / air defense at the target",
    "tooltip.ballisticmissiles.emp_missile": "High-altitude burst – the EMP knocks out radars, air defense, silos, missiles and redstone",
    "tooltip.ballisticmissiles.tactical_nuke": "☢ Low-yield nuclear warhead – small crater, mushroom cloud",
    "tooltip.ballisticmissiles.designator_1": "Right-click: lock the block you look at (beyond render distance up to the horizon)",
    "tooltip.ballisticmissiles.designator_2": "Sneak + right-click: targeting computer (map, memory, players, remote launch)",
    "tooltip.ballisticmissiles.designator_3": "Click a missile / silo / truck with it: start / abort the countdown",
    "tooltip.ballisticmissiles.designator_4": "Click a launch pad, or sneak-click a silo / truck: link it for remote launch",
    "tooltip.ballisticmissiles.designator_memory": "%s saved targets, %s linked launchers",
    "tooltip.ballisticmissiles.truck_1": "Drivable launcher for tactical and cruise missiles",
    "tooltip.ballisticmissiles.truck_2": "Right-click: get in (WASD to drive) – missile in hand: load it",
    "tooltip.ballisticmissiles.truck_3": "Click with the target designator: erect and launch",
    "message.ballisticmissiles.intercept_hit": "INTERCEPTED: %s destroyed",
    "message.ballisticmissiles.intercept_miss": "MISSED: %s still inbound – re-engaging!",
    "message.ballisticmissiles.sam_launch": "Interceptor away → %s (magazine %s/%s)",
    "message.ballisticmissiles.ad_status": "Air defense: %s/%s missiles | %s, range %s | kills %s, misses %s",
    "message.ballisticmissiles.ad_linked": "linked to radar",
    "message.ballisticmissiles.ad_autonomous": "autonomous (no radar within 96 blocks)",
    "message.ballisticmissiles.jammed": "⚡ ELECTRONICS DEAD (EMP) – %s s left",
    "message.ballisticmissiles.radar_track": "RADAR %s %s from the %s – %s blocks, altitude %s, impact ≈ %s / %s (±%s) in %s s",
    "message.ballisticmissiles.radar_more": " (+%s more)",
    "message.ballisticmissiles.links_full": "You can link at most %s launchers.",
    "message.ballisticmissiles.linked": "⛓ Linked: %s (%s launchers)",
    "message.ballisticmissiles.unlinked": "Unlinked: %s (%s launchers)",
    "message.ballisticmissiles.memory_full": "Target memory full (%s).",
    "message.ballisticmissiles.target_saved": "Target saved: %s",
    "message.ballisticmissiles.player_unknown": "Player %s not found (other dimension?)",
    "message.ballisticmissiles.no_launchers": "No launcher selected.",
    "message.ballisticmissiles.remote_sent": "Launch order sent to %s launcher(s) …",
    "message.ballisticmissiles.remote_unreachable": "no connection",
    "message.ballisticmissiles.remote_gone": "no longer exists",
    "message.ballisticmissiles.remote_empty": "no missile on the pad",
    "message.ballisticmissiles.remote_busy": "already launching",
    "message.ballisticmissiles.remote_launching": "COUNTDOWN RUNNING",
    "message.ballisticmissiles.remote_refused": "launch refused (target too close?)",
    "message.ballisticmissiles.remote_idle": "no launch in progress",
    "message.ballisticmissiles.silo_empty": "Silo empty – right-click with a missile to load it",
    "message.ballisticmissiles.silo_full": "The silo is already loaded.",
    "message.ballisticmissiles.silo_counting": "☢ Silo: %s – COUNTDOWN RUNNING",
    "message.ballisticmissiles.silo_loaded": "Silo ready: %s",
    "message.ballisticmissiles.truck_empty": "The launcher has no missile loaded.",
    "message.ballisticmissiles.truck_erecting": "⚠ Erecting launcher – target %s (%s blocks). Crew out!",
    "message.ballisticmissiles.truck_too_big": "Too big for the truck – ICBMs belong in a silo or on a pad.",
    "message.ballisticmissiles.truck_full": "The launcher is already loaded.",
    "message.ballisticmissiles.truck_loaded": "Missile loaded onto the launcher.",
    "message.ballisticmissiles.emp_hit": "ELECTROMAGNETIC PULSE – electronics knocked out!",
    "message.ballisticmissiles.long_range": "⌖ Long-range fix: target on the horizon about %s blocks away (ground height is resolved at launch)",
    "radar.ballisticmissiles.class.unknown": "UNKNOWN",
    "radar.ballisticmissiles.class.ballistic": "BALLISTIC",
    "radar.ballisticmissiles.class.cruise": "CRUISE MISSILE",
    "radar.ballisticmissiles.class.hypersonic": "HYPERSONIC",
    "radar.ballisticmissiles.class.reentry": "RE-ENTRY VEHICLE",
    "radar.ballisticmissiles.compass.n": "north",
    "radar.ballisticmissiles.compass.ne": "northeast",
    "radar.ballisticmissiles.compass.e": "east",
    "radar.ballisticmissiles.compass.se": "southeast",
    "radar.ballisticmissiles.compass.s": "south",
    "radar.ballisticmissiles.compass.sw": "southwest",
    "radar.ballisticmissiles.compass.w": "west",
    "radar.ballisticmissiles.compass.nw": "northwest",
    "launcher.ballisticmissiles.pad": "Pad %s/%s",
    "launcher.ballisticmissiles.silo": "Silo %s/%s",
    "launcher.ballisticmissiles.truck": "Truck %s/%s",
    "screen.ballisticmissiles.target": "TARGETING COMPUTER",
    "screen.ballisticmissiles.radar": "RADAR – SITUATION",
    "screen.ballisticmissiles.radar_jammed": "⚡ JAMMED – EMP (%s s)",
    "screen.ballisticmissiles.radar_range": "Range %s · sweep %s s",
    "screen.ballisticmissiles.radar_emp": "Electronics knocked out by EMP",
    "screen.ballisticmissiles.radar_clear": "No contacts",
    "screen.ballisticmissiles.legend_radar": "Radar",
    "screen.ballisticmissiles.legend_sam": "Air defense",
    "screen.ballisticmissiles.legend_silo": "Silo",
    "screen.ballisticmissiles.tab_target": "Target",
    "screen.ballisticmissiles.tab_memory": "Memory",
    "screen.ballisticmissiles.tab_players": "Players",
    "screen.ballisticmissiles.tab_launch": "Launch",
    "screen.ballisticmissiles.here": "Here",
    "screen.ballisticmissiles.map_center": "Map center",
    "screen.ballisticmissiles.last_death": "Death point",
    "screen.ballisticmissiles.confirm": "Set",
    "screen.ballisticmissiles.name": "Name",
    "screen.ballisticmissiles.save": "Save",
    "screen.ballisticmissiles.memory_empty": "Target memory empty – set a target, enter a name, save.",
    "screen.ballisticmissiles.no_players": "No other players online.",
    "screen.ballisticmissiles.target_player": "Use their position as target",
    "screen.ballisticmissiles.player_targeted": "Target: current position of %s",
    "screen.ballisticmissiles.no_links": "No launchers linked – click a pad / silo / truck with the designator.",
    "screen.ballisticmissiles.fire": "FIRE (%s)",
    "screen.ballisticmissiles.fire_confirm": "CONFIRM – FIRE (%s)",
    "screen.ballisticmissiles.fired": "Launch order sent to %s launcher(s)!",
    "screen.ballisticmissiles.abort": "Abort",
    "screen.ballisticmissiles.target_sent": "Target set: %s, %s, %s",
    "screen.ballisticmissiles.map_scale": "%s blocks",
    "screen.ballisticmissiles.look_ahead": "Along your view (blocks):",
    "screen.ballisticmissiles.launch_hint": "Click = select · press FIRE twice",
    "screen.ballisticmissiles.hint": "Click the map = target · drag = pan · wheel = zoom",
}


def merge_lang(name, new):
    path = os.path.join(ASSETS, "lang", name)
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    data.update(new)
    write(path, data)


def models():
    for m in NEW_MISSILES + ["mobile_launcher"]:
        write(os.path.join(ASSETS, "items", m + ".json"), {"model": {"type": "minecraft:model", "model": "ballisticmissiles:item/" + m}})
        write(os.path.join(ASSETS, "models", "item", m + ".json"), {"parent": "minecraft:item/handheld", "textures": {"layer0": "ballisticmissiles:item/" + m}})
    write(os.path.join(ASSETS, "items", "missile_silo.json"), {"model": {"type": "minecraft:model", "model": "ballisticmissiles:block/missile_silo"}})
    for model, top in (("missile_silo", "missile_silo_top"), ("missile_silo_open", "missile_silo_top_open")):
        write(os.path.join(ASSETS, "models", "block", model + ".json"), {
            "parent": "minecraft:block/cube_bottom_top",
            "textures": {
                "top": "ballisticmissiles:block/" + top,
                "bottom": "ballisticmissiles:block/missile_silo_bottom",
                "side": "ballisticmissiles:block/missile_silo_side",
            },
        })
    write(os.path.join(ASSETS, "blockstates", "missile_silo.json"), {"variants": {
        "open=false": {"model": "ballisticmissiles:block/missile_silo"},
        "open=true": {"model": "ballisticmissiles:block/missile_silo_open"},
    }})


def shaped(name, pattern, key, count=1):
    write(os.path.join(DATA, "ballisticmissiles", "recipe", name + ".json"), {
        "type": "minecraft:crafting_shaped",
        "category": "combat",
        "pattern": pattern,
        "key": key,
        "result": {"id": "ballisticmissiles:" + name, "count": count},
    })


def data():
    shaped("missile_silo", ["IHI", "CLC", "CRC"], {
        "I": "minecraft:iron_trapdoor", "H": "minecraft:heavy_core", "C": "minecraft:polished_deepslate",
        "L": "ballisticmissiles:launch_pad", "R": "minecraft:redstone_block",
    })
    shaped("mobile_launcher", ["  L", "IPI", "BMB"], {
        "L": "ballisticmissiles:launch_pad", "I": "minecraft:iron_block", "P": "minecraft:piston",
        "B": "minecraft:blast_furnace", "M": "minecraft:minecart",
    })
    shaped("incendiary_missile", [" F ", "BMB", " B "], {
        "F": "minecraft:fire_charge", "B": "minecraft:blaze_powder", "M": "ballisticmissiles:ballistic_missile",
    })
    shaped("anti_radar_missile", [" E ", "CRC", " A "], {
        "E": "minecraft:ender_eye", "C": "minecraft:comparator", "R": "ballisticmissiles:cruise_missile", "A": "minecraft:amethyst_shard",
    })
    shaped("emp_missile", ["LCL", "RMR", "LCL"], {
        "L": "minecraft:lightning_rod", "C": "minecraft:copper_block", "R": "minecraft:redstone_block", "M": "ballisticmissiles:ballistic_missile",
    })
    shaped("tactical_nuke", [" N ", "TMT", " S "], {
        "N": "minecraft:nether_star", "S": "minecraft:netherite_scrap", "T": "minecraft:tnt", "M": "ballisticmissiles:ballistic_missile",
    })
    write(os.path.join(DATA, "ballisticmissiles", "loot_table", "blocks", "missile_silo.json"), {
        "type": "minecraft:block",
        "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "ballisticmissiles:missile_silo"}],
                   "conditions": [{"condition": "minecraft:survives_explosion"}]}],
        "random_sequence": "ballisticmissiles:blocks/missile_silo",
    })
    tag = os.path.join(DATA, "minecraft", "tags", "block", "mineable", "pickaxe.json")
    with open(tag, encoding="utf-8") as f:
        t = json.load(f)
    if "ballisticmissiles:missile_silo" not in t["values"]:
        t["values"].append("ballisticmissiles:missile_silo")
    write(tag, t)


if __name__ == "__main__":
    merge_lang("de_de.json", DE)
    merge_lang("en_us.json", EN)
    models()
    data()
    print("resources ok")
