# Cannsheet Mobile: product context

## Platform and mode

- Native Android, Kotlin, Jetpack Compose and Material 3. The redesign keeps Material 3 structure while replacing the template purple identity with fixed light and dark colour roles.
- Mode: Operate and review. Most visits are brief ledger entries; Insights supports slower review. The app is local-first: Room stores records and queued actions synchronize to the owner’s spreadsheet when possible.

## Who uses it

- One technically comfortable person on their own Android phone, maintaining a private record of purchases and consumption.
- They want reliable personal history, product inventory context and descriptive trends. The app is a ledger and does not advise on use.

## When and where

- Consumption entries are made in short, repeated sessions, often one-handed. Purchase entries are less frequent and may involve scanning a product barcode.
- Insights is reviewed later, when there is time to compare totals and patterns. Settings is used to manage sync, data and device integrations.
- The UI should remain discreet and neutral if seen by someone nearby.

## Jobs

1. Log a consumption against a selected product, usually accepting the suggested quantity and current date/time. Current baseline: 2 taps (choose a product, then Log Consumption); quantity defaults to one or a remembered valid quantity. This is the redesign ceiling.
2. Record a new purchase without saved defaults: 7 taps to focus/select Type (open + choose), focus Product Name, Cost, THC and Grams, accept the default pre-tax basis, then save. Date is prefilled; text entry itself and optional barcode scanning are not extra taps in this count. Existing catalog/default autofill can reduce the work. This is the redesign ceiling.
3. Review descriptive Insights across date presets, product usage, inventory and spend; distinguish confirmed server data from local pending actions.
4. Keep local capture reliable while offline, then see sync state and resolve operational issues in Settings.
5. Ask the optional on-device Assistant for a summary of available personal facts; use barcode scanning to autofill purchase details when useful.
6. Use home-screen quick actions and widgets for pen logging, today totals, sync status and eligible cached projections.

## Feel

- Precise ledger: tabular figures, a clear grotesk and/or monospaced data face, ink on paper, one accent colour.
- Calm, legible and discreet. Prefer ruled rows, grouped form sections and direct chart labels over generic stacks of cards or decorative charts.
- Fixed light and dark palettes expressed through Material 3 roles; follow the system scheme and do not use dynamic colour.
- Full redesign scope includes every screen, launcher icon, notifications, home-screen widgets and UX copy. Every feature, stored value and Sheet column remains unchanged. Forms can be rearranged but must respect the tap ceilings above.
- Fonts must use SIL Open Font License faces bundled in the APK.

## Anti-references

- Android Studio template purple, unstructured hard-coded colour, repeated same-looking cards, bland unlabeled charts and hand-built header substitutes.
- Cannabis leaf imagery, jokes, counterculture stereotypes, promotional language or privacy-revealing copy.
- Decorative serif warmth, hospital-style clinical blue, or extra interaction steps that slow the common entry jobs.

## Constraints that do not move

- Keep all five destinations: Log (Consumption), Purchase, Insights, Assistant and Settings; keep the separate barcode scanner route.
- Preserve every existing feature, stored value, Sheet column, offline queue, sync behavior and widget action. No schema or product-scope changes.
- Respect the two-tap consumption and nine-tap new-purchase ceilings stated above.
- Fixed light/dark Material 3 palettes; system-following mode; no dynamic colour.
- SIL OFL bundled fonts only. Minimum contrast 4.5:1 for text and 3:1 for controls.
