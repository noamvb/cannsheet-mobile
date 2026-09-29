# Cannsheet Mobile design system: A · Ledger Green

Chosen direction A, Ledger Green. The reference is `docs/design/mockups/direction-a.html`; product constraints are in `docs/design/PRODUCT.md`. Directions B and C were not chosen.

A quiet instrument: paper white, deep pine ink and one measured green, with IBM Plex Sans labels and IBM Plex Mono figures. The fixed palette follows system light or dark appearance; dynamic colour is disabled.

## Colour

Every listed value is copied from the mockup's `--md-sys-color-*` CSS properties. Hex values are shown exactly as authored.

### Core roles

| Role | Light | Dark |
|---|---|---|
| primary | `#145F58` | `#83D0C2` |
| onPrimary | `#FFFFFF` | `#12332E` |
| primaryContainer | `#B9E3DB` | `#234A43` |
| secondary | `#465B55` | `#B8C9C2` |
| surface | `#FFFFFF` | `#18221F` |
| surfaceContainer | `#E9EEEA` | `#19221F` |
| onSurface | `#172522` | `#E5EFEB` |
| outline | `#65736E` | `#899992` |
| error | `#B3261E` | `#FFB4AB` |

### Remaining Material 3 roles

| Role | Light | Dark |
|---|---|---|
| onPrimaryContainer | `#172522` | `#D6F3EC` |
| onSecondary | `#FFFFFF` | `#263832` |
| secondaryContainer | `#DCE8E2` | `#394A44` |
| onSecondaryContainer | `#172522` | `#D5E5DE` |
| tertiary | `#53636B` | `#BCCBD0` |
| onTertiary | `#FFFFFF` | `#29383D` |
| tertiaryContainer | `#DCE8EC` | `#3F5055` |
| onTertiaryContainer | `#172522` | `#D8E7EB` |
| onError | `#FFFFFF` | `#690005` |
| errorContainer | `#F9DEDC` | `#93000A` |
| onErrorContainer | `#410E0B` | `#FFDAD6` |
| background | `#F4F6F2` | `#101816` |
| onBackground | `#172522` | `#E5EFEB` |
| surfaceTint | `#145F58` | `#83D0C2` |
| surfaceVariant | `#CFD8D3` | `#34443E` |
| onSurfaceVariant | `#53625E` | `#B5C4BE` |
| surfaceDim | `#D8DEDA` | `#101816` |
| surfaceBright | `#FFFFFF` | `#303A36` |
| surfaceContainerLowest | `#FFFFFF` | `#0B110F` |
| surfaceContainerLow | `#F0F3EF` | `#151E1B` |
| surfaceContainerHigh | `#E3E9E5` | `#232D29` |
| surfaceContainerHighest | `#DDE4DF` | `#2E3834` |
| outlineVariant | `#CFD8D3` | `#34443E` |
| inverseSurface | `#29332F` | `#E1EAE5` |
| inverseOnSurface | `#EFF4F1` | `#27312D` |
| inversePrimary | `#9BD2C7` | `#356D64` |
| scrim | `#000000` | `#000000` |

The mockup also defines `--md-sys-color-shadow:#000000` in both schemes; it is a CSS utility token, not a Material 3 `ColorScheme` property, so it is not settable in `LedgerLightColors` or `LedgerDarkColors`.

### Contrast

Computed with the WCAG relative-luminance formula. The mockup's lowest text pair in its contrast check is `#4A5752` on `#CFD8D3` = 5.19:1 in light mode and `#B5C4BE` on `#34443E` = 5.68:1 in dark mode. Both exceed 4.5:1.

## Type

IBM Plex Sans variable (bundled, weights 400–700) carries all Material 3 text styles. IBM Plex Mono regular and medium are for data figures (13–32sp) and use tabular numerals (`fontFeatureSettings = "tnum"` via `TextStyle.tabular()`). The mockup specifies the named scale entries below; missing intermediate M3 roles are filled from the M3 size progression and use the nearest specified Sans weight.

| M3 role | Face | Weight | Size / line height (sp) |
|---|---|---:|---:|
| displayLarge | IBM Plex Sans | 400 | 57 / 64 |
| displayMedium | IBM Plex Sans | 400 | 45 / 52 |
| displaySmall | IBM Plex Sans | 500 | 36 / 44 |
| headlineLarge | IBM Plex Sans | 600 | 32 / 40 |
| headlineMedium | IBM Plex Sans | 600 | 28 / 36 |
| headlineSmall | IBM Plex Sans | 600 | 24 / 32 |
| titleLarge | IBM Plex Sans | 600 | 22 / 28 |
| titleMedium | IBM Plex Sans | 600 | 16 / 24 |
| titleSmall | IBM Plex Sans | 600 | 14 / 20 |
| bodyLarge | IBM Plex Sans | 400 | 16 / 24 |
| bodyMedium | IBM Plex Sans | 400 | 14 / 20 |
| bodySmall | IBM Plex Sans | 400 | 12 / 16 |
| labelLarge | IBM Plex Sans | 600 | 14 / 20 |
| labelMedium | IBM Plex Sans | 600 | 12 / 16 |
| labelSmall | IBM Plex Sans | 600 | 11 / 16 |

## Shape

| M3 shape | Radius |
|---|---|
| extraSmall | 2dp |
| small | 8dp |
| medium | 14dp |
| large | 24dp |
| extraLarge | 28dp |
| full | pill |

These radii follow the mockup's sample control values. Rows stay open with restrained rules; the chip controls use pill corners.

## Surfaces and elevation

- Light page `background` is `#F4F6F2`; paper `surface` is `#FFFFFF`. Dark page is `#101816`; dark paper is `#18221F`.
- Use tonal fills and restrained rules. Avoid card shadows; express grouping through the `surfaceContainer*` ladder.
- The navigation area rests on `surfaceContainer`; its selected state uses the primary accent.

## Components and structure

- Keep five destinations visible: Log, Purchase, Insights, Assistant and Settings. Use a bottom navigation bar on the phone frames.
- Log frame: date/title/sync header; confirmed and local ledger columns; selectable products; quantity chips; date/time row; one full-width Log consumption action and undo hint.
- Purchase frame: date/title header; product type and name, cost, THC, grams and tax basis fields; one full-width save action.
- Insights frame: date/title header; range selector; descriptive summary and metrics; directly labelled chart.
- Assistant frame: date/title header; conversation bubbles, suggested prompts and a message composer.
- Settings keeps its title/header and presents configuration rows with controls appropriate to each value.
- Controls have at least 48dp touch targets. Log consumption remains at a two-tap ceiling; a new purchase remains at a seven-tap ceiling.

## Data and status

Figures use Plex Mono with tabular numerals. Confirmed server totals and local pending totals remain distinct in the Log ledger. Status and errors use the established Material 3 semantic roles; colour never replaces a label.
