# Launcher icon: cannabis fan leaf

`agy/v1.svg` is the source of the shipped launcher icon (ADR-058 in `docs/DECISIONS.md`); `app/src/main/res/drawable/ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` are generated from its paths.

`agy/v2.svg` and `agy/v3.svg` are the rejected serrated variants; `../icon-fan-comparison.png` compares nine variants from codex, agy and Claude.

To change the icon, edit the SVG and regenerate both drawables; do not edit the paths by hand.
