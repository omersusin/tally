# Recon map — best open-source Android step counter

Scope: Android pedometer, core loop (count → today → history → goal). For user's own open-source app. Clean-room: functionality and UX patterns only, nothing copied (no logos, copy, brand colours, proprietary assets).

## Sources

| source | url |
|---|---|
| Stride source (local clone) | ~/cloned_repos/stride |
| Steppy (user's own skeleton) | ~/steppy |
| Pacer homepage / features | https://www.mypacer.com/, https://www.mypacer.com/walking-app |
| Pacer Android guide | https://www.mypacer.com/blog/pacer-android-homepage-update-guide |
| Pacer background-GPS support | https://support.mypacer.com/hc/en-us/articles/41657466271757-GPS-tracking-stops-when-Pacer-is-in-background-Android |
| Pacer Play listing | https://play.google.com/store/apps/details?hl=en_US&id=cc.pacer.androidapp |
| Leap Pedometer listing | https://play.google.com/store/apps/details?hl=en&id=pedometer.steptracker.calorieburner.stepcounter |
| Simple Design listing | https://play.google.com/store/apps/details?id=stepcounter.steptracker.pedometer.calorie&hl=en_IN |
| Accupedo manual | https://accupedo.com/usermanual.html |
| Google Fit help | https://support.google.com/fit/answer/7619539?co=GENIE.Platform%3DAndroid&hl=en |
| Samsung Health steps | https://www.samsung.com/us/support/answer/ANS10001370/ |
| Zombies Run | https://zombiesrungame.com/ |

## Screen inventory

| id | screen | how to get there | purpose | key components | states |
|---|---|---|---|---|---|
| S01 | Onboarding + permissions | first launch | explain value, grant Activity Recognition, notifications, battery exemption | pages, grant buttons, skip | granted / denied / skipped |
| S02 | Today (home) | default tab | giant live count, goal ring, distance/kcal/active stats | ring, stat rows, pause/resume, edit | empty morning / live / goal-reached / sensor-missing |
| S03 | History / trends | tab | day/week/month charts, totals, streaks | bar chart, range switch, day detail | empty / filled / range states |
| S04 | Workout (GPS, opt-in) | tab / FAB | timed walk/run/hike/ride with map, pause, auto-break | map, timer, pause/resume, target picker | idle / active / paused / break / summary |
| S05 | Awards / streaks | tab | achievements, streak flame, milestones | badges grid, streak card | locked / unlocked |
| S06 | Settings | tab | goal, personal metrics, units, theme, backup, sensitivity | forms, toggles, export | default / edited |
| S07 | Widget + notification | launcher / shade | glanceable live count without opening | Glance widget, persistent notif | live / paused / permission-lost |

## Flows

```
F01 First run → count: S01 grant → S02 live count today (happy path: 3 taps)
    edge: sensor absent, permission denied, battery-optimizer kills service
F02 Daily use: open S02 → see progress → goal celebration
    edge: midnight rollover, reboot, timezone change
F03 Review progress: S03 charts → day detail → streak
    edge: no data yet, restored from backup
F04 Optional workout: S04 start → pause/break → summary saved
    edge: GPS denied (step-only fallback), GPS drift, incoming call
F05 Resilience: reboot → service restarts → counts continue, no loss
F06 Trust: export/backup → reinstall → history restored
```

Happy-path click counts to beat: F01 ≤3 taps, F02 = 0 taps (widget), F04 start ≤2 taps.

## Components

Buttons (primary/ghost/danger), goal ring, stat row, bar chart, range switch, badge card, streak card, settings form + toggles, pause/resume FAB, target picker, empty states, permission rationale cards, celebration sheet.

## Inferred data model

```
Day  epochDay, steps, distanceM, calories, activeMin, goal, source (sensor|health|manual|workout)
     evidence: Stride DailySummaryEntity, Steppy Day/DayDao — confidence high
Workout  id, type (walk|run|hike|ride|treadmill), startedAt, endedAt, steps, distanceM, gpsTrack?, pausedMs
     evidence: Pacer GPS flows, steppy README plan — confidence medium
Award  id, unlockedAtEpochDay — confidence high
Prefs  goal, height, weight, stepLength, units, theme, sensitivity, treadmillMode — confidence high
```

## Cannot be cloned (skip)

Licensed content (Pacer video workouts, Zombies story audio), server social graphs (clubs/leaderboards need our own backend or skip), proprietary coach AI, partner wearable SDKs beyond Health Connect. Our answer: offline-first, no account, Health Connect sync instead of proprietary integrations.

## Size

Screens 7, flows 6, entities 4. Hard parts: background resilience across OEMs (Xiaomi/BBK), Health Connect merge without double-count, GPS drift filtering. Size: M (few weeks, vertical slice first).

Next: /replica-architect.
