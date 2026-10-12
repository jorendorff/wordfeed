# Wordfeed

A small Android reader for word-of-the-day sources. Swipe (or tap the tabs)
to move between sources; a dot on a tab means it has something you haven't
seen. The gear icon lets you subscribe/unsubscribe and add any RSS/Atom feed.

Sources with an RSS or Atom feed are shown as cards. Sources without one
(Wordnik, Dictionary.com, and OED if its feed fails) are shown as the web page
itself.

## Installing

Every push to `main` builds an APK on GitHub Actions and publishes it as the
`latest` release:

    https://github.com/jorendorff/wordfeed/releases/latest/download/wordfeed.apk

Open that on the phone (signed in to GitHub, since the repo is private), then
install it. Android will ask you to allow installs from your browser once.

Builds are signed with the checked-in `debug.keystore`, so new builds install
over old ones and keep your settings.

## Building locally

Open the folder in Android Studio, or with Gradle 8.9+ and an Android SDK:

    gradle assembleDebug
