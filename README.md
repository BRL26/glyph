# Glyph

A lightweight, dependency-free replacement for the stock Nothing glyph service
on the Nothing Phone (3a) running Evolution X. Frames are written directly to
the `aw20036` LED driver through a root shell; there is no stock glyph service
in the loop.

## Features

- 36-LED phone map with per-strip assignment, brightness and behavior settings.
- Essential-notification bars with app/title/text rules.
- Sound-compiled ringtone and notification animations. A real ringing call
  loops the compiled ringtone frames for the whole ring; notifications play
  their compiled sound once. Calls outrank notifications; both temporarily
  outrank every ambient display and the underlying display resumes afterwards.
- Bottom-up volume, media-progress, notification-progress and charging bars.
- **Music visualizer**: live note map from the current audio output, persistent
  until turned off. Each detected pitch gets its own LED, with held-note decay;
  calls and notifications still take priority and the visualizer resumes after
  them.
- **Glyph lights**: full-white torch that bypasses the normal output gates.
- **All off**: one master kill switch for normal output; Glyph lights remains
  available.
- Category gates for Music, Volume, Battery and Ringtone. Gates never modify the
  saved zone assignments.
- Material 3 Expressive UI driven by the phone's wallpaper palette.
- Home controls, home-screen widget and Quick Settings tiles.

Glyph auto-starts after boot through its `BOOT_COMPLETED` receiver, falling
back to a root service start if the ROM blocks background starts. The optional
KernelSU module in `module/` starts it at boot as well.

The visualizer and service deliberately do **not** use a foreground service.
The persistent notification listener keeps the process alive, so Glyph does not
appear in Android's Active apps / "running in the background" list and has no
ongoing notification. Root keep-alive settings are applied through the root
shell.

## Requirements

- Nothing Phone (3a) (`Asteroids`) with the `aw20036` LED driver.
- Root (KernelSU or Magisk) for LED writes.
- Notification access for live alerts and to keep the background service alive.
- Microphone permission for the live visualizer.

## Controls

- **Home**: On/Off permission switch, Force all off, output gates, music
  visualizer, glyph lights and All off.
- **Phone**: strip selection, assignment, per-strip brightness and bulk setup.
- **Alerts**: notification rules, call/notification sound response, quiet hours
  and sound tests.
- **Debug & About**: root/hardware tests, stock replacement, diagnostics and
  version information.
- Quick Settings tiles mirror the gates plus Glyph lights, All off and the
  visualizer. The launcher widget exposes the same controls.

## Build

The project builds with the Android SDK tools directly; Gradle and AndroidX are
not required.

```bash
ANDROID_HOME="$HOME/Android/Sdk" ./build.sh
bash test.sh
```

`build.sh` produces `Glyph.apk`. The host tests cover frame composition,
progress geometry, effects, audio spectrum, the live note map and the
auto-generator ports.

## Install

```bash
adb install -r Glyph.apk
```

Grant Notification access in **Debug & About**, grant the microphone permission
for the visualizer, and approve root when prompted. The stock `co.aospa.glyph`
package can be disabled or removed from **Debug & About** after verifying that
Glyph owns the LEDs.
