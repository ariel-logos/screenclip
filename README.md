# ScreenClip

Freeze the screen, crop any rectangle — adjusting it until it's right — then copy it,
save it to the gallery, or both.

Minimal, no third-party libraries beyond `androidx.core` and `androidx.activity`.
minSdk 30, targetSdk 35, Kotlin 2.0, AGP 8.7.

## Build

Builds clean with AGP 8.7.3 / Gradle 8.9 / JDK 17. The toolchain used here lives
outside the project:

| Piece | Path |
| --- | --- |
| JDK 17 (Temurin) | `D:\AndroidBuild\jdk\jdk-17.0.20+8` |
| Android SDK (platform 35, build-tools 35.0.0) | `D:\AndroidBuild\sdk` |
| Gradle 8.9 | `D:\AndroidBuild\gradle-8.9` (the wrapper now fetches its own) |

`local.properties` points at the SDK and is git-ignored. Gradle needs JDK 17 on
`JAVA_HOME` — the system default here is JDK 23, which AGP 8.7 rejects:

```bash
JAVA_HOME='D:\AndroidBuild\jdk\jdk-17.0.20+8' ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (~5.4 MB, debug-signed, unminified).

Install to a connected device:

```bash
D:\AndroidBuild\sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

## Two capture paths

| | Trigger | Consent | Foreground service |
| --- | --- | --- | --- |
| **Instant** | accessibility service | none, ever | none |
| **Fallback** | MediaProjection | one tap, every capture | yes, with a notification |

The instant path is used automatically whenever the accessibility service is enabled;
otherwise it falls back to MediaProjection, which is the path proven earlier and is
left byte-for-byte intact.

Instant capture is **optional** — the app is fully functional without it, it just asks
for recording permission on every capture. Setup says so in those words, because an
accessibility service is a heavyweight thing to ask for and the user should know they
can decline it and still have a working app.

Discoverability of that choice is deliberate: the setup screen shows until it has been
seen once (not merely until the overlay permission is granted, or a user who grants
that would never learn the option exists), and it stays reachable afterwards by
long-pressing the app icon or the Quick Settings tile. The tile subtitle reads
"Instant" or "Asks permission" so the current state is visible where it is used.

Everything setup turns on, it can also turn off — but by two different mechanisms,
and only one of them is symmetric:

- **Instant capture** is a real toggle. `AccessibilityService.disableSelf()` lets the
  service switch itself off in one tap. Turning it *on* still requires a trip to
  Settings, because no app can grant itself accessibility; that asymmetry is Android's,
  not a shortcut taken here.
- **The tile** has an add API (`requestAddTileService`) and no remove API. Removing
  works by disabling the `TileService` component, which makes the system drop the tile
  from Quick Settings and from the picker. And since nothing can query whether a tile
  is currently *added*, add and remove are two buttons rather than one switch — a
  switch would be asserting state the app cannot see. The screen says so in as many
  words.

**Install so the accessibility toggle is not blocked:**

```bash
D:\AndroidBuild\sdk\platform-tools\adb.exe install -r -i com.android.shell app\build\outputs\apk\debug\app-debug.apk
```

Android guards accessibility for apps installed by an untrusted installer — the toggle
appears but is greyed out with *"Controlled by restricted setting"*. Installing as
`com.android.shell` avoids it. If you do hit it: tap the greyed-out switch once, then
Settings › Apps › ScreenClip › ⋮ › **Allow restricted settings**. That menu item does
not exist until the switch has been tapped once, which is the part everyone misses.

## Using it

1. Grant "draw over other apps" (the button in the app opens the settings page).
2. Trigger a capture — **Quick Settings tile** (captures the app you are in), or the
   launcher icon (which can only ever capture what is behind the launcher).
3. The screen freezes. Drag a rectangle.
4. Adjust it: drag a corner or edge to resize, drag inside to move, drag outside to
   start over. Or skip the drag entirely and hit **select the whole screen**.
5. Pick an action from the icon bar: **whole screen** ⋮ **copy** · **save** ·
   **copy + save** · **cancel**.
   The bar is icon-only; long-press any button for its label, and each carries a
   content description for TalkBack. There is no conventional glyph for "copy and
   save", so that one is composed from the other two — the copy sheets with the
   download arrow badged into the corner.

   The bar is visible from the moment the screen freezes, with the three output
   buttons dimmed until a selection exists. It has to be: "select the whole screen"
   is most useful *before* any drag, so it cannot live behind a first selection.
   A divider separates changing the selection from acting on it.

   The whole-screen button is a pair of corner-bracket icons — pushed out to the edges
   for "take it to the whole screen", pulled back in toward the centre for "return to
   the selection I had". Deliberately the same bracket shape that is drawn on the
   selection itself, so the button reads as doing something to that rectangle.

   Going back always works, because *having had no selection* counts as a previous
   state: press whole-screen straight after the freeze and pressing again returns you
   to "Drag to select". The tooltip changes accordingly — "back to the previous
   selection" when there is a rectangle to restore, "clear the selection" when there
   is not — rather than promising a restore it cannot perform.

   The bar is fixed to the bottom. An earlier version hopped it to the top when the
   selection reached the bottom edge; it never needed to, because the crop comes from
   the frozen bitmap and the bar is never in the output. It was only avoiding
   *visually* covering the selection, and a bar that moves under you is worse than one
   that overlaps. The cost is that a selection's bottom handles can sit beneath the
   bar; drag the top or side handles instead.

## Architecture

The capture happens **before any of our UI exists**. That one decision is what makes
the adjust phase possible and removes an entire class of bug:

1. Service gets the projection and mirrors one full-screen frame.
2. The projection is stopped immediately — the pixels are already in hand.
3. The overlay draws that frozen bitmap as its background. The screen appears to stop.
4. All cropping is against the in-memory bitmap.

So the selection rectangle, the handles and the action bar can never contaminate the
result, no matter where the user puts them. Nothing is hidden before capture, because
at capture time none of it exists.

| File | Role |
| --- | --- |
| [MainActivity.kt](app/src/main/java/dev/screenclip/MainActivity.kt) | Permissions, consent, starts the service, then `moveTaskToBack` |
| [CaptureService.kt](app/src/main/java/dev/screenclip/CaptureService.kt) | Projection, freeze, crop, clipboard, teardown |
| [OverlayRoot.kt](app/src/main/java/dev/screenclip/OverlayRoot.kt) | Frozen frame, selection, handles, action bar |
| [Gallery.kt](app/src/main/java/dev/screenclip/Gallery.kt) | MediaStore publish |
| [CaptureSignals.kt](app/src/main/java/dev/screenclip/CaptureSignals.kt) | "our UI is off screen now" handshake |

### The five things that are not obvious

**Capturing "immediately" captures the wrong thing.** When `getMediaProjection()`
returns, the consent dialog is still animating out and `MainActivity` is still on
screen. Grab the first frame and the frozen wallpaper is a picture of this app. The
service therefore waits for `MainActivity.onStop()` — a real signal, not a guessed
delay — *and* for 120 ms with no new frame, capped at 2.5 s after which it commits
the newest frame it has rather than failing while holding valid pixels.

**Stopping the projection re-enters your own callback.** AOSP's `dispatchStop()` fans
out to every registered callback including the caller's, so `projection.stop()` posts
`onStop()` back to you. With the obvious `onStop { finish() }` that deletes the adjust
UI milliseconds after the screen freezes. `unregisterCallback()` **before** `stop()`,
plus a `haveFrame` guard so a system-initiated stop after capture is also harmless.

**Only the focused app may write the clipboard** (Android 10+), and the write fails
*silently*. The overlay window is focusable and stays attached until after
`setPrimaryClip`; on action it goes to `alpha = 0f` rather than `INVISIBLE` or
`removeView`, because both of those drop window focus. The write is read back and the
result logged, so a refusal shows up in logcat instead of nowhere.

**`ViewGroup` skips `onDraw`.** Without `setWillNotDraw(false)` on the root the whole
overlay is blank — the single most likely way this design ships broken.

**Resize is a pure function** of the rect as it was at `ACTION_DOWN` plus the total
delta, clamped on overshoot, never flipped. Mutating the live rect incrementally is
what produces grab-offset jump, creep and min-size jam.

## Verified on device

REDMAGIC 11S Pro (NX809J), **Android 16 / API 36**, 1216×2688:

- Frozen frame is the launcher — not this app, not the consent dialog. (The frozen
  clock reads 12:51 while the live status bar reads 12:52: the background really is
  the still image, not the live screen showing through.)
- Drag 886×524 → resize by the bottom-right handle → 930×831, top-left anchored →
  move by dragging inside, size preserved.
- **Copy + save**: `clipboard write confirmed` in logcat, and
  `Pictures/ScreenClip/ScreenClip_20260812-125501.png`, 921×829 — the *adjusted*
  crop, clean of scrim, border and buttons.
- **Copy** only: clipboard written, no gallery row. **Save** only: gallery row, no
  clipboard write. **Cancel**: neither, service stopped. Each does only what it says.
- On API 34+ the consent prompt is pinned to the whole display with
  `MediaProjectionConfig.createConfigForDefaultDisplay()`, so "share one app" is never
  offered — a partial mirror would produce a letterboxed frame whose coordinates no
  longer match the screen.

## Verified on device — instant path

Same REDMAGIC 11S Pro, Android 16 / API 36:

- Launcher icon with the service enabled: straight to the selection UI, **no consent
  prompt**, no notification. `dumpsys activity services` shows only the bound
  `ShotService` — no foreground service anywhere.
- Tile tapped from an **open** Quick Settings panel: frozen frame is the screen behind,
  sharp, with no shade and no blur in it.
- Full pipeline through the accessibility path: `clipboard write confirmed`, and a new
  row in `Pictures/ScreenClip`.
- The service appears in Settings › Accessibility as "ScreenClip instant capture",
  enabled, **not** greyed out — while another sideloaded app in the same list shows
  "Controlled by restricted setting", which is what the wrong installer identity gets you.

### The bug only a device could find

The first tile capture came back *blurred*. The shade was gone — but this ROM blurs the
wallpaper behind Quick Settings, and the blur is still animating out after the shade
window has already shrunk. Logging showed the settle predicate going clear at **47 ms**,
so the capture landed mid-animation.

The predicate alone is therefore necessary but not sufficient, and no window signal
describes "the compositor has finished". `SHADE_FLOOR_MS = 450` is the floor a shade
capture may not commit before — measured against how long the blur actually takes to
clear on this device (~150 ms), with margin. It is the one honest constant in the file,
and it is documented as measured rather than guessed.

## Known limits

- **A `FLAG_SECURE` app underneath kills the projection on this ROM** — the system
  toasts "App content hidden from screen share for security" and stops it. The
  exposure is now roughly one frame wide instead of the whole session, and a frame
  that comes back fully transparent is rejected with a message. Pure black is
  deliberately *not* treated as blank; plenty of real screens are black.
- **Rotating while adjusting cancels the session.** Unrecoverable by then: the
  projection is stopped and, on API 34+, a consent token allows one virtual display.
- A five-minute watchdog closes an abandoned session. (It was two minutes, which a
  slow test tripped over — a session that vanishes under the user is worse than one
  that lingers.)
- Not every app reads images from the clipboard; Gboard, Chrome, Discord and
  WhatsApp do, plenty of others only look at `text/plain`.
- Back is handled via `OnBackInvokedCallback` on 33+ with a `dispatchKeyEvent`
  fallback, but the visible **Cancel** button is the exit that cannot be taken away.
