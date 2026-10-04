# Tally — the honest step counter

Offline-first, open-source Android step counter. No ads. No account. No tracking. Your steps stay on your phone.

## What it does

- **Today** — giant live count, goal ring with overflow (`+X over`), distance / calories / active minutes, pause-resume, goal presets, celebration shown once
- **History** — day / week / month bar charts, day detail, streaks, screen-reader text fallback
- **Workout** — walk / run / hike / ride + treadmill step-only mode, targets (free, steps, distance, time), pause + auto-break, honest summary (sensor count stands, nothing added)
- **Awards** — badges and streaks with plain-word states, never color-only
- **Settings** — goal, height / weight / step length, metric + imperial, light / dark / true-black, sensitivity, Health Connect (off by default), battery-exemption + Xiaomi / BBK guides, CSV export, ZIP backup + validated restore
- **Glance widget + notification** — live count without opening the app

## Privacy

No `INTERNET` permission. No ads, analytics, or trackers. Backup, export, and Health Connect sync only happen when you tap them. `allowBackup=false`; delete = clear app data.

## Build

CI builds every `main` push (lint + debug APK, in Actions artifacts). Locally you need JDK 17 and the Android SDK (platform 35, build-tools 35.0.0):

```sh
gradle :app:assembleDebug
```

## Stock engine notes (honesty file)

- Crash between writes under-counts rather than inventing steps — by design.
- Post-midnight sensor batches land wholly on the new day (small skew, unavoidable).
- Restored backups keep your current goal; Health Connect stays off after restore.
- Merged Health Connect steps are attributed to Tally on write-back (documented limitation).

## Contribute

One writer per file, small diffs, verify on device. See `replica/` for the recon map, architecture, brand, and design tokens.

License: MIT.
