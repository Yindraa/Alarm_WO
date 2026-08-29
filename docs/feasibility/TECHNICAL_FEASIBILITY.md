# Mission Alarm — Technical Feasibility

| Field | Value |
|---|---|
| Product | Mission Alarm |
| Document | Technical Feasibility |
| Version | 0.2 |
| Status | Conditional Pass — Emulator Proven; Physical Device Qualification Pending |
| Scope | Android-first MVP; iOS desk assessment only |
| Date | 2026-08-28 |
| Prototype | [`../../spikes/mobile-feasibility`](../../spikes/mobile-feasibility) |

## 1. Objective

Fase 2 membuktikan dua asumsi teknis paling berisiko sebelum arsitektur produksi dikunci:

1. Android dapat memicu alarm lokal secara tepat waktu, memasuki foreground flow, berbunyi, dan memberi jalur menuju mission UI dari kondisi background/lock-screen.
2. Perangkat dapat menjalankan CameraX dan MediaPipe Pose Landmarker secara on-device, kemudian mengubah landmark menjadi progress push-up tanpa mengirim frame ke JavaScript atau backend.

Fase ini menggunakan disposable spike. Source spike bukan fondasi production dan tidak boleh dipindahkan langsung tanpa reliability design, persistence, security review, serta test coverage yang diwajibkan fase berikutnya.

## 2. Exit criteria

| ID | Criterion | Status |
|---|---|---|
| FEAS-ENV-001 | Toolchain Android dan React Native dapat dikonfigurasi | Passed |
| FEAS-ALM-001 | Exact alarm dapat dijadwalkan dan dipicu pada device | Passed — emulator API 36 |
| FEAS-ALM-002 | Alarm dapat memulai foreground audio/service | Passed — emulator API 36; audio hardware qualification pending |
| FEAS-ALM-003 | Full-screen/lock-screen behavior terukur | Passed — emulator API 36; OEM matrix pending |
| FEAS-ALM-004 | Permission denial dan recovery terukur | Partial — special access false/true recovery proven; remaining denial matrix pending |
| FEAS-CV-001 | MediaPipe model dapat dimuat dari app asset | Passed — emulator API 36 |
| FEAS-CV-002 | Live camera frame diproses on-device | Partial — CameraX/inference loop active; human landmark validation pending |
| FEAS-CV-003 | Push-up state machine menolak partial dan low-confidence input | Passed — synthetic |
| FEAS-CV-004 | FPS, latency, dan thermal behavior terukur | Pending physical device |
| FEAS-DOC-001 | Risiko, batas OS, dan rekomendasi go/no-go terdokumentasi | Passed |

Spike memberi bukti cukup untuk melanjutkan perancangan Fase 3 secara kondisional. Fase 2 belum boleh ditutup sebagai qualification produksi: alarm dan camera/CV adalah kemampuan hardware/OS sehingga pengujian perangkat fisik tetap menjadi gate sebelum implementasi MVP dianggap release-ready.

## 3. Environment audit

### 3.1 Host

| Component | Detected |
|---|---|
| Architecture | Apple Silicon `arm64` |
| macOS | 26.6.2 |
| Node.js | 24.19.0 |
| npm | 11.17.0 |
| Xcode | 26.6, iOS SDK 26.5 |
| Android Studio | Installed |
| JDK | OpenJDK 17.0.20.1 installed via Homebrew |
| Android command-line tools | 15859902 installed |
| Android SDK packages | Platform Tools 37.0.1, Platform 37, Build Tools 37.0.0/36.0.0, NDK 27.1.12297006, CMake 3.22.1 |
| Emulator | Android API 36, Google Play ARM64 (`medium_phone`) |
| Physical Android device | Not detected/tested yet |

### 3.2 Selected spike versions

| Component | Version |
|---|---|
| React Native | 0.87.0 |
| React | 19.2.3 |
| TypeScript | 6.0.x |
| Android Gradle Plugin | Template-managed React Native 0.87 |
| Kotlin | 2.2.0 |
| Compile SDK | 37 |
| Target SDK | 36 |
| Minimum SDK | 24 |
| Android Build Tools | 37.0.0 |
| CameraX | 1.6.1 stable |
| MediaPipe Tasks Vision | 1.0.0 |
| Pose model | Pose Landmarker Lite, float16 |

React Native 0.87 dipilih karena merupakan current stable baseline saat spike dibuat dan menggunakan Strict TypeScript API, AGP 9, serta Kotlin 2.x. Versi dependency dikunci; tidak ada `latest.release` di source build.

## 4. Android alarm spike

### 4.1 Implemented path

```text
React Native screen
    -> typed Turbo Native Module
    -> TestAlarmScheduler
    -> AlarmManager.setExactAndAllowWhileIdle
    -> immutable PendingIntent
    -> ExactAlarmReceiver
    -> AlarmForegroundService
    -> high-importance alarm notification
    -> full-screen PendingIntent
    -> MainActivity shown over lock screen
```

Spike mencakup:

- Pemeriksaan `canScheduleExactAlarms()`.
- Deep-link ke exact alarm special access.
- Pemeriksaan `canUseFullScreenIntent()` pada OS yang mendukung.
- Exact RTC wake-up alarm 15 detik.
- Immutable `PendingIntent`.
- Broadcast receiver yang tidak diekspor.
- Foreground service dengan media-playback type.
- Looping default alarm audio menggunakan `USAGE_ALARM`.
- High-importance alarm notification.
- Full-screen intent menuju React Native activity.
- `setShowWhenLocked` dan `setTurnScreenOn`.
- Stop action khusus tester agar spike tidak menjebak perangkat.

### 4.2 Important constraints

1. `SCHEDULE_EXACT_ALARM` adalah special access dan dapat dicabut user/system. Semua alarm perlu dijadwalkan ulang setelah akses kembali tersedia.
2. Full-screen intent memiliki access/policy tersendiri pada Android modern. Jika tidak diizinkan, fallback yang diharapkan adalah heads-up/lock-screen notification, bukan silent failure.
3. Force-stop mengubah jaminan scheduling; produk tidak boleh menjanjikan dapat mengalahkan tindakan force-stop atau power-off.
4. Foreground service dan notification permission harus diuji per OS/vendor.
5. Audio test menggunakan default system alarm. Production memerlukan audio focus, interruption, volume-policy, vibration, dan lifecycle specification.
6. Spike belum memiliki persistence, boot rescheduling, timezone update, duplicate suppression, atau FIFO overlap queue. Komponen itu baru layak dibuat setelah primitive trigger terbukti.
7. Tombol `STOP TEST` hanya untuk keselamatan spike. Production normal-dismiss tetap dikunci oleh mission engine.

### 4.3 Required device scenarios

| Scenario | Expected evidence |
|---|---|
| Foreground, screen on | Trigger timestamp, audio start, UI transition |
| Background, screen on | Trigger timestamp, notification/full-screen behavior |
| Screen locked | Wake/lock-screen presentation and audio |
| Doze/idle | Trigger drift from scheduled timestamp |
| App removed from recents | Receiver/service behavior |
| Exact alarm access denied | Clear failure and recovery route |
| Notification denied | Audio/UI fallback behavior and recoverability |
| Full-screen access denied | Heads-up notification fallback |
| Battery saver enabled | Trigger drift and service start behavior |
| OEM-restricted device | Vendor-specific restriction evidence |

Passing recommendation:

- No duplicate receiver execution.
- Alarm audio begins once.
- User can always reach the spike UI or stop the safe test.
- Denied permission produces recoverable error, not false success.
- Trigger drift is recorded rather than assumed.

### 4.4 Emulator runtime evidence

Validasi dilakukan pada Android API 36 Google Play ARM64:

| Check | Evidence | Result |
|---|---|---|
| Native capability | `canScheduleExactAlarms=true`; `canUseFullScreenIntent=true` setelah special access diberikan | Passed |
| Exact scheduling | `RTC_WAKEUP`, `setExactAndAllowWhileIdle`, `exactAllowReason=permission`, trigger 15 detik | Passed |
| Locked screen | Sebelum trigger `mWakefulness=Dozing`; sesudah trigger `mWakefulness=Awake` | Passed |
| Full-screen route | `MainActivity` menjadi `topResumedActivity` dari kondisi layar terkunci | Passed |
| Foreground service | `AlarmForegroundService`, `isForeground=true`, notification ID `4104` | Passed |
| Notification | Category `alarm`, importance `HIGH`/4, ongoing, public visibility, full-screen `PendingIntent` | Passed |
| Recovery | Stop action menghentikan service dan menghapus notification | Passed |
| Duplicate execution | Satu receiver/service execution pada skenario lock-screen | Passed |

Default alarm sound pada emulator tidak dapat dijadikan bukti kualitas audio perangkat. Service menangani kegagalan `MediaPlayer` secara aman agar UI dan stop path tetap dapat dipulihkan. Skenario Doze panjang, force-stop, notification/full-screen denial, battery saver, serta variasi OEM masih termasuk physical-device qualification.

## 5. Computer vision spike

### 5.1 Implemented path

```text
Camera permission
    -> native PoseFeasibilityActivity
    -> CameraX front-camera Preview + ImageAnalysis
    -> KEEP_ONLY_LATEST backpressure
    -> rotated Bitmap / MPImage
    -> MediaPipe Pose Landmarker LIVE_STREAM
    -> choose most-visible body side
    -> landmark visibility filter
    -> elbow and body angle
    -> PushUpStateMachine
    -> reps + feedback displayed natively
```

Frame tidak melewati React Native bridge. Hanya perintah membuka spike melewati Turbo Module. Desain ini menghindari transfer frame berfrekuensi tinggi melalui JavaScript dan sesuai prinsip on-device privacy.

### 5.2 Model

| Field | Value |
|---|---|
| Asset | `pose_landmarker_lite.task` |
| Size | Approximately 5.5 MB |
| SHA-256 | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |
| Source | Official Google MediaPipe model storage |
| Runtime | CPU baseline, live-stream asynchronous |

Lite digunakan untuk feasibility karena startup dan throughput lebih penting daripada mengejar accuracy maksimum sebelum device benchmark tersedia. Full model dapat dibandingkan setelah baseline metrics diperoleh.

### 5.3 Provisional state machine

```text
READY
  -> TOP when elbow >= 150° and alignment valid
  -> DOWN when elbow <= 95° and alignment valid
  -> TOP when elbow >= 150°
  -> +1 valid rep
```

Provisional filters:

- Required side landmarks: shoulder, elbow, wrist, hip, ankle.
- Minimum visibility: 0.60.
- Minimum body alignment angle: 150°.
- Most-visible left/right side is selected per result.
- Incomplete `TOP -> partial -> TOP` does not count.
- Low-visibility result does not mutate state or progress.

Angka ini bukan product threshold. Fase 5 harus menentukan threshold berdasarkan dataset, body diversity, camera placement, dan error analysis.

### 5.4 Synthetic evidence

Pure Kotlin smoke test telah membuktikan:

- `TOP -> DOWN -> TOP` menambah satu rep.
- Partial depth tidak menambah rep.
- Low visibility tidak mengubah progress.
- State machine dapat diuji tanpa camera atau MediaPipe dependency.

Belum terbukti:

- Landmark jitter pada manusia nyata.
- Side-selection stability.
- Occlusion dan loose clothing.
- False count akibat arm-only motion.
- Front-angle versus side-angle behavior.
- Inference FPS dan end-to-end latency.
- Thermal throttling dan battery usage.
- Behavior pada perangkat kelas bawah/menengah.

### 5.5 Emulator runtime evidence

Pada Android API 36, native `PoseFeasibilityActivity` berhasil menjadi activity aktif. MediaPipe memuat `libmediapipe_tasks_jni.so` dan image-processing JNI dari APK, CameraX membuka front camera virtual, `Preview` dan `ImageAnalysis` berjalan, serta UI memperbarui hasil menjadi `Body not detected` tanpa crash.

Ini membuktikan integrasi, packaging model, lifecycle camera, dan inference loop. Kamera virtual hanya menampilkan test scene tanpa manusia, sehingga hasil tersebut bukan bukti accuracy, landmark quality, rep counting pada manusia, FPS, atau thermal behavior.

### 5.6 Required measurements

Untuk setiap device dan skenario, catat:

- Camera startup time.
- Model initialization time.
- Median dan p95 inference latency.
- Processed FPS selama minimal dua menit.
- Dropped-frame ratio.
- CPU/memory utilization.
- Thermal condition setelah lima menit.
- Valid rep recall.
- Invalid/partial false-positive rate.
- Detection recovery time setelah tubuh keluar frame.

Fase 2 tidak menetapkan target accuracy final. Tujuannya membuktikan pipeline layak dan menemukan batas awal untuk Technical Requirements.

## 6. Automated evidence

| Check | Result |
|---|---|
| React Native template creation | Passed |
| npm dependency installation | Passed |
| Jest render test | Passed |
| ESLint | Passed, zero warnings |
| TypeScript `tsc --noEmit` | Passed |
| Pose model download/checksum | Passed |
| Pure Kotlin push-up smoke test | Passed |
| React Native Codegen | Passed |
| Android Kotlin/Java/native compilation | Passed |
| Android unit tests | Passed |
| Debug APK assembly | Passed |
| APK installation/launch | Passed — emulator API 36 |
| Locked-screen alarm runtime | Passed — emulator API 36 |
| CameraX/MediaPipe initialization | Passed — emulator API 36 |
| Human pose and push-up runtime | Pending physical device |

Final debug artifact at the time of validation:

- Path: `spikes/mobile-feasibility/android/app/build/outputs/apk/debug/app-debug.apk`
- Size: 180,841,451 bytes
- SHA-256: `11c3a5745741f147a0daf74e23b371065dec0dc3f0cf2f4ee1e20c7f92f97ce7`

Build emits deprecation warnings from the current React Native/AGP integration. Warnings do not fail the spike, tetapi perlu dimonitor saat dependency baseline produksi dikunci.

## 7. iOS desk assessment

iOS is not part of the first MVP release. Current host has Xcode 26.6 and iOS SDK 26.5, so future feasibility can use:

- AlarmKit for prominent scheduled alarms.
- Swift native module for React Native integration.
- AVFoundation for camera frames.
- MediaPipe Tasks Vision for iOS.

Important: AlarmKit presentation, stop intent, authorization, minimum OS, and the ability to route dismissal into a mission flow must be prototyped separately. Android results cannot be projected onto iOS.

## 8. Preliminary decisions

| Decision | Status | Reason |
|---|---|---|
| React Native 0.87 + TypeScript | Proceed | Template, Jest, lint, and strict typecheck pass; native integration path is available. |
| Kotlin native alarm engine | Proceed | Alarm scheduling and OS lifecycle are platform responsibilities. |
| Native CameraX/MediaPipe processing | Proceed | Keeps frames off JS bridge and supports offline/privacy requirements. |
| CameraX 1.6.1 stable | Conditional proceed | Emulator camera lifecycle passes; hardware performance pending. |
| MediaPipe Tasks Vision 1.0.0 + Lite model | Conditional proceed | Model and inference pipeline load on emulator; human/device evidence pending. |
| Android minimum API 24 | Provisional | React Native template baseline; product/device-market decision belongs to Technical Requirements. |
| Backend for core flow | Reject for MVP | Adds no value to real-time alarm or verification path and violates offline dependency principle. |
| Final CV thresholds | Defer | Requires measured human/device dataset. |
| Go to Fase 3 Technical Requirements | Approved conditionally | Build and both high-risk integration paths run on API 36 emulator. |
| Production implementation/release qualification | Pending | Requires physical-device/OEM alarm matrix and human CV performance evidence. |

## 9. Recommendation and carry-over gates

Rekomendasi Fase 2 adalah **conditional go** ke Fase 3. Tidak ada blocker arsitektural yang ditemukan untuk Android-first, offline alarm, atau native on-device pose processing.

Carry-over gates berikut harus masuk ke Technical Requirements dan Testing Strategy:

1. Alarm matrix pada minimal satu perangkat fisik Android referensi dan satu OEM dengan battery policy agresif.
2. Permission-denial recovery untuk exact alarm, notification, full-screen intent, dan camera.
3. Locked-screen, Doze, reboot/reschedule, timezone change, force-stop, battery saver, serta overlap behavior.
4. Benchmark CV di perangkat kelas menengah: startup, median/p95 latency, FPS, memory, battery, dan thermal.
5. Dataset gerakan manusia untuk valid rep, partial rep, body diversity, occlusion, camera angle, serta false-positive analysis.
6. Threshold akhir dan target kualitas tidak boleh diturunkan dari angka state machine provisional tanpa evidence Fase 5.

## 10. Risk register

| Risk | Probability | Impact | Mitigation |
|---|---|---|---|
| Exact alarm permission revoked | Medium | High | Capability check, settings recovery, reschedule audit |
| Full-screen intent unavailable | Medium | High | Heads-up fallback, permission education, device tests |
| OEM background restriction | High | High | Multi-vendor device matrix and explicit guidance |
| Force-stop/power-off | Medium | High | Honest product boundary; recovery after next launch/boot where permitted |
| False-positive push-up | Medium | High | Sequence, alignment, visibility, hysteresis, dataset testing |
| False-negative push-up | High initially | High | Setup guide, feedback, threshold tuning, emergency dismiss |
| Low-end CV performance | Medium | High | Lite model, frame throttling, benchmark-based device floor |
| Thermal/battery load | Medium | Medium | Process latest frame only, cap FPS, stop camera immediately on completion |
| React Native/native lifecycle mismatch | Medium | High | Native source of truth; persist state before emitting UI events |
| Dependency regression | Medium | Medium | Pin versions and retain native adapter boundaries |

## 11. Remaining qualification work

1. Connect at least one physical Android reference device and one OEM device with aggressive battery policy.
2. Execute the full alarm scenario and permission-denial matrix from Section 4.
3. Execute human camera/pose tests and record the measurements from Section 5.
4. Compare Lite versus Full model only if Lite fails the accuracy gate or has sufficient performance headroom.
5. Update this document to version 1.0 `Accepted` after the physical-device gates pass, atau record a change/no-go decision with evidence.

A physical device is mandatory for final qualification because emulator evidence does not represent OEM power management, speaker behavior, real camera throughput, human pose accuracy, or thermal behavior.

## 12. Primary references

- [React Native environment setup](https://reactnative.dev/docs/set-up-your-environment)
- [React Native 0.87 release](https://reactnative.dev/blog/2026/08/11/react-native-0.87)
- [Android exact alarms](https://developer.android.com/develop/background-work/services/alarms)
- [Android AlarmManager](https://developer.android.com/reference/android/app/AlarmManager)
- [CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera)
- [MediaPipe Pose Landmarker for Android](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android)
- [Apple AlarmKit](https://developer.apple.com/documentation/alarmkit)
