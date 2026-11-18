# Fake Location

Set your phone's location to any place in the world. WhatsApp live location, Google Maps and other apps
will see the place you choose.

## Features
- Pick a place by tapping the map, searching any place name, or pasting `lat, lng` (e.g. from Google Maps)
- Saved favourite places
- Runs in the background with a notification and a Stop button
- "Natural movement" adds a tiny 1–2 m GPS wobble so a live location looks real
- Mocks GPS, network and Google fused location (the one WhatsApp uses)

## Travel A → B (moving live location)
- Open the **Travel A → B** tab, tap **A** and pick the start, tap **B** and pick the destination
  (map tap, search, or paste coordinates).
- Choose how to travel: Roads (car), Walking, Cycling (real road routes from OpenStreetMap) or Straight line.
- Set the speed with the slider, the presets (walk 5, cycle 15, scooter 35, car 60, highway 100 km/h)
  or tap the km/h value to type an exact speed. Speed can be changed while travelling.
- Tap **START TRIP**. Your location moves from A to B along the route at that speed; GPS speed and heading
  are set too. Pause/Resume and Stop any time. On arrival it stays at B.
- Share WhatsApp live location: the other person sees you moving along the route.

## Sending to someone
- **📍 Send present location** – sends where you are right now (the chosen place, or your current point
  on the trip) as a map pin to WhatsApp or any app.
- **🔴 Send live location (moving A → B)** – starts the trip and opens WhatsApp. In the chat tap
  📎 → Location → Share live location. The other person sees you moving from A to B at your speed.

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
