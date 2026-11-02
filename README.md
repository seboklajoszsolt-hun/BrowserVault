# BrowserVault – Android app

A telefonon futó vevő és a keresőfelület egyetlen APK-ban. Nem kell hozzá
Termux, Node.js vagy bármilyen más telepítés — csak ez az alkalmazás,
plusz a hálózathoz a Tailscale.

## Mit tartalmaz

- HTTP vevő a 8787-es porton (előtér-szolgáltatásként, értesítéssel)
- ugyanaz az API, mint a Termux-os vevőé: POST /ingest Bearer kulccsal
- keresés, szűrés, törlés és JSONL mentés az app felületén
- az archívum az app privát tárolójában (files/data/records.jsonl)

## Így lesz APK

### A) GitHubon, eszközök nélkül

1. Készíts egy új repositoryt a GitHubon.
2. Töltsd fel ennek a ZIP-nek a tartalmát (a .github mappát is).
3. Actions fül → Android APK → Run workflow.
4. A futás végén az Artifacts alatt ott a BrowserVault-APK, abban az
   app-debug.apk. Töltsd le, és telepítsd a telefonon
   (Beállítások → ismeretlen források engedélyezése az adott fájlra).

### B) Android Studio-val

1. Android Studio → Open → a kicsomagolt mappa.
2. Build → Build App Bundle(s) / APK(s) → Build APK(s).
3. Az APK az app/build/outputs/apk/debug mappában lesz.

A debug APK közvetlenül telepíthető. A release változathoz saját aláírás
kulcs kell (Build → Generate Signed Bundle / APK).

## Első indítás

1. Nyisd meg az appot — a vevő magától elindul.
2. A kártyán látszik a cím (http://100.x.x.x:8787, ha a Tailscale fut)
   és a kulcs.
3. A bővítmény beállításaiban ugyanezt a címet és kulcsot add meg.
4. A böngészőből a cím megnyitva csak állapotot látsz: a keresés az
   appban van.

## Végpontok

- GET / — állapot (nyilvános)
- GET /api/health — állapot JSON-ban (nyilvános)
- POST /ingest — rekordok fogadása (Bearer kulcs)
- GET /api/search — keresés: q, browser, device, from, to, limit, offset
- GET /api/stats — összesítés
- GET /api/export — teljes archívum JSONL-ben
- DELETE /api/record/{id} — törlés (Bearer kulcs)

A /ingest és a /api/record kivételével minden végpont kulccsal védett.

## Fontos

- A törölt rekord azonosítója megmarad (deleted.jsonl), ezért egy régi
  mentés visszaállítása nem hozza vissza.
- Az archívum az app privát tárolójában van: az app törlésével elvész.
  Mentés: a jobb felső ikon (JSONL megosztása).
- Az app a 0.0.0.0-ra köt, tehát a helyi Wi-Fi-n is elérhető; a telefonon
  kívüli eléréshez a Tailscale-t használd.
- A kulcsot az „Új kulcs” gombbal cserélheted — utána a bővítményben is
  írd át.
