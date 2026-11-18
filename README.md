# Fake Location

Set your phone's location to any place in the world. WhatsApp live location, Google Maps and other apps
will see the place you choose.

## Features
- Pick a place by tapping the map, searching any place name, or pasting `lat, lng` (e.g. from Google Maps)
- Saved favourite places
- Runs in the background with a notification and a Stop button
- "Natural movement" adds a tiny 1–2 m GPS wobble so a live location looks real
- Mocks GPS, network and Google fused location (the one WhatsApp uses)

## One-time setup on the phone
1. Install the APK and allow location permission.
2. Settings → About phone → tap **Build number** 7 times to turn on Developer options.
3. Settings → Developer options → **Select mock location app** → **Fake Location**.
4. Open the app, choose a place, tap **START FAKE LOCATION**. Then share live location in WhatsApp.

Tip: if WhatsApp still shows your real place, turn Location off and on once, or set Location mode to
"Device only"/GPS only, then reopen WhatsApp.

## Build
GitHub Actions ("Build APK") builds on every push to this branch and publishes the APK as a release
(`fakelocation-vN`) and commits it to `apk/FakeLocation.apk`.
