# Demo recording tools

Reusable pipeline for the README / Play-store demo GIFs. It records an on-device
screen capture and converts it to a README-ready GIF plus the source MP4, matching
the look of `docs/assets/demo.gif`.

## Requirements

- `adb`, with the device connected, unlocked, and in portrait
- `ffmpeg` / `ffprobe` on the host
- `python3` (element lookup for the scripted drivers)

## Capture by hand

Record for a fixed window while you tap through the demo yourself:

```bash
tools/record-demo.sh myclip --duration 15 --width 340
```

Output: `docs/assets/myclip.gif` and `docs/assets/myclip.mp4`.

## Scripted, reproducible demos

```bash
tools/record-demo.sh copy        --prep tools/demos/_reset-shallow.sh --drive tools/demos/copy.sh        --width 340 --duration 40
tools/record-demo.sh root        --prep tools/demos/_reset.sh         --drive tools/demos/root.sh        --width 340
tools/record-demo.sh app-manager --prep tools/demos/_reset.sh         --drive tools/demos/app-manager.sh --width 340
```

The README hero is these three stitched into one tour. Rebuild it with:

```bash
tools/combine-demo.sh demo --speed 2.0 --width 320 \
  docs/assets/copy.mp4 docs/assets/app-manager.mp4 docs/assets/root.mp4
```

`--speed >1` tightens the pauses the drivers leave while dumping the UI. Reset
choice matters: file ops use the fast `_reset-shallow.sh`; the tree demos (Root,
App manager) need the thorough `_reset.sh` to clear remembered deep expansions.

- `--prep` runs **off-camera** before recording. `_reset.sh` normalizes the app to a
  clean storage-roots home (see below).
- `--drive` runs the tap sequence **while** recording. Drivers find rows by their
  on-screen text and chevron state via `uiautomator` — not fixed pixels — so they
  survive layout shifts, tree expansion, and different screen sizes.

## record-demo.sh options

`--serial S` · `--duration SEC` (hard cap) · `--width PX` · `--fps N` ·
`--crop-top/--crop-bottom PX` · `--bit-rate R` · `--cold` (force-stop + relaunch first) ·
`--touches` / `--no-touches` (touch indicator overlay, on by default) ·
`--prep SCRIPT` · `--drive SCRIPT` · `--out-dir DIR` (default `docs/assets`).

Portrait demos want a smaller `--width` (the phone is 1440px wide); `800` suits landscape.

## Why the reset is thorough (and takes ~1 min)

XFiles deliberately *remembers* sub-expansions under a collapsed parent, so a
clean-looking home can still spring a deep folder open on the next tap — and a
remembered-but-unloaded node renders as an open folder with no rows. `reset_home`
therefore exposes the demo paths (Root → data, App manager → Installed → first app)
and collapses every open chevron **deepest-first**, leaving a genuinely empty
expansion set. That guarantees each scripted expand plays its reveal animation
instead of toggling or re-opening a remembered node.

## Files

| File | Role |
|---|---|
| `record-demo.sh` | record → GIF pipeline (screenrecord + ffmpeg palette) |
| `combine-demo.sh` | concat clips → one sped-up tour GIF (the README hero) |
| `demos/lib.sh` | tap/scroll/select/reset helpers over adb + uiautomator |
| `demos/tree_state.py` | parse a uiautomator dump: chevron state, indentation depth |
| `demos/_reset.sh` | prep: normalize to a clean home, empty expansion set (thorough) |
| `demos/_reset-shallow.sh` | prep: fast collapse-and-scroll-top (for non-tree demos) |
| `demos/copy.sh` | driver: file copy with explicit-destination picker |
| `demos/root.sh` | driver: Root access demo |
| `demos/app-manager.sh` | driver: App manager demo |

## Add a demo

Copy `demos/root.sh`, build the flow from `tap_text "Label"` / `hold` / `swipe_up`
(see `demos/lib.sh`), then record with `--drive tools/demos/yourdemo.sh`.
