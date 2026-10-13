# Wordfeed

A small Android reader for word-of-the-day sources. Swipe (or tap the tabs)
to move between sources; a dot on a tab means it has something you haven't
seen. The gear icon is for subscribing/unsubscribing, adding any RSS/Atom
feed, and choosing light or dark.

## How it works

Each source is shown in a WebView running `app/src/main/assets/reader.html`.
All the per-site work happens in JavaScript:

- `sources.json` lists the built-in sources.
- `extractors.js` has one extractor per site, which fetches pages or feeds
  (through the Kotlin bridge, so there are no CORS problems) and turns them
  into entries.
- `reader.js` renders entries; `reader.css` styles them.

The Kotlin side (`app/src/main/java/...`) is just the shell: tabs, settings,
and an HTTP fetcher.

## Seeing what it looks like without a phone

Because the reader is a web page, `tools/snap.mjs` can run it in headless
Chromium and screenshot every source at phone size, light and dark:

    cd tools && npm install && cd ..
    node tools/snap.mjs --live --record /tmp/fixtures --out /tmp/shots
    node tools/snap.mjs --replay /tmp/fixtures --out /tmp/shots   # offline

The `snapshots` workflow does the `--live` run on GitHub Actions after each
change (and daily) and pushes the screenshots and recorded responses to the
`snapshots` branch.

## Installing

Every push to `main` builds an APK on GitHub Actions and publishes it as the
newest release (tagged `v0.1.N`):

    https://github.com/jorendorff/wordfeed/releases/latest/download/wordfeed.apk

Or point Obtainium at `https://github.com/jorendorff/wordfeed`.

Builds are signed with the checked-in `debug.keystore`, so new builds install
over old ones and keep your settings.

## Building locally

Open the folder in Android Studio, or with Gradle 8.9+ and an Android SDK:

    gradle assembleDebug
