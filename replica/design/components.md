# Tally components — Operate / plain-steady-truthful / Lucide-open-only
Screens: S01 onboarding/permissions, S02 Today, S03 History, S04 Workout, S05 Awards, S06 Settings, S07 widget+notification.
Global a11y: real Button/Switch, 2dp accent focus ring, 48dp targets, contentDescription on icons/charts, reduce-motion disables animation/confetti.

## GoalRing [S02]
variants: track/partial/complete (100%+overflow) | sizes: 184dp home, 120dp compact | states: default, paused (hatched), focus-ring
tokens: surface card r16, accent progress, border track, text 28/20 | a11y: role=ProgressBar, label "6200 of 8000 steps"

## GiantCount [S02]
variants: live/paused/final | size: 72dp tabular-nums, label 14dp muted | states: default, updating (live-region)
tokens: text/bg, muted sub "of 8,000 goal" | a11y: heading, announce on change only, no reader spam

## StatRow [S02]
variants: 3-up (distance/calories/active min) | size: icon 20dp + 16dp value + 12dp label | states: default, focus
tokens: surface r16, text/muted | a11y: single grouped label, 48dp row height

## BarChart [S03]
variants: day(24h)/week(7)/month(30) | size: h180dp, bar 12dp r8, today=accent | states: default, selected-bar, empty
tokens: border grid, muted axis 12dp, accent/success highlight | a11y: table semantics + text fallback list, reduce-motion = no grow anim

## RangeSwitch [S03]
variants: Day/Week/Month segmented | size: h48dp pill r28 full-width | states: selected (accent fill), unselected, focused
tokens: surface container, accent selected + onAccent text | a11y: segmented Button group, arrow-key nav

## BadgeCard [S05]
variants: locked/unlocked/new | size: 104dp grid, icon 28dp | states: default, pressed, focused
tokens: surface r16 border, muted locked (lock icon), warning new-dot | a11y: Button, label "10k steps, unlocked Mar 4"

## StreakCard [S02,S05]
variants: active/at-risk/broken | size: full-width h96dp | states: default, warning pulse (off if reduce-motion)
tokens: surface r16, accent flame, warning at-risk, danger broken | a11y: status text not color-only ("2 days left")

## SettingsRow+Toggle [S06]
variants: row[label+desc+control] / Toggle on/off | size: row 64dp, hit 48dp | states: on, off, disabled, focused
tokens: surface, text 16dp + muted 14dp desc | a11y: real Switch, stateDescription, label-control pairing

## PauseResumeFAB [S02]
variants: pause/resume extended | size: 56dp h, pill r28, icon+label | states: default, pressed, focused
tokens: accent bg + onAccent text/icon | a11y: real FAB Button, label "Pause counting"/"Resume counting"

## TargetPicker [S02,S06]
variants: stepper dialog (- 8000 +) + presets 5k/8k/10k | size: sheet r28, buttons 48dp | states: default, min/max disabled
tokens: surface, accent confirm, border stepper | a11y: dialog title, +/- real Buttons, announce value

## EmptyState [S03,S05]
variants: no-data/no-permission | size: icon 40dp, title 20dp, body 14dp + Primary CTA | states: static only
tokens: muted icon/text on bg, r16 card optional | a11y: heading + plain truthful copy, CTA 48dp

## PermissionCard [S01]
variants: activity-recognition notice | size: full-width r16 p16 | states: default, denied (show settings link)
tokens: surface border, text/muted, accent CTA | a11y: real Buttons Allow/Not now, no dark-pattern copy

## CelebrationSheet [S02]
variants: goal/badge/streak | size: bottomSheet r28, drag-handle, confetti* | states: shown once, dismissed
tokens: surface, success check, text 20dp + muted 14dp | a11y: dialog, focus-trapped, confetti off under reduce-motion, Ghost dismiss

## Buttons Primary/Ghost [all]
variants: Primary filled / Ghost text-only | size: h48dp r16/pill, 14dp label | states: default/pressed/disabled/loading/focus-ring
tokens: Primary accent+onAccent; Ghost transparent+accent text; disabled muted/border | a11y: real Button, loading blocks double-tap + spinner label
