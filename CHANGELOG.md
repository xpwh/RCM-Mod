# Ballistic Missiles – Änderungsprotokoll

Änderungen von der realistischen AK-47 (Version 12.14.0) bis heute, neueste zuerst. Stand: Version **13.5.0** (01.11.2026).

---

### 13.5.0 – Voxel-Rauch
- Dritte Rauch-Darstellung neben „Volumetrisch“ und „Klassisch“: **Voxel** (Einstellungen → Effekte → Rauch-Darstellung, durchschalten). Der Rauch von Raketen, Jets, Startrampen und Rückstrahl wird aus Würfeln auf einem festen Weltraster gebaut, passend zur Minecraft-Welt.
- Rauchspuren werden zu einer zusammenhängenden, kantigen Säule statt einer Kette von Kisten: Wolken gleicher Größe teilen sich ihre Würfel, innen liegende Flächen werden weggelassen. Kleine, frische Wolken bestehen aus feinen Würfeln (ab 1/8 Block), große alte aus groben (bis 64 Blöcke), weit entfernte werden gröber.
- Jede Würfelseite ist flach schattiert: oben hell, seitlich dunkler, unten am dunkelsten, auf der Sonnenseite heller, unter darüberliegenden Würfeln im Schatten; feiner dunkler Rand pro Würfel. Frischer Abgasstrahl an der Düse glüht orange, Lichter in der Nähe färben den Rauch.
- Dünner werdender Rauch blendet nicht aus, sondern zerfällt: die Wolke schrumpft und bröckelt in immer weniger Würfel.
- Würfel sind massiv und verdecken richtig (Tiefe), Rauch hinter Wolken bleibt versteckt. Alte Einstellung „Volumetrisch an/aus“ wird übernommen.

### 13.4.0 – Blut läuft echt an Wänden herunter
- Statt dunkler Streifen über die ganze Blockseite sammelt sich Blut, das über eine Kante läuft, zu wenigen unregelmäßig verteilten Rinnsalen: oben ein nasser Rand und ein Trichter, so breit wie die Stelle, an der es überläuft (bei viel Blut erst ein Film, der sich weiter unten aufteilt), darunter dünne, leicht geschlängelte Bahnen mit ungleicher Breite, in der Mitte dunkler, am Rand dünn und durchscheinend.
- Die Rinnsale kriechen sichtbar die Wand hinunter, so schnell, wie Blut nachkommt, mit einem runden Bluttropfen an der Spitze. Wenig Blut bleibt auf halber Höhe stehen, der Tropfen hängt dann dort. Erst wenn ein Rinnsal unten ankommt, läuft das Blut in die Lache am Boden, wo es sich leicht verbreitert.
- An Überhängen bildet sich am Ende ein hängender Tropfen, der wächst und abfällt (mit Tropfgeräusch).
- Läuft über ganze Wände (bis 6 Blöcke) bis zum Boden; Blut, das in Wasser läuft, verschwindet.
- Nass und glänzend, solange Blut nachfließt, danach dunkler, matter Film, nach Minuten braun angetrocknet.

### 13.3.0 – Blut fließt wie echte Flüssigkeit
- Blut am Boden wird jetzt als Flüssigkeit simuliert: jede Blockoberfläche ist ein Raster aus 12 × 12 Zellen, das Blut läuft von hoch nach tief über die kleinen Unebenheiten des Bodens – es kriecht in Mulden, bildet Finger und Ausläufer, fließt um Erhebungen und bleibt erst als dünner Film liegen, der hier dicker, dort dünner ist (keine runden oder eckigen Lachen mehr, nicht ans Blockraster gebunden).
- Es fließt über ebenen Boden in den nächsten Block weiter, läuft Stufen hinunter (mit Blutspuren an der Stufenkante) und sammelt sich an Abbruchkanten, bis es Tropfen für Tropfen hinunterfällt (mit Tropfgeräusch).
- Unter Verletzten und Toten quillt das Blut über einige Sekunden nach: die Lache wächst langsam.
- Tropfen und Spritzer, die auf dem Boden landen, werden Teil der Flüssigkeit; schnelle Tropfen spritzen in kleine Tröpfchen auseinander.
- Aussehen: dünner Film klar rot und durchscheinend, tiefe Stellen fast schwarzrot und leicht erhaben, nasser Glanz im Licht; stehendes Blut dunkelt über Minuten nach und trocknet zu einem braunen Fleck. Erde, Sand und Gras saugen es schneller auf als Stein.
- Kostet etwas mehr Leistung als die alten Lachen.

### 13.2.1 – Echtere Gurgel- und Blutgeräusche
- Röcheln neu aus echten Aufnahmen: schweres, verletztes Atmen mit Blut im Hals, das im Rhythmus des Atems gluckert, nasses Blut-Husten und Würgen. Sterbende röcheln öfter, als dass sie stöhnen.
- Spritzende Arterien klingen nach einem nassen Spritzer mit nachprasselnden Tropfen statt nach Schleim.
- Neu: Blut, das über eine Kante tropft, hört man Tropfen für Tropfen.
- Vier zusätzliche Knochenbrüche.

### 13.2.0 – Realistischeres Blut
- Blutlachen sind keine einzelnen roten Scheiben mehr, sondern eine zusammenhängende Fläche. Jeder Block wählt passend zu seinen acht Nachbarn ein Kachelstück, die Ränder gehen nahtlos ineinander über, und große Lachen werden eine geschlossene Fläche mit unregelmäßigem Rand.
- Farbe wie echtes Blut: dick fast schwarzrot, nur der dünne Film am Rand heller und durchscheinend. Der nasse Glanz kommt vom Licht, nicht mehr von rosa Flecken in der Textur.
- Deutlich weniger Menge: Tropfen und Leichen füllen nicht mehr ganze Wiesen.
- Spritzer und Tropfen sind dunkler und weniger rosa.

### 13.1.0 – Gore: fließendes Blut, Austrittswunden, Arterienpuls, echte Klänge
- Blut verhält sich wie eine Flüssigkeit:
  - Es sammelt sich auf dem Block, auf den es fällt, und läuft weiter, wenn zu viel da ist: über ebenen Boden zu den Nachbarblöcken, Stufen hinunter (mit Rinnspur an der Kante) und über Kanten, wo es tropfenweise heruntertropft.
  - Die Tropfen bilden unten neue Lachen.
  - Erde, Gras und Sand saugen es schneller auf als Stein.
  - Frisch ist es hellrot und glänzend, über Minuten trocknet es dunkel ein.
- Austrittswunden je nach Waffe:
  - Gewehrkugeln reißen eine viel größere Austrittswunde als die Einschusswunde und werfen viel Blut auf die Wand dahinter.
  - Pistolenkugeln bleiben oft im Körper stecken und machen kleinere Austrittswunden.
  - Schrotkugeln treten nie aus.
  - Hinter dem Ziel hängt kurz ein feiner roter Blutnebel.
- Arterienblutung im Herzschlag: Jeder Schlag wirft einen Strahl, kurz danach eine schwächere zweite Welle. Mit dem Blutverlust rast das Herz (80 bis 150 Schläge pro Minute), die Strahlen werden aber schwächer und sinken tiefer.
- Echte Klänge aus Aufnahmen (Quellen in CREDITS.md):
  - Kugeleinschläge in den Körper.
  - Knochenbrüche bei Kopftreffern und abgetrennten Gliedmaßen.
  - Dumpfe Treffer auf Schutzwesten.
  - Schmerzensschreie von Spielern und Soldaten.
  - Sterbendes Stöhnen und Röcheln in der Sterbephase.
  - Spritzende Arterien.
- Schreie und Stöhnen laufen über den Lautstärkeregler „Stimme/Sprache“ und lassen sich dort leiser stellen.

### 13.0.0 – Bauwerke neu in Blender modelliert
Startrampe, Raketensilo, Rundluken-Silo, U-Boot und die Wolframstab-Bodenstation sind in Blender komplett neu und realistisch gebaut. Schmutz, Rostschlieren, abgenutzte Kanten, Ruß und Schatten in den Ecken sind in die Texturen gebacken und werden mit dem PBR-Shader der Waffen gezeichnet. Maße und alle Animationen bleiben gleich.
- Startrampe:
  - Betonfläche mit Fugen, Ölflecken und Ruß, ausgemauerter Flammengraben mit gebogenem Stahl-Ablenker.
  - Serviceturm aus rot-weißen Doppel-T-Trägern mit Gitterböden, Geländern, Treppen, Aufzugsschacht und Kran.
  - Schwenkarme als Fachwerk mit Schläuchen, gefräste Halteklammern.
  - Kugeltanks mit Leitern, Plattformen und Rohrleitungen, Wasserturm, Blitzschutzmasten mit Abspannseilen, Flutlichtmasten.
- Silo: Betonfläche mit schweren Schiebetoren auf Schienen, Lüftungsgehäuse, Schaltschrank, Personenluke, Antenne, Zaun mit Stacheldraht und Tor. Rundes Abschussrohr im Schacht.
- Rundluken-Silo: Panzerluke mit Verriegelungsbolzen am massiven Ring, Hydraulikzylinder und Hydraulikaggregat.
- U-Boot: glatter Turm mit Tiefenrudern und Masten, Raketendeck mit zwei Reihen bündiger Luken, Gummibeschichtung, Heckruder und Propeller.
- Bodenstation: Cassegrain-Schüssel mit Panelnähten, Fangspiegel und Rippen, Drehkopf mit Plattform, Container mit Wellblech und Klimageräten, rot-weißer Flugwarnmast, Generator mit Tank, Maschendrahtzaun.

### 12.45.0 – Nasse Kleidung
- Regen macht dich in etwa einer halben Minute nass (im Gewitter schneller, mit Lederjacke langsamer), Wasser sofort.
- Nass fühlt sich die Luft bis zu 5 °C kälter an, im Freien im Wind bis zu 8 °C. Dein Körper kühlt doppelt so schnell aus. Bei Hitze kühlt Nässe angenehm.
- Kleidung trocknet von selbst: langsam in der Kälte, schneller in Wärme und Sonne, am schnellsten an Lagerfeuer, Ofen oder Lava. Dort dampft die Kleidung sichtbar.
- Nasse Spieler tropfen, das sehen auch andere.
- Im Temperatur-Abzeichen steht Wasser so hoch, wie nass du bist, mit wogender Oberfläche. Unten sammeln sich Tropfen, schwellen an und fallen.

### 12.44.0 – Rundes Temperatur-Abzeichen
- Die Temperatur ist jetzt ein rundes Abzeichen rechts neben der Hotbar, nur mit der Gradzahl. Der Rand hat die Farbe der Luft, ein Lichtpunkt läuft um den Rand, das Glas hat einen Glanz.
- Hitze: Eine Feuerkrone lodert rund um das Abzeichen (Flammen weiß-gelb, orange, rot, verglühend zu Rauch). Funken fliegen mit Leuchtspur davon, das Innere glüht und pulsiert, die Ziffern flimmern in der heißen Luft.
- Kälte: Eine Krone aus Eisstacheln mit Verästelungen wächst aus dem Rand, Eisblumen kriechen über das Glas. Funkelnde Sterne blitzen am gefrorenen Rand auf, sechsarmige Schneeflocken drehen sich beim Herabschweben.

### 12.43.0 – Lebendiges Thermometer
- Das Thermometer ist größer und schöner: Glasröhre mit Skala und Kugel, Gradzahl, darunter ein Farbverlauf für den Körper (blau – grau – orange) mit Markierung.
- Hitze: Unter dem Thermometer lodern Flammen, die über den Rand züngeln. Funken fliegen, alles glüht orange, die Quecksilbersäule pulsiert. Je heißer, desto stärker.
- Kälte: Eisblumen wachsen von den Ecken und Rändern herein, das Glas beschlägt, ein Lichtreflex wandert über den gefrorenen Rand, Schneeflocken rieseln und ein ❄ erscheint. Je kälter, desto weiter wächst das Eis.
- Die Effekte blenden sanft ein und aus.

### 12.42.1 – Thermometer immer sichtbar
- Das Thermometer rechts neben der Hotbar ist jetzt immer da, nicht nur bei großer Hitze oder Kälte.
- Neues Aussehen: ein kleines Glasthermometer, das je nach Temperatur steigt und die Farbe wechselt (blau bei Frost bis rot bei Hitze), die Gradzahl daneben.
- Darunter ein Balken für den Körper: links unterkühlt, Mitte gut, rechts überhitzt. Wird es gefährlich, blinkt die Markierung.

### 12.42.0 – Realistischer Regen
- Statt der blauen Regenwände fallen einzelne Tropfen: dünne, blasse, halbdurchsichtige Streifen, durch die Fallbewegung verwischt, unterschiedlich groß und schnell. In der Ferne verblassen sie ins Grau.
- Der Wind treibt den Regen schräg, im Gewitter stärker. Bei Starkregen und Gewitter fallen mehr Tropfen.
- Die blauen „V“-Spritzer sind weg. Am Boden springen kleine Wasserperlen hoch, auf Wasser und Pfützen breiten sich Ringe aus, bei Wolkenbruch hängt feine Gischt über dem Boden.
- Abschaltbar in den Einstellungen unter „Welt & Natur“ → „Realistischer Regen“.

### 12.41.0 – Echter Donner
- Der künstlich erzeugte Donner ist durch echte Gewitter-Aufnahmen ersetzt.
- In der Nähe: 5 echte Einschläge mit Krachen, Knall und nachrollendem Grollen.
- In der Ferne: 6 echte rollende Donner, gefiltert wie über große Entfernung.
- Quellen in CREDITS.md (InspectorJ, Gregor Quendel, wuxiascrub).

### 12.40.0 – Einstellungsmenü im Profi-Look
- Einstellungen stehen jetzt untereinander in Karten, eine Karte pro Abschnitt, mit feinen Trennlinien.
- Jede Option zeigt unter ihrem Namen, was sie macht. Die Tooltip-Texte stehen direkt in der Zeile.
- Die ganze Zeile ist klickbar. Rechts sitzen Schalter, Auswahlfelder mit Pfeilen oder Regler mit Wertanzeige.
- Seitenleiste in zwei Gruppen („Dein Spiel“, „Welt & Werkzeuge“), Symbole auf farbigen Kacheln, Version und Alpha-Abzeichen unten.
- Kopfbereich mit Seitensymbol, großem Titel und Beschreibung. Hinweise mit „i“-Symbol, Suchfeld im selben Stil.
- „Fertig“-Knopf in der Farbe der Seite. Die Liste blendet oben und unten weich aus. Das Fenster ist zentriert und in der Größe begrenzt.

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
