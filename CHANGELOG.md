# Ballistic Missiles – Änderungsprotokoll

Änderungen von der realistischen AK-47 (Version 12.14.0) bis heute, neueste zuerst. Stand: Version **12.39.1** (01.11.2026).

---

### 12.39.1 – Keine Zufalls-Explosionen mehr beim Spawnen
- Tiere und Monster lösen die Landminen in den Minenfeldern der Schützengraben-Basen nur noch aus, wenn ein Spieler in der Nähe ist (48 Blöcke). Spieler, Soldaten und Fahrzeuge lösen sie wie bisher immer aus.
- Handgranaten und Minen sind nur noch bis 800 Blöcke zu hören statt bis 3.000.

### 12.39.0 – Einstellungsmenü (/bmmenu) komplett neu gestaltet
- Seitenleiste links mit Logo, Symbol und Farbe je Bereich. Die aktive Seite ist farbig markiert, Hover wird weich eingeblendet.
- Großer Seitentitel mit kurzer Beschreibung, beim Seitenwechsel gleitet er herein.
- Schalter statt „An/Aus“-Knöpfen: Pillen mit gleitendem Knopf in der Farbe der Seite.
- Regler mit schmaler Spur, Knopf und farbigem Wert. Auswahl-Optionen zeigen ihren Wert in einem Chip mit ‹ ›.
- Abschnitts-Überschriften mit Farbpunkt, Hinweise in eigenen Boxen, dünne farbige Scrollleiste.
- Fußzeile mit Versionsnummer und neuem „Fertig“-Knopf, Statusmeldungen als grüne Pille.

### 12.38.2 – Neue Welten laden wieder
- Die häufigeren Städte aus 12.38.1 sind zurückgenommen: wieder seltener und mit der alten Platzwahl. Neue Welten hingen beim Laden („Loading terrain“).
- `/bmfinden city` bleibt und sucht weiterhin 2,5-mal so weit wie `/locate`.

### 12.38.1 – Große Stadt auffindbar
- Neuer Befehl `/bmfinden <ort>` (auch `/bmlocate`) mit Vorschlägen beim Tippen: Stadt, Militärbasis, Flugzeugträger, Geheimlabor und alle anderen Mod-Orte. Er sucht 2,5-mal so weit wie `/locate`, die Koordinaten in der Antwort sind anklickbar.
- Städte entstehen öfter: dichteres Raster und weniger strenge Ansprüche an ebenes, trockenes Gelände.
- Neue Struktur-Tags `#ballisticmissiles:city` und `#ballisticmissiles:all` für `/locate`.

### 12.38.0 – Ladehemmungen, KI lädt realistisch nach, Neuerungen im Spiel
- Ladehemmungen bei AK und Glock: Ab und zu bleibt die leere Hülse in der Auswurföffnung stecken. Häufiger bei langen Feuerstößen, Regen oder Wasser und mit Schalldämpfer.
- Solange die Waffe klemmt, klickt der Abzug nur. Die Hülse steht sichtbar aus der Auswurföffnung.
- **R** behebt die Hemmung mit einer in Blender gemachten Animation (je 3 Varianten): Magazin von unten festschlagen, Hebel bzw. Schlitten kräftig durchziehen, die Hülse fliegt heraus.
- KI-Soldaten laden mit denselben Blender-Animationen nach wie du, inklusive Fummeln und fallengelassenem Magazin unter Beschuss.
- Nach einem Update zeigt ein Fenster beim Start, was neu ist. Abschaltbar und jederzeit über „Neuerungen anzeigen“ in den Einstellungen.

### 12.37.1 – AK-Visier korrigiert
- Die Kimme sitzt wieder am hinteren Ende des Visierblatts, das Auge 22 cm dahinter.
- Hand und Waffe werden erst 5 mm statt 5 cm vor dem Auge abgeschnitten: Der Verschlussdeckel bricht beim Zielen nicht mehr als graue Fläche auf.

### 12.37.0 – Matte Waffen, sortiertes Kreativ-Inventar
- AK und Glock sind matt statt glänzend, das Holz wirkt geölt statt lackiert.
- Das Kreativ-Inventar ist in 7 sortierte Tabs aufgeteilt, mit neuen Tabs „Waffen“ und „Drohnen & Jets“.

### 12.36.0 – 18+-Warnung neu gestaltet
- Symbol-Kacheln zeigen, was dich erwartet.
- Drei Stufen zur Wahl: Aus, Nur Blut, Alles. Für „Alles“ muss der Knopf gedrückt gehalten werden.

### 12.35.0 – Tsar Bomba: Riesenpilz, Stille, Hitzeblitz, EMP
- Pilzwolke im echten Maßstab: rund 560 Blöcke hoch, Hut rund 175 Blöcke breit, mit Eiskappe (Pileus) und Kondensationsringen am Stiel.
- Die Wolke reißt ein großes Loch in die Wolkendecke.
- Stille vor dem Knall: Je näher die sichtbare Druckwelle kommt, desto leiser wird die Welt, bis fast völlige Stille.
- Hitzeblitz: Wer bis 1.500 Blöcke freie Sicht auf den Feuerball hat, verbrennt und fängt Feuer. Wer hinsieht, wird geblendet. Deckung schützt.
- EMP:
  - Bis 1.200 Blöcke fallen Radar und Abwehr aus.
  - In der Nähe (bis rund 720 Blöcke) verlieren Raketen ihre Lenkung, Drohnen stürzen ab und Redstone brennt durch.
  - Nachtsicht und Wärmebild flackern mit Bildrauschen, Lampen und Waffenlichter flackern.
- Der Kraterboden glüht weit nach außen, und die Strahlungszone reicht über den Kraterrand.
- Überlebende Spieler und Tiere glühen minutenlang grünlich, mit Licht und aufsteigenden Partikeln.

### 12.34.0 – Tsar Bomba: 3-facher Krater, sichtbare Druckwelle
- Kraterradius von 80 auf 240 Blöcke: flache Glasschüssel mit höherem Auswurfwall.
- Der Krater wird mit einem Budget pro Tick gegraben, damit der Server nicht einfriert.
- Eine Staub- und Rauchwand rast mit der echten Schadensfront über das Land.
- Knall, Beben, Staubsturm und mehrere Sekunden Echo kommen erst, wenn die Wand bei dir ankommt.

### 12.33.0 – Eigener Körper, Rotorabwind, Nachtsicht
- Eigener Körper in Ego-Sicht: Beine, Brust und Stiefel beim Blick nach unten. In den Einstellungen abschaltbar.
- Rotorabwind von Hubschraubern, tief fliegenden Jets und Drohnen drückt Gras und Laub weg und wirbelt Staub, Sand, Schnee oder Gischt auf.
- Nachtsicht überstrahlt bei Mündungsfeuer und Explosionen und regelt danach herunter.
- IR-Blinklichter an den Helmen verbündeter Spieler, nur durch Nachtsicht sichtbar.

### 12.32.0 – Viel realistischere Waffen-Animationen
- Hände drehen sich mit dem Magazin mit, Unterarme rollen.
- Kopf- und Kamerabewegung beim Nachladen: Blick zum Schacht, Ruck beim Einrasten.
- Magazine fallen per Blender-Starrkörper-Simulation (fallen, taumeln, prallen ab).
- Nachschwingen der Waffe; beim Nachladen hängt sie beim Umsehen stärker nach.
- 3 leicht verschiedene Varianten pro Nachladen, zufällig gewählt.
- Nachladen in der dritten Person ist für andere sichtbar.

### 12.31.0 – Nachladen unter Stress
- Unter Beschuss: Fummeln am Schacht, fallengelassene Magazine, zitternde Hände (Stress, Kälte).

### 12.30.0 – Welt & Natur, Leistung
- Neuer Einstellungs-Tab „Welt & Natur“.
- Server-Schalter für Schnee, Lawinen, Aschesturm, Temperatur, Blitzschäden, Shulkerwachen, Nachwachsen und Gewittervorhersage.
- Leistungsverbesserungen.

### 12.29.0 – Blitze schlagen realistisch ein

### 12.28.0 – Schnee, Körpertemperatur, Wetter-Satellit
- Schnee sammelt sich an, mit Spuren, Schmelzkratern und Lawinen.
- Körpertemperatur bei Hitze und Kälte: Zittern, Atemwolken, Schweiß, Thermometer.
- Wetter-Satellit als Karten-Item.

### 12.27.0 – Nether und End
- Hitzeflimmern und Aschestürme im Nether.
- Echter End-Himmel, überarbeiteter Drachenkampf, Lichter und Shulker-Wachen in End-Städten.
- Realistischere Portale.

### 12.26.0 – Realistische Blitze

### 12.25.0 – Realistischere ferne Gewitter

### 12.24.0 – Gewitter ziehen sichtbar heran

### 12.23.0 – Regenbögen, Wind im Gras, Nachtgeräusche

### 12.22.0 – Lebendige Natur, Ruinen wachsen zu

### 12.21.0 – Funktionierende Ampeln, Lichtkegel, flackernde Stromausfälle

### 12.20.0 – Stadtlichter mit eigenen Zeiten, Neonschilder, spiegelnd nasse Straßen

### 12.19.0 – Die Stadt im Detail

### 12.18.0 – Große Stadt mit Hochhäusern, Basis-Details, keine Bäume auf Anlagen

### 12.17.0 / 12.17.1 – Glock 19 in Blender neu gebaut
- Glock 19 in Blender neu gebaut, Animationen in Blender gekeyframed.
- Militärbasen und Außenposten liegen doppelt so weit auseinander.

### 12.16.0 – AK-47-Animationen in Blender gekeyframed

### 12.15.0 / 12.15.1 – Physikalisch basierter Waffen-Shader für die AK-47, Handschutz korrigiert

### 12.14.0 – Neues AK-47-Modell in Blender nach Referenzfoto
