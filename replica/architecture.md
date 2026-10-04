# Architecture — Tally offline step counter (clean-room)
> Stack: Kotlin + Compose M3 + Room + DataStore + Glance + Health Connect. Single `:app`, minSdk 26, targetSdk 35. CI: `lint + assembleDebug`. No server/login.

## 1. Stack — why
| Layer | Choice | Why |
|---|---|---|
| UI | Compose M3 | single toolkit, S02–S06 + theme/AMOLED, no Fragments |
| Sensor | `TYPE_STEP_COUNTER` | hardware-batched, lowest battery; fallback accel if missing |
| DB | Room | offline source of truth for Day/Workout/Award; survives kill |
| Prefs | DataStore Proto/Prefs | typed goal/metrics/flags, no SQL for KV |
| BG | FGS + WorkManager + BootReceiver | FGS live count, WM hourly flush, boot rebaseline |
| Charts | custom Canvas | 3 bars (day/week/month S03), zero dep, exact ring reuse |
| Widget | Glance | S07 + notif count without opening, system-managed updates |
| Health | Health Connect client | read/write Steps, no vendor SDKs, user-gated |

## 2. Data
```kotlin
Day(epochDay:Long PK, steps:Int, distanceM:Float, kcal:Float, activeMin:Int, goal:Int, manualDelta:Int, source:String, updatedAt:Long) // idx(updatedAt)
Workout(id:String PK, type:String, startMs:Long, endMs:Long, steps:Int, distanceM:Float, pausedMs:Long, gpsPolyline:String?) // idx(startMs)
Award(id:String PK, unlockedDay:Long) // e.g. `7-streak`, `10k`
```
Prefs keys: `goal`, `heightCm`, `weightKg`, `stepLenCm`, `units`, `theme`, `sensitivity[L|M|H]`, `treadmill:Bool`, `hcMode[OFF|R|RW]`, `hcAuto:Bool`, `batteryAsked:Bool`.
Migration: NEVER `fallbackToDestructiveMigration()`; real `Migration(1→2…)` from v1 + `autoMigrations` where possible + migration test on schema dump.

## 3. Step engine (StepRepository, clean-room)
- Counter delta: `delta = counter - baseline`; persist `baseline+bootId` in DataStore per epochDay. If `counter < baseline` → reboot → `baseline=counter`, keep `Day.steps`.
- Day-roll: check `epochDay` on every event + WM tick + `ACTION_DATE_CHANGED`; freeze yesterday, set `baseline=counter`, start Day with `manualDelta` carried=0.
- HC merge (no double-count): `total = max(sensorDay, hcDay_exclOurs) + manualDelta`; `hcDay_exclOurs` filters `metadata.dataOrigin=ours`; write-back only sensor delta with our session; sensor gap (>30min no event) → HC fills gap only.
- One-way AUTO latch: S06 toggle default OFF; once granted+enabled `hcAuto=true` persists; never auto-off on revoke — user must toggle; reads never overwrite larger sensor value.
- Sensitivity: `L/M/H` → cadence gate `12/8/4s` + min-burst `10/6/2` steps; filters accel-fallback + workout cadence only, never rewrites HW counter.
- Treadmill: `treadmill=true` → no GPS, `Workout.type=treadmill`, distance=`steps*stepLenCm`, GPS drift filter skipped, source=`treadmill`.
- Manual edit: S02 edit writes only `Day.manualDelta` (+/-) with audit `updatedAt`; delete zeroes it; never touches `baseline`; excluded from HC write-back, included in export.

## 4. Background resilience
- S01 prompt: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` after Activity Recognition granted; `batteryAsked` avoids nag; fallback to settings intent if denied.
- OEM guide (in-app S06 card): Xiaomi: Settings→Apps→Autostart=ON + Battery saver=No restrictions + Lock in Recents; BBK (Oppo/Vivo/Realme): same + Allow background + Startup manager.
- FGS: `health` type for step service (persistent notif S07, `POST_NOTIFICATIONS` gated); `location` type added only during active S04 GPS workout, dropped on stop.
- WorkManager: hourly `SyncWorker` (requiresBatteryNotLow=false, `setExpedited`) → flush Day, rollover check, Glance update, HC sync if `hcMode!=OFF`; `BootReceiver` re-registers FGS+WM, rebaselines counter.

## 5. Bite list
- TZ/DST: key all Days by `epochDay = LocalDate.now(zone).toEpochDay()`; on `TIMEZONE_CHANGED` recompute today, never move past rows.
- Midnight: rollover path runs even if killed — WM + next launch reconcile `updatedAt` vs `epochDay`.
- Reboot: `counter<baseline` guard + BootReceiver; sensor-missing state on S02 if no counter after 10s.
- Gaps: >30min silence → mark gap, HC backfill only gap window; never interpolate sensor.
- GPS: S04 Kalman/5s window, drop `accuracy>20m` or `speed>3.5m/s walk`; car-count fix; GPS denied → step-only fallback.
- GDPR: local-only (Room+DataStore), no account/ads/trackers → trivially compliant; export CSV/PDF + backup ZIP are explicit user actions; delete = clear app data.

## 6. Build order
- Slice 0: count→today→persist: sensor delta + S02 ring + Day row + BootReceiver (F05/F02 happy path).
- Musts: S01 perms+battery, rollover/reboot, S03 Canvas bars, S05 streaks/awards, S07 Glance+notif, sensitivity, goal ring (F01≤3 taps).
- Shoulds: S04 GPS + treadmill + pause/auto-break, manual edit, HC sync, export/backup, units/theme (F04 start≤2 taps).
- Fixes top-up: no ads/login, OEM cards, widget reliability, backup restore test, car/GPS filter tuning.
