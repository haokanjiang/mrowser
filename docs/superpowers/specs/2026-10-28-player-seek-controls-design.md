# Player seek controls — design

**Date**: 2026-10-28
**Status**: Approved, not yet implemented

## The problem

Seeking in the player takes three key presses and lands on the wrong granularity.

`PlayerActivity` overrides no key handler at all (only `onBackPressed`), so every key goes to
media3. `PlayerView.dispatchKeyEvent` (`PlayerView.java:932-955`) swallows the first D-pad press
to show the controls:

```java
boolean isDpadKey = isDpadKey(event.getKeyCode());
if (isDpadKey && useController() && !controller.isFullyVisible()) {
  maybeShowController(true);
  handled = true;          // the press does nothing else
}
```

So **right → right → OK** = forward 15s. Skipping a 2-minute intro is eight OK presses.

Three aggravations, all confirmed in media3 1.10.1 source:

- `isFullyVisible()` requires `uxState == UX_STATE_ALL_VISIBLE`
  (`PlayerControlViewLayoutManager.java:440-442`), so a second press *during* the fade-in is
  swallowed too — the key is lost.
- The controls hide after `DEFAULT_SHOW_TIMEOUT_MS = 5_000` (`PlayerControlView.java:359`), so
  any hesitation restarts the three-step sequence.
- `isDpadKey` includes `KEYCODE_DPAD_CENTER` (`PlayerView.java:1852`), so **OK does not pause**
  while the controls are hidden — it only reveals them. Pausing is also two presses.

### Granularity gap

The three seek tools available are either too fine or far too coarse:

| Tool | Step | Notes |
| --- | --- | --- |
| ⏪ / ⏩ | 5s / 15s | `C.DEFAULT_SEEK_BACK_INCREMENT_MS`, `..._FORWARD_...` (`C.java:1116,1119`) — never overridden |
| Progress bar, left/right | `duration / 20` | `DEFAULT_INCREMENT_COUNT = 20` (`DefaultTimeBar.java:192,981-985`) |
| ⏮ | back to 0:00 | No previous item, so `seekToPrevious()` falls through to `seekToCurrentItem(0)` (`BasePlayer.java:160-178`) |

`duration / 20` scales with the film: **5m09s per press on a 1h43m film**, 9 minutes on a
3-hour one. Nothing covers "10–30 seconds, one press".

⏭ is dead weight: with one media item `COMMAND_SEEK_TO_NEXT` is unavailable, so
`updateNavigation()` disables it (`PlayerControlView.java:1333`).

## Goals

1. Drop the scrim — entirely, including while paused.
2. Drop the centre row (⏮ ⏪ ⏯ ⏩ ⏭).
3. Move everything onto the progress bar:
   - short press left/right → seek, **applied immediately**, with **no controls shown**
   - hold left/right → continuous ramping seek, controls shown
   - OK → play/pause
4. Controls hide 3s after the last interaction.
5. Configurable seek amounts (5s–60s) and a long-press speed preset.

## Non-goals

- Volume (the TV's own keys own it).
- Next/previous episode (one stream per handoff).
- A custom progress bar — `DefaultTimeBar` is kept.
- A separate "peek" gesture — pause already serves as one (see below). Up/down stay unused.

## Control scheme

| Key | Focus on the progress bar | Focus elsewhere (the gear) |
| --- | --- | --- |
| Short left/right | Seek ∓ the configured amount, applied at once, **no controls** | Normal focus movement |
| Hold left/right | Ramping continuous seek, controls shown, 3s hide on release | Normal focus movement |
| OK | Play/pause | Activate the focused button |
| Up/down | Focus movement within the controls | Focus movement |
| BACK | Hide the controls if visible, else exit (unchanged) | Same |

**Pause doubles as the peek gesture.** media3 shows the controls indefinitely while paused, so
OK → read the position, reach the gear, adjust subtitles → OK to resume. That removes the need
for a dedicated reveal key, which is why up/down stay free.

Confirmed chain, requiring no code of ours:

```
OK pauses -> onPlayWhenReadyChanged -> updateControllerVisibility()
  -> maybeShowController(false) -> shouldShowControllerIndefinitely() == true   (!getPlayWhenReady())
  -> showController(true)                       // stays up

resume -> same chain, wasShowingIndefinitely == true
  -> showController(false) -> uses show_timeout // hides after 3s
```

(`PlayerView.java:1546-1557`, `1806-1812`, `1532-1544`.) So `auto_show` keeps its default and
`app:show_timeout="3000"` gives the 3s rule.

## Decisions

| Question | Choice |
| --- | --- |
| Pause key | **OK** (`KEYCODE_DPAD_CENTER`) |
| Short press | **Seeks, shows nothing** |
| Hold | **Shows the controls**, ramping to 32x |
| Paused | **Progress bar, no scrim**, stays until resume |
| Hide timeout | **3s** |
| Seek amounts | **Separate** forward and back settings |
| Long-press speed | **Three presets**, one settings row |

### Why OK is free

`DefaultTimeBar.onKeyDown` maps `KEYCODE_DPAD_CENTER` to "commit the scrub"
(`DefaultTimeBar.java:663-669`). Neither new gesture needs it: a short press applies at once, and
a hold commits on release. Intercepting OK in the Activity therefore costs nothing.

### Why a short press shows nothing, for free

Intercepting in `Activity.dispatchKeyEvent` and returning true means `PlayerView` never sees the
key, so its "first D-pad press reveals the controls" branch never runs. No suppression needed.

### Why the ramp uses media3's scrubbing mode

media3 1.10.1 ships `ExoPlayer.setScrubbingModeEnabled(boolean)` (`ExoPlayer.java:1707`) for
exactly this — "many frequent seeks". `ScrubbingModeParameters.DEFAULT`
(`ScrubbingModeParameters.java:41,62-69`) disables audio and metadata tracks, raises the codec
operating rate, and skips both the MediaCodec flush and the key-frame reset. It also suppresses
playback with `PLAYBACK_SUPPRESSION_REASON_SCRUBBING`, which is what a fast-forward should do
anyway.

`PlayerControlView` already drives it by reflection from its scrub callbacks
(`pcv.java:2006-2068`), gated on `app:time_bar_scrubbing_enabled`. We call it directly — the
project is already `@OptIn(UnstableApi::class)`.

### Why the max rate is a setting

The stated risk of a 32x cap is that HLS/DASH seek granularity is bounded by segment length
(typically 2–10s) and key-frame spacing. Making the cap configurable lets a user dial it down to
match their box instead of waiting on a guess.

Exposing the cap and the ramp interval as two raw numbers would be two rows of hard-to-predict
config, so they move together as one preset, reusing the `CursorSpeed` enum + single-choice
dialog pattern (`SettingsView.pickCursor`).

## Architecture

Pure decision logic separate from Android glue, per the project convention:

```
player/SeekGesture.kt   pure — short-press / long-press / OK decision table
player/SeekRamp.kt      pure — the ramp curve
data/SeekSpeed.kt       pure — the three presets
PlayerActivity          glue — Handler, ticker, player calls
```

### Short versus long press

`KeyEvent.repeatCount` is not used — the system repeat rate varies by vendor (~400ms to first
repeat, then ~50ms). A Handler owns the timing, as `CursorLayout` does for the BACK hold:

| Event | Action |
| --- | --- |
| `ACTION_DOWN`, `repeatCount == 0` | Record the timestamp, arm a 400ms timer |
| `ACTION_DOWN`, `repeatCount > 0` | Swallow — system repeats would fire repeated seeks |
| Timer fires | Enter long press: show the controls, scrubbing mode on, start the ticker |
| `ACTION_UP`, long press not entered | Short press: `seekTo(position ± amount)`, show nothing |
| `ACTION_UP`, long press entered | Stop the ticker, scrubbing mode off, commit, 3s hide |

Interception is conditional: left/right are taken **only when the progress bar holds focus, or
the controls are hidden** (nothing in the controller has focus). With focus on the gear they fall
through to normal focus movement, or the gear would be unreachable.

### The ramp

```kotlin
// 2x -> 4x -> 8x -> 16x -> 32x, one step per the preset's interval
fun rateAt(heldMs: Long, preset: SeekSpeed): Int
```

Each 50ms tick advances `rate × 50ms` of content time, clamped to `[0, duration]`.

| Preset | Curve | ~5s hold |
| --- | --- | --- |
| Gentle | 2→4→8x, 1.5s per step | 25s |
| Standard | 2→4→8→16x, 1.2s per step | 50s |
| Aggressive | 2→4→8→16→32x, 1.2s per step | 1m50s |

### Layout

A custom `res/layout/player_controls.xml` via `app:controller_layout_id`
(`PlayerControlView.java:546`).

Kept: `exo_progress_placeholder`, `exo_position`, `exo_duration`, `exo_settings`.

Dropped: `exo_controls_background` (the scrim), all of `exo_center_controls`, `exo_fullscreen`
(always fullscreen on TV), `exo_vr`, `exo_shuffle`, `exo_repeat_toggle`, `exo_overflow_*`,
`exo_subtitle` (already hidden — the project's own CC button lives in the sync box).

Removing buttons is safe: `PlayerControlView` resolves each by `findViewById` and null-checks it
(`pcv.java:716-751`).

Focus must move to the progress bar — `requestPlayPauseFocus()`
(`PlayerControlView.java:1666-1668`) targets a button that no longer exists, which would leave
focus nowhere and the arrow keys dead.

With no scrim the bar floats over the video, so the bar and the time labels carry their own
translucent background, like the sub-sync capsule's `sync_box_bg`.

### Settings

`Settings` gains three fields; `SettingsJson`'s `optInt`/`enumOrDefault` defaults keep an existing
`settings.json` readable:

```kotlin
val seekForwardSec: Int = 15
val seekBackSec: Int = 5
val seekSpeed: SeekSpeed = SeekSpeed.STANDARD
```

Two stepper rows (a third row type — the existing ones are toggle and picker) reusing `ic_minus` /
`ic_plus` from the sub-sync box, plus one preset picker row. Stepper rows take focus as a whole and
**adjust on left/right**, with −/+ as visual affordance and a hold to run through the range — the
same gesture as the player, and one less focus hop.

`ExoPlayer.Builder.setSeekForwardIncrementMs()` is deliberately **not** used: it binds at build
time, so a changed setting would not apply. `seekTo(position ± ms)` is computed per press instead.

Amounts step by 5s over 5–60s (12 stops).

### Settled defaults

| Item | Value |
| --- | --- |
| Long-press threshold | 400ms |
| Ticker | 50ms (20fps) |
| On-screen rate readout | None — the live picture, the moving scrubber and the running clock are the feedback |

## Testing

JVM tests for the pure modules, matching the existing `BackGestureTest` / `CursorGeometryTest`
style:

- `SeekGestureTest` — the decision table: first down arms, repeats are swallowed, up before the
  threshold is a short press, up after it commits a hold.
- `SeekRampTest` — rate per held duration per preset, the cap, and that a tick never leaves
  `[0, duration]`.
- `SettingsJsonTest` — the three new fields round-trip; a file without them reads as defaults.

Not unit-testable, to check on the device:

- Whether 32x stays smooth given real segment lengths and key-frame spacing. If it stutters,
  either lower the cap or switch high rates to "move the scrubber only, seek on release".
- That focus lands on the progress bar when the controls appear.
- That the bar stays legible over a bright scene.

## Risks

**Segment-bounded seek granularity.** The ramp's top rates may not be smooth on streams with long
segments; `allowSkippingKeyFrameReset` helps but does not remove the bound. Mitigated by making the
cap a setting.

**`setScrubbingModeEnabled` is `@UnstableApi`.** Its signature may change across media3 releases.
The project already depends broadly on UnstableApi, so this is accepted, not solved.
