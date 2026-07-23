# Y2Player – harte Projektregeln

- Zielgerät: Innioasis Y2, MT6582V, Android 4.4.2 / API 19, armeabi-v7a.
- Kein Jetpack Compose, kein AndroidX-Zwang, keine unnötigen Abhängigkeiten.
- CPU, Heap und Garbage Collection sind kritisch: keine kompletten Bibliotheken im RAM,
  keine teuren Sortierungen im UI-/Wheel-Pfad, begrenzte Caches.
- Ziel: schlanker Musikplayer + HOME-Launcher; keine Video-, Bild-, E-Book- oder
  sonstigen Stock-App-Features hinzufügen.
- Bestehende Architektur: MediaPlayer, AudioManager, SQLite.
- Alle Änderungen müssen API-19-kompatibel bleiben.
- Vor Codeänderungen: relevante Dateien und bestehende Tests untersuchen.
- Nach Codeänderungen: passende Tests ausführen und Ergebnis nennen.
- Keine Flash- oder Firmware-Dateien ändern, außer wenn die Aufgabe dies ausdrücklich verlangt.
- Nie android.uid.system hinzufügen.
- OEM-Plattformsignatur und OEM-System-Apps nicht ersetzen oder neu signieren.
- Vor dem ersten Flash-Test nur die system-Partition testen; niemals Preloader/NVRAM ändern.
- Für DAC-Aussagen keine Bit-perfect-/DSD-Versprechen machen: App-Ebene ist durch
  AudioFlinger/HAL begrenzt.