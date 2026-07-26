# Nord palette and dimmed deck scheme

Date: 2026-07-26
Status: implemented in 0.1.3

## Problem

The deck shipped with a saturated neon palette (`#40F7FF`, `#FF2BD6`, `#D6FF39`) painted on
near-black. Peak accent contrast against the background reached ~17:1, which reads as glare on a
phone held at arm's length in a dark room. The colours were also unmanageable: 34 literals spread
across `DeckView`, `MainActivity`, `GridBackdropView`, `ScanlineView`, the launcher drawables and
`colors.xml`, with no single source of truth.

## Decisions

1. **Two schemes, switchable at runtime.** Nord is the default; the neon look survives as a dimmed
   `DECK` scheme instead of being deleted.
2. **Canonical Nord surfaces.** Background `nord0`, panels `nord1`, borders `nord3` — the palette as
   used by Nord editor themes, not Nord accents pasted onto black.
3. **One palette object.** `ui/Palette` holds every colour role and is the only place colour
   literals live. It has no `android.graphics` dependency, so both schemes are unit-testable on a
   plain JVM.
4. **Derived fills.** Tinted surfaces (cards, chips, buttons, transcript rows) are computed from the
   panel colour blended toward the role colour at a given alpha, rather than each widget carrying
   its own ARGB constant.

## Palette

| Role | Nord | DECK (dimmed neon) | Previously |
|:--|:--|:--|:--|
| background | `#2E3440` nord0 | `#070B10` | `#030509` |
| panel | `#3B4252` nord1 | `#0B1119` | `#070C13` |
| stroke | `#4C566A` nord3 | `#1D2A33` | — |
| accent | `#88C0D0` nord8 | `#46C6CE` | `#40F7FF` |
| accentAlt | `#B48EAD` nord15 | `#C24BA6` | `#FF2BD6` |
| ok | `#A3BE8C` nord14 | `#A8C24A` | `#D6FF39` |
| warn | `#EBCB8B` nord13 | `#C98A3C` | `#FFAA36` |
| error | `#BF616A` nord11 | `#C9566E` | `#FF4F74` |
| errorText | `#D08E96` | `#DB8494` | `#FFBECA` |
| text | `#ECEFF4` nord6 | `#C3D2D8` | `#DAE9EE` |
| muted | `#8A97B0` | `#6A808A` | `#718B96` |

`error` stays a border and stripe colour: `nord11` on `nord1` is 2.5:1, below the 3:1 needed for
text, so labels and buttons use `errorText`.

## Switching

The scheme id lives in `DeckPreferences` (`color_scheme`, default `nord`). Colours are baked into
views as they are constructed, so `CORE CONTROL → COLOR SCHEME` persists the choice and calls
`Activity.recreate()` rather than walking the hierarchy. The transcript is already persisted, and a
Termux result landing during the restart is recovered from its durable per-operation record in
`onResume()`. The action is refused while a command is in flight, matching the other CORE actions.

Static resources — launcher icon, `windowBackground`, `colors.xml` — cannot follow a runtime choice
and therefore carry the Nord default. On the `DECK` scheme the window background is briefly
Nord-toned before the first frame.

## Testing

`PaletteTest` runs on the JVM and pins:

- the canonical Nord hexes, so a future tweak cannot silently drift off-palette;
- opacity of every surface role, and translucency (alpha < 0x40) of the four overlay roles;
- WCAG contrast: body text ≥ 7:1 against its panel, every label colour ≥ 3:1;
- peak accent contrast against the background is lower than the original neon for both schemes, and
  lower for Nord than for DECK — the machine-checked form of "less bright";
- derived fills keep the requested alpha and stay between panel and role per channel.
