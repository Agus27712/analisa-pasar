# Polishing Baseline

Baseline validated on `backup` after a final debug build/runtime check.

## Scope
- Chart polish
- Price micro-animation polish
- Progress Entry animation polish
- Important Level visual polish
- Final visual/runtime audit
- Final Debug build

## Guardrails
- Do not alter scalping thresholds, scoring, MTF logic, TP/SL, or entry logic during UI polish.
- Preserve real Indodax data and live refresh behavior.
- Avoid flicker and animation restarts caused by recomposition.
- Keep portrait layout scan-friendly.
- `backup` is the development baseline; `main` remains untouched.
