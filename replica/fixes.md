# Fixes — what the originals get wrong, in users' words (honest sample: search excerpts + aggregators, Play not directly reachable; no invented counts)

## What they hate
1. Intrusive/deceptive ads (5 sources): "so many ads it doesnt function" — https://apprecs.com/android/charisma.motiondetectorpedometer.steptrackercounter/pacer-pedometer-step-counter
2. Inaccurate/vanishing steps (5 sources): "I did 80 and the app counted 41" — https://appy.fyi/faq/com.tayu.tau.pedometer ; "watch the Fit app decrease my count by a few thousand at a time" (paraphrase flagged verbatim from reddit excerpt) — https://www.reddit.com/r/GoogleFit/comments/1loelez/inconsistent_step_tracking_android_16/
3. Background kill, stops when locked (3 sources): Pacer support admits exemptions needed — https://support.mypacer.com/hc/en-us/articles/41657466271757-GPS-tracking-stops-when-Pacer-is-in-background-Android
4. GPS/distance errors, car counted (3 sources, paraphrase).
5. Crashes + lost streaks/history (3 sources): "Crashes regularly (upon every Android update?)" — https://play.google.com/store/apps/details?hl=en_US&id=com.corusen.aplus
6. Forced subscription, no one-time buy (2 sources): "I'll be more than happy to pay for it once" — https://apps.apple.com/au/app/pacer-pedometer-step-counter/id600446812?platform=iphone&see-all=reviews

## Missing (asked by name)
- Treadmill/indoor mode; working widget + notification count; edit/delete steps; reliable Health/Fit sync; backup + no-login privacy; one-time remove-ads.

## Unsolved
Treadmill/walking-pad users; low-mobility users needing low-count accuracy; privacy-first offline users; long-term history keepers after phone switch.

## Fix plan (top 5, evidence x cheapness)
1. No ads, offline sensor-only, no login (S) → features.csv row trust
2. Battery-exemption + OEM autostart guidance + sensitivity (S)
3. Treadmill mode + manual edit/delete (S)
4. Widget + notification count that just works (S)
5. Export/backup + Health Connect sync (M)

## Angle (recommended)
For walkers who just want an honest count, [APP] counts steps offline with no ads, no account, and a widget that works.
Evidence: ads + accuracy + background-kill themes, 10+ sources.
