# Mission Alarm — Technical Requirements Specification

| Field | Value |
|---|---|
| Product | Mission Alarm |
| Document | Technical Requirements Specification (TRS) |
| Version | 1.0 |
| Status | Accepted |
| Scope | Android-first MVP |
| Date | 2026-08-28 |
| Product baseline | [`../product/MVP_SCOPE.md`](../product/MVP_SCOPE.md) |
| Feasibility evidence | [`../feasibility/TECHNICAL_FEASIBILITY.md`](../feasibility/TECHNICAL_FEASIBILITY.md) |

## 1. Purpose

Dokumen ini menerjemahkan PRD, BRS, keputusan Fase 1, dan bukti Fase 2 menjadi persyaratan teknis yang dapat diimplementasikan dan diuji. Dokumen ini menentukan **apa yang wajib dilakukan sistem** serta batas kualitasnya, tetapi belum menetapkan detail komponen, tabel database final, payload API final, layout pixel-level, atau threshold CV final.

Kata **MUST**, **MUST NOT**, **SHOULD**, dan **MAY** bersifat normatif. Requirement berstatus `Provisional` wajib divalidasi dengan evidence sebelum menjadi release gate final.

## 2. Scope and constraints

### 2.1 In scope

- Aplikasi Android offline-first.
- One-time dan weekly-repeat alarm.
- Tepat satu mission per alarm: Push-up, Math, atau QR.
- Exact local scheduling, active alarm, mission lock, emergency dismissal, dan local history.
- Native Android alarm lifecycle dan on-device camera/pose processing.
- Transactional local persistence serta recovery setelah process death/reboot sesuai batas OS.

### 2.2 Out of scope

- iOS, backend, account, authentication, cloud sync, analytics pihak ketiga.
- Snooze, multiple mission, Squat, Plank, gamification, statistics dashboard.
- Raw camera recording, remote CV inference, custom/downloaded alarm sound.
- Upaya mencegah force-stop, power-off, uninstall, atau kontrol privileged OS.

## 3. Technical baseline

| ID | Requirement | Status |
|---|---|---|
| TR-PLT-001 | Production app MUST menggunakan React Native + TypeScript untuk application UI dan Kotlin untuk capability Android yang memerlukan lifecycle/native control. | Accepted |
| TR-PLT-002 | Minimum supported Android MUST API 24. Perilaku capability yang hanya tersedia pada API lebih baru MUST memiliki version-specific handling. | Accepted |
| TR-PLT-003 | Baseline build MUST menggunakan compile SDK 37 dan target SDK 36. Upgrade target SDK berikutnya MUST melalui regression suite alarm/permission. | Accepted |
| TR-PLT-004 | Core alarm, active-instance recovery, dan mission verification MUST NOT membutuhkan internet atau backend. | Accepted |
| TR-PLT-005 | Dependency versions MUST dikunci dan reproducible build MUST menggunakan lockfile. Dynamic dependency version MUST NOT digunakan. | Accepted |
| TR-PLT-006 | Frame kamera MUST diproses pada native pipeline; raw frame MUST NOT dikirim melalui React Native bridge. | Accepted |
| TR-PLT-007 | Spike Fase 2 MUST diperlakukan sebagai disposable evidence; production code MUST memenuhi persistence, idempotency, recovery, security, dan testing dalam TRS ini. | Accepted |

## 4. System invariants

Invariants berikut berlaku pada seluruh implementasi:

| ID | Invariant |
|---|---|
| TR-INV-001 | Tanpa verification valid, normal dismissal MUST NOT terjadi. |
| TR-INV-002 | Emergency dismissal MUST selalu dapat dicapai dari active-alarm/mission flow dan menghasilkan `EMERGENCY_DISMISSED`, bukan `SUCCESS`. |
| TR-INV-003 | Satu scheduled occurrence MUST menghasilkan paling banyak satu alarm instance. |
| TR-INV-004 | Satu alarm instance MUST memiliki paling banyak satu terminal result. |
| TR-INV-005 | Progress physical mission MUST hanya berasal dari verification engine. |
| TR-INV-006 | Error, low confidence, missing permission, timeout, atau process restart MUST NOT menghasilkan success. |
| TR-INV-007 | Konfigurasi aktif MUST di-snapshot ke instance; edit berikutnya MUST NOT mengubah instance aktif atau history. |
| TR-INV-008 | Raw camera frame/video MUST NOT disimpan atau ditransmisikan. |
| TR-INV-009 | Semua critical state transition MUST dipersist sebelum side effect yang tidak aman untuk diulang dianggap selesai. |
| TR-INV-010 | Semua terminal flow MUST menghentikan audio, foreground service, camera, dan resource mission terkait secara idempotent. |

## 5. Alarm configuration and scheduling

| ID | Requirement | Verification |
|---|---|---|
| TR-ALM-001 | User MUST dapat create, read, edit, enable, disable, dan delete alarm yang tidak sedang menjadi instance aktif. | UI/integration test |
| TR-ALM-002 | Alarm MUST memiliki valid local time, one-time atau weekly repeat rule, tepat satu valid mission configuration, dan built-in sound sebelum dapat diaktifkan. | Unit/UI test |
| TR-ALM-003 | Draft alarm MAY disimpan tanpa scheduling permission; alarm MUST NOT menjadi enabled sampai critical scheduling capability tersedia. | Permission test |
| TR-ALM-004 | Enabled alarm MUST dijadwalkan melalui exact alarm capability ketika tersedia dan MUST menyimpan next scheduled occurrence. | Native integration test |
| TR-ALM-005 | Scheduler MUST menggunakan immutable `PendingIntent` dan stable occurrence identity. | Code/integration test |
| TR-ALM-006 | Occurrence identity MUST unik terhadap pasangan alarm dan scheduled instant sehingga callback berulang tidak membuat duplicate instance. | Concurrency test |
| TR-ALM-007 | Edit, enable, disable, atau delete MUST membatalkan pending schedule lama secara idempotent sebelum schedule baru dianggap aktif. | Integration test |
| TR-ALM-008 | Weekly recurrence MUST dihitung menggunakan local timezone aktif dan calendar semantics, bukan penambahan durasi tetap 24 jam. | DST/timezone tests |
| TR-ALM-009 | Setelah timezone/time/date change, reboot, app upgrade, atau exact-alarm access dipulihkan, scheduler MUST melakukan reconciliation terhadap seluruh alarm enabled. | Receiver/integration test |
| TR-ALM-010 | Reconciliation MUST aman dijalankan berulang dan MUST NOT membuat duplicate `PendingIntent` atau instance. | Idempotency test |
| TR-ALM-011 | One-time alarm MUST otomatis menjadi disabled setelah occurrence dibuat, tanpa membatalkan instance yang telah aktif. | Integration test |
| TR-ALM-012 | Disabled alarm MUST NOT menghasilkan occurrence baru. Race antara disable dan trigger MUST diselesaikan secara transactional berdasarkan persisted state. | Race-condition test |
| TR-ALM-013 | Home MUST menampilkan next alarm dari state scheduler/persistence yang telah direkonsiliasi, bukan hanya perhitungan UI sementara. | Integration test |
| TR-ALM-014 | Kegagalan scheduling MUST ditampilkan sebagai recoverable state dan MUST NOT dilaporkan sebagai alarm aktif yang sehat. | Failure injection |

## 6. Alarm instance and runtime state

### 6.1 Required states

```text
SCHEDULED
    -> TRIGGERED
    -> MISSION_LOCKED
    -> MISSION_IN_PROGRESS
    -> SUCCESS

MISSION_LOCKED / MISSION_IN_PROGRESS
    -> RECOVERY_REQUIRED
    -> MISSION_LOCKED / MISSION_IN_PROGRESS

MISSION_LOCKED / MISSION_IN_PROGRESS / RECOVERY_REQUIRED
    -> EMERGENCY_DISMISSED

Queued overlap:
TRIGGERED -> PENDING_ATTENTION -> MISSION_LOCKED
```

`SUCCESS`, `EMERGENCY_DISMISSED`, `FAILED`, dan `CANCELLED` adalah terminal result. Nama internal boleh berbeda selama transisi dan invariant setara.

### 6.2 Runtime requirements

| ID | Requirement | Verification |
|---|---|---|
| TR-INS-001 | Trigger receiver MUST membuat atau mengambil instance secara atomic berdasarkan occurrence identity. | Unit/concurrency test |
| TR-INS-002 | Instance MUST menyimpan snapshot scheduled time, mission type/configuration, target, dan sound yang berlaku saat trigger. | Persistence test |
| TR-INS-003 | Trigger MUST mencatat actual trigger timestamp dari wall clock dan monotonic timestamp bila tersedia untuk diagnosis drift. | Integration test |
| TR-INS-004 | Active instance MUST menjadi source of truth native/local dan MUST dapat ditemukan walaupun React Native runtime belum berjalan. | Process-death test |
| TR-INS-005 | Alarm audio MUST menggunakan alarm audio attributes, loop sampai terminal result, dan MUST memiliki maksimal satu playback stream aktif untuk aplikasi. | Native/device test |
| TR-INS-006 | Foreground service dan high-importance alarm notification MUST dimulai sesuai batas OS ketika instance triggered. | Device test |
| TR-INS-007 | Saat diizinkan OS, full-screen flow MUST membawa user ke instance aktif dari background atau lock screen. Jika tidak diizinkan, notification fallback MUST tetap membuka instance yang sama. | Permission/device matrix |
| TR-INS-008 | Active alarm UI MUST NOT menyediakan normal dismiss sebelum mission completed. System back/Home/relaunch MUST NOT mengubah result. | UI/E2E test |
| TR-INS-009 | Setelah verified target tercapai, transition ke `SUCCESS`, history write, dan stop side effects MUST dieksekusi sebagai satu idempotent completion workflow. | Failure-injection test |
| TR-INS-010 | Relaunch atau process recreation MUST memulihkan instance aktif tertua beserta persisted progress dan result yang benar. | Process-death test |
| TR-INS-011 | Bila persisted state tidak konsisten, system MUST memilih non-success safe state, menampilkan recovery, dan mempertahankan emergency dismissal. | Corruption test |
| TR-INS-012 | App MUST NOT menjanjikan atau menampilkan status seolah alarm tetap guaranteed setelah force-stop/power-off. | UX/content review |

## 7. Overlapping alarms

| ID | Requirement | Verification |
|---|---|---|
| TR-OVR-001 | Setiap occurrence valid MUST memiliki instance sendiri walaupun instance lain masih aktif. | Integration test |
| TR-OVR-002 | Hanya satu instance boleh berstatus user-attended/mission-in-progress pada satu waktu. | Concurrency test |
| TR-OVR-003 | Instance tambahan MUST masuk FIFO `PENDING_ATTENTION` berdasarkan scheduled time lalu creation sequence sebagai tie-breaker. | Unit/integration test |
| TR-OVR-004 | Overlap MUST NOT membuat audio stream bertumpuk. Audio service MAY memperbarui konteks notification tanpa restart loop yang tidak perlu. | Device test |
| TR-OVR-005 | Setelah instance aktif terminal, instance antrean tertua MUST dipromosikan dan ditampilkan tanpa kehilangan result tiap instance. | E2E test |
| TR-OVR-006 | Emergency dismissal hanya MUST mengakhiri instance yang sedang ditampilkan; queued instance tetap membutuhkan resolution tersendiri. | E2E test |

## 8. Mission engine

| ID | Requirement | Verification |
|---|---|---|
| TR-MIS-001 | Mission engine MUST menerima immutable mission snapshot dan instance ID, bukan membaca mutable alarm configuration saat runtime. | Unit test |
| TR-MIS-002 | Mission type MUST satu dari `PUSH_UP`, `MATH`, atau `QR` untuk MVP. Unknown type MUST masuk recoverable error, bukan success. | Unit/migration test |
| TR-MIS-003 | Mission progress MUST monotonic dan dibatasi `0..target`. | Property test |
| TR-MIS-004 | Progress yang telah diverifikasi MUST dipersist dan MUST bertahan setelah navigation, permission flow, temporary detection failure, atau process recreation. | E2E/process-death test |
| TR-MIS-005 | Mission completion event MUST idempotent; event berulang MUST NOT membuat duplicate history atau completion side effect. | Concurrency test |
| TR-MIS-006 | Test Mission MUST memakai verification path setara runtime tetapi MUST NOT membuat alarm instance/history dan MUST NOT mengendalikan alarm audio. | Integration test |
| TR-MIS-007 | Tidak ada mission MVP yang memiliki skip, manual-complete, atau manual progress control. | UI/code review |
| TR-MIS-008 | Verification error MUST menghasilkan actionable feedback dan recovery state dengan emergency dismissal tetap tersedia. | Failure-injection test |

## 9. Push-up verification requirements

| ID | Requirement | Verification |
|---|---|---|
| TR-PUP-001 | Push-up target MUST integer 1–50; default MUST 10. | Validation test |
| TR-PUP-002 | CameraX MUST menyediakan front-camera preview dan latest-frame-only analysis agar backlog tidak menumpuk. | Native integration test |
| TR-PUP-003 | MediaPipe Pose Landmarker MUST berjalan on-device dan MUST tetap berfungsi tanpa network. | Offline device test |
| TR-PUP-004 | Detector MUST memisahkan pose estimation dari exercise state machine sehingga landmark/model dapat diuji terpisah dari business logic. | Architecture/code test |
| TR-PUP-005 | Minimal shoulder, elbow, wrist, hip, dan ankle pada side yang dipilih MUST memenuhi visibility threshold sebelum state dapat berubah. | Dataset/unit test |
| TR-PUP-006 | Rep MUST memerlukan urutan valid top → down → top/up dengan depth dan body-alignment criteria. Posisi tunggal MUST NOT menambah rep. | State-machine test |
| TR-PUP-007 | Partial motion, low confidence, invalid alignment, missing body, duplicate frame/event, dan arm-only motion MUST NOT menambah rep. | Negative dataset test |
| TR-PUP-008 | Hysteresis/debounce/cooldown MUST mencegah satu siklus dihitung lebih dari sekali. Nilai final ditentukan pada Computer Vision Specification. | State-machine/dataset test |
| TR-PUP-009 | Temporary body loss MUST mempertahankan valid committed reps, tetapi MUST NOT mempertahankan ambiguous half-rep sebagai valid completion. | State recovery test |
| TR-PUP-010 | UI MUST memberikan feedback singkat untuk framing, distance, visibility/lighting, posture, state, dan rep progress tanpa menampilkan diagnostic confidence mentah. | UX/device test |
| TR-PUP-011 | Kamera dan inference MUST berhenti segera setelah mission terminal, emergency dismiss, atau activity benar-benar ditinggalkan tanpa active verification. | Lifecycle test |
| TR-PUP-012 | Raw frame, bitmap, landmark stream, atau biometric template MUST NOT ditulis ke persistent storage/log. | Privacy inspection |

Threshold feasibility (`elbow top 150°`, `down 95°`, visibility `0.60`, alignment `150°`) hanya seed eksperimen dan **bukan requirement produksi**. Fase 5 wajib menetapkan nilai berdasarkan dataset dan error analysis.

## 10. Math mission requirements

| ID | Requirement | Verification |
|---|---|---|
| TR-MAT-001 | Required question count MUST integer 1–10; default MUST 3. | Validation test |
| TR-MAT-002 | Operasi MVP MUST terbatas pada integer addition, subtraction, dan multiplication. | Generator test |
| TR-MAT-003 | Question set dan answer key MUST dibuat secara lokal, dikaitkan ke instance, dan dipersist sebelum pertanyaan pertama ditampilkan. | Persistence test |
| TR-MAT-004 | Recovery instance MUST menampilkan question set dan index yang sama; restart MUST NOT mereroll soal. | Process-death test |
| TR-MAT-005 | Correct answer MUST menambah progress tepat satu; wrong/invalid answer MUST mempertahankan soal dan progress. | Unit/UI test |
| TR-MAT-006 | Mission MUST complete tepat setelah seluruh required questions dijawab benar. | Unit/E2E test |
| TR-MAT-007 | Input MUST mendukung bilangan negatif bila generator menghasilkan hasil negatif dan MUST menolak format non-integer secara eksplisit. | Input test |
| TR-MAT-008 | Seed/generator version MUST disimpan agar perubahan algoritma pada app update tidak mengubah active instance. | Migration test |

## 11. QR mission requirements

| ID | Requirement | Verification |
|---|---|---|
| TR-QR-001 | QR configuration MUST mendaftarkan tepat satu reference payload sebelum alarm dapat diaktifkan. | Validation test |
| TR-QR-002 | System MUST menyimpan versioned HMAC/digest reference yang diperlukan untuk matching dan MUST NOT menyimpan foto QR. | Storage inspection |
| TR-QR-003 | Runtime scan MUST diproses lokal, dinormalisasi dengan aturan versi yang sama, lalu dibandingkan menggunakan exact digest match. | Unit/offline test |
| TR-QR-004 | Matching payload MUST menyelesaikan mission sekali; non-matching atau unreadable payload MUST NOT menambah progress. | Scanner/E2E test |
| TR-QR-005 | Camera permission/recovery MUST mengikuti instance yang sama dan MUST NOT mengganti registered reference. | Permission test |
| TR-QR-006 | Scanner MUST rate-limit duplicate decode result agar satu frame sequence tidak menghasilkan repeated completion event. | Integration test |
| TR-QR-007 | QR payload mentah MUST NOT ditulis ke diagnostic log atau history. | Privacy inspection |

## 12. Emergency dismissal

| ID | Requirement | Verification |
|---|---|---|
| TR-EMG-001 | Emergency control MUST tersedia pada active alarm, seluruh mission screen, dan recovery/error state. | UI/E2E test |
| TR-EMG-002 | Aktivasi MUST membutuhkan continuous press-and-hold 5 detik. Melepas atau kehilangan pointer sebelum selesai MUST mereset progress hold. | Interaction test |
| TR-EMG-003 | UI MUST menampilkan hold progress dan menjelaskan bahwa hasil bukan mission success. | UX/accessibility test |
| TR-EMG-004 | Setelah hold selesai, audio/foreground service MUST dihentikan segera tanpa mensyaratkan alasan tambahan. | Device latency test |
| TR-EMG-005 | Result MUST dipersist sebagai `EMERGENCY_DISMISSED` dengan timestamp dan dismiss method; verified progress terakhir MUST dipertahankan. | Persistence test |
| TR-EMG-006 | Emergency workflow MUST idempotent dan tetap berfungsi ketika verification engine/camera gagal. Critical stop path MUST NOT bergantung hanya pada JS callback. | Failure-injection test |
| TR-EMG-007 | TalkBack user MUST dapat menemukan, memahami, dan menjalankan equivalent 5-second hold action. | Accessibility test |

## 13. Permission and capability handling

| ID | Requirement | Verification |
|---|---|---|
| TR-PER-001 | Permission/capability MUST diminta just-in-time: notification/exact alarm saat aktivasi pertama; camera saat Test Mission atau camera mission pertama. | UI test |
| TR-PER-002 | App MUST membedakan `not_requested`, `granted`, `denied`, `denied_permanently/restricted`, dan special-access unavailable bila OS menyediakan distinction. | Unit/device test |
| TR-PER-003 | Denial MUST NOT crash, create success, atau menghapus active instance/progress. | Permission test |
| TR-PER-004 | Critical scheduling capability yang tidak tersedia MUST mencegah alarm menjadi enabled dan MUST menyediakan recovery route ke system settings bila tersedia. | Device test |
| TR-PER-005 | Camera denial pada active mission MUST masuk `RECOVERY_REQUIRED` dengan retry/settings route dan emergency dismissal. | E2E test |
| TR-PER-006 | Notification/full-screen denial MUST memiliki documented fallback dan MUST NOT dilaporkan sebagai full capability. | Device matrix |
| TR-PER-007 | Setelah kembali dari Settings, app MUST re-check capability; cached permission state MUST NOT dianggap authoritative. | Integration test |
| TR-PER-008 | Capability reconciliation MUST berjalan saat app foreground dan sebelum scheduling/rescheduling. | Integration test |

## 14. Local data and persistence

### 14.1 Storage requirements

| ID | Requirement | Verification |
|---|---|---|
| TR-DAT-001 | SQLite transactional database MUST menjadi durable source of truth untuk alarm configuration, schedule occurrence, active instance, mission snapshot/progress, dan history. | Integration test |
| TR-DAT-002 | Native alarm receiver/service MUST dapat membaca dan menulis critical state tanpa menunggu React Native bridge. | Process-death test |
| TR-DAT-003 | Primary identifiers MUST non-reusable UUID/ULID-equivalent; database MUST enforce uniqueness occurrence dan one-terminal-result constraints. | Schema/integration test |
| TR-DAT-004 | Timestamps MUST disimpan sebagai UTC epoch plus timezone ID/offset context yang diperlukan untuk recurrence/audit. | Unit/migration test |
| TR-DAT-005 | Critical multi-record transition MUST memakai transaction. Crash di tengah transition MUST dapat direkonsiliasi tanpa false success/duplicate instance. | Failure-injection test |
| TR-DAT-006 | Schema MUST versioned dan migration-tested dari setiap production schema version. Destructive fallback MUST NOT digunakan untuk user data. | Migration test |
| TR-DAT-007 | History MUST immutable dari product UI. Edit/delete alarm MUST NOT cascade-delete history. | UI/database test |
| TR-DAT-008 | Active-instance lookup, queue ordering, next alarm, dan recent history queries SHOULD memiliki index dan bounded query time. | Query/performance test |
| TR-DAT-009 | App data clear/uninstall MAY menghapus local data sesuai OS; produk MUST menjelaskan bahwa MVP tidak memiliki cloud recovery. | UX/content review |
| TR-DAT-010 | Backup policy MUST exclude ephemeral CV/cache data dan SHOULD exclude data yang tidak dapat dipulihkan konsisten tanpa Keystore material. | Manifest/security test |

### 14.2 Minimum persisted snapshot

Setiap instance minimal MUST menyimpan:

- Instance ID, alarm ID bila alarm masih ada, dan occurrence identity.
- Scheduled time, actual trigger time, terminal time.
- Mission type, versioned configuration, target, verified progress.
- Runtime state, result, dismiss method, recovery/error reason yang tersanitasi.
- Sound identifier dan repeat/configuration snapshot yang dibutuhkan untuk audit.
- Math generator version/seed/question state atau QR digest version sesuai mission.

## 15. Privacy and security

| ID | Requirement | Verification |
|---|---|---|
| TR-SEC-001 | App MUST menggunakan Android app sandbox dan Keystore untuk secret key material. Secret MUST NOT di-hardcode di source atau bundle. | Security review |
| TR-SEC-002 | PendingIntent MUST explicit, immutable kecuali mutability benar-benar diperlukan, dan component internal MUST non-exported secara default. | Manifest/code test |
| TR-SEC-003 | Exported receiver yang diperlukan OS MUST memvalidasi action/input dan MUST NOT menerima arbitrary mission completion dari external app. | Security test |
| TR-SEC-004 | Native module boundary MUST memvalidasi type, range, identifier, dan current state; JavaScript input MUST NOT dianggap trusted. | Unit/fuzz test |
| TR-SEC-005 | Logs MUST NOT berisi raw QR payload, camera image/landmark stream, answer key lengkap, secret, atau personally identifying free text. | Log inspection |
| TR-SEC-006 | Release build MUST disable debug menu, development server access, verbose CV logs, dan test-only alarm stop hooks. | Release inspection |
| TR-SEC-007 | Dependency and model artifact integrity MUST dapat diverifikasi melalui lockfile/checksum dalam build pipeline. | CI test |
| TR-PRV-001 | Seluruh camera/CV processing MUST on-device dan berfungsi dalam airplane mode. | Offline device test |
| TR-PRV-002 | App MUST NOT meminta location, motion/activity, contacts, microphone, storage/media, atau account permission pada MVP. | Manifest/runtime audit |
| TR-PRV-003 | Camera resource MUST aktif hanya ketika Test Mission atau Push-up/QR verification sedang digunakan. | Lifecycle test |
| TR-PRV-004 | History MUST menyimpan hasil minimum dan MUST NOT menyimpan raw sensor/camera material. | Storage inspection |

## 16. Reliability, performance, and resource targets

### 16.1 Release gates

| ID | Requirement | Target |
|---|---|---|
| TR-REL-001 | Critical workflow MUST idempotent terhadap duplicate receiver, duplicate native event, rapid tap, dan process recreation. | Zero duplicate instance/result pada test suite |
| TR-REL-002 | Alarm MUST berfungsi dengan network disabled dan app tidak berada di foreground. | 100% scenario pass pada qualification matrix |
| TR-REL-003 | Active alarm MUST recover setelah process kill yang dilakukan test harness, kecuali OS force-stop boundary. | Instance/progress pulih tanpa false success |
| TR-REL-004 | Reboot/timezone reconciliation MUST restore next schedule untuk enabled alarm. | 100% deterministic scenario pass |
| TR-REL-005 | Seluruh error path MUST menyisakan emergency dismissal atau OS-level stop fallback yang terdokumentasi. | Zero trapped-alarm scenario |

### 16.2 Provisional device targets

Target berikut berstatus `Provisional` sampai physical-device benchmark Fase 2/Fase 5 selesai:

| ID | Metric | Provisional target |
|---|---|---|
| TR-PFM-001 | Trigger drift pada reference device, normal/background/locked | p95 ≤ 2 detik dari scheduled instant |
| TR-PFM-002 | Trigger drift dalam Doze menggunakan allowed exact alarm | p95 ≤ 10 detik |
| TR-PFM-003 | Alarm audio start setelah receiver callback | p95 ≤ 3 detik |
| TR-PFM-004 | Active alarm UI usable setelah trigger atau notification tap | p95 ≤ 3 detik |
| TR-PFM-005 | Local DB critical write | p95 ≤ 100 ms pada reference device |
| TR-PFM-006 | Camera preview ready | p95 ≤ 3 detik |
| TR-PFM-007 | Pose model ready | p95 ≤ 3 detik setelah activity start |
| TR-PFM-008 | Processed pose throughput pada perangkat kelas menengah | median ≥ 15 FPS selama 2 menit |
| TR-PFM-009 | End-to-end pose feedback latency | p95 ≤ 200 ms |
| TR-PFM-010 | CV stability | Tidak crash/ANR dan tidak mencapai severe thermal state selama sesi 5 menit |

Jika OS/OEM membuat target alarm tidak realistis, hasil MUST dicatat per device dan requirement direvisi secara eksplisit; system MUST NOT menyembunyikan drift atau failed scheduling.

## 17. Accessibility and safety

| ID | Requirement | Verification |
|---|---|---|
| TR-ACC-001 | Semua interactive control MUST memiliki accessible name, role, state, dan touch target minimum 48×48 dp. | Automated/manual audit |
| TR-ACC-002 | Core flow MUST tetap dapat digunakan dengan TalkBack dan font scaling minimal 200% tanpa control penting terpotong. | Manual test |
| TR-ACC-003 | Status mission/error MUST disampaikan tidak hanya melalui warna; visual text dan audio/haptic cue SHOULD tersedia sesuai konteks. | UX test |
| TR-ACC-004 | Alarm/mission UI MUST memenuhi contrast minimum 4.5:1 untuk normal text dan 3:1 untuk large text/essential UI. | Contrast audit |
| TR-ACC-005 | Camera guidance MUST menggunakan bahasa tindakan yang singkat dan tidak mendiagnosis kondisi kesehatan. | Content review |
| TR-ACC-006 | Physical mission MUST menampilkan safety guidance saat konfigurasi/Test Mission dan emergency dismissal MUST tetap mudah ditemukan. | UX test |
| TR-ACC-007 | Haptic/audio feedback MUST menghormati capability perangkat dan MUST memiliki equivalent visual feedback. | Device test |

## 18. Diagnostics and observability

| ID | Requirement | Verification |
|---|---|---|
| TR-OBS-001 | Local diagnostic events MUST menggunakan structured event name, timestamp, instance correlation ID, app/OS version, dan sanitized reason code. | Log test |
| TR-OBS-002 | Minimal events MUST mencakup schedule requested/result, trigger received, duplicate suppressed, service/audio start result, mission state transition, verification error, recovery, dan terminal result. | Coverage test |
| TR-OBS-003 | Logging failure MUST NOT memblokir scheduling, verification, dismissal, atau persistence utama. | Failure-injection test |
| TR-OBS-004 | Diagnostic retention MUST bounded dan dapat dibersihkan; raw camera/QR content MUST NOT masuk event. | Storage/privacy test |
| TR-OBS-005 | Tidak ada remote telemetry pada MVP. Export diagnostik jika kelak dibuat MUST explicit user action dan redacted. | Product/security review |

## 19. Build and quality requirements

| ID | Requirement | Verification |
|---|---|---|
| TR-QLT-001 | CI MUST menjalankan TypeScript typecheck, ESLint tanpa error, JavaScript tests, Kotlin unit tests, Android lint, dan debug/release compilation. | CI gate |
| TR-QLT-002 | Alarm state, recurrence, idempotency, mission state machine, Math generator, QR matching, dan database migrations MUST memiliki deterministic unit tests. | Test coverage review |
| TR-QLT-003 | Native integration tests MUST mencakup receiver → instance → service/notification dan process-death recovery. | Instrumentation gate |
| TR-QLT-004 | Release candidate MUST lulus permission, offline, background, lock-screen, Doze, reboot, timezone, battery saver, dan overlap matrix. | Device qualification |
| TR-QLT-005 | CV release candidate MUST lulus dataset yang mencakup valid, partial, invalid alignment, low confidence, occlusion, varied body/camera position, dan repeated-frame cases. | CV evaluation gate |
| TR-QLT-006 | Zero known P0/P1 defect boleh terbuka pada alarm trigger, trapped alarm, false success, emergency dismissal, data corruption, atau privacy leakage. | Release review |
| TR-QLT-007 | Production release MUST ditandatangani, minified/optimized sesuai compatibility, dan tidak memuat development/test-only capability. | Release inspection |

## 20. Physical-device qualification matrix

Minimum matrix sebelum status production-ready:

| Dimension | Required coverage |
|---|---|
| Android versions | Minimum supported API, satu mid-range API modern, dan current target API |
| Device families | Minimal satu Google/reference Android dan satu OEM dengan aggressive battery policy |
| App state | Foreground, background, removed from recents, process killed oleh test harness |
| Screen/power | Screen on, locked, Doze/idle, battery saver |
| Capability | Exact alarm granted/denied/recovered; notification granted/denied; full-screen granted/denied; camera granted/denied/recovered |
| System events | Reboot, timezone/date change, app upgrade |
| Network | Connected, offline, airplane mode |
| Alarm load | Single instance, duplicate callback injection, two or more overlapping occurrences |
| Camera | Normal/poor lighting, body missing, partial framing, valid/invalid push-up, QR match/mismatch |

## 21. Traceability

| Product/business source | Covered by |
|---|---|
| PRD FR-001–FR-005; BRS ALM-001–ALM-008, TRG-001 | TR-ALM-001–014, TR-INS-001–003 |
| PRD FR-006–FR-010; BRS TRG-002–006, CMP-001–007, DIS-001–005 | TR-INV-001, TR-INS-004–012, TR-MIS-001–008 |
| BRS PHY-001–008, PUP-001–009, ACH-001–008 | TR-PUP-001–012 |
| BRS MAT-001–006 | TR-MAT-001–008 |
| BRS QR-001–005 | TR-QR-001–007 |
| PRD FR-011; BRS EMG-001–006 | TR-EMG-001–007 |
| BRS ATT-001–005, ERR-001–005 | TR-MIS-004–008, TR-INS-010–011, TR-REL-001–005 |
| BRS PER-001–005, CAM-001–005 | TR-PER-001–008, TR-PUP-010–012 |
| PRD FR-012; BRS HIS-001–006, CFG-001–004 | TR-DAT-001–010 and minimum snapshot |
| BRS OFF-001–005 | TR-PLT-004, TR-REL-002, TR-PRV-001 |
| BRS PRV-001–005 | TR-SEC-001–007, TR-PRV-001–004 |
| Fase 1 FIFO overlap decision | TR-OVR-001–006 |
| Fase 2 carry-over gates | TR-PER-006, TR-PFM-001–010, TR-QLT-004–005, Section 20 |

## 22. Acceptance gates for Fase 3

Fase 3 dapat menjadi `Accepted` jika:

1. Scope dan invariants tidak bertentangan dengan Fase 1.
2. Seluruh requirement P0 core flow memiliki ID dan verification method.
3. Minimum Android API dan provisional performance targets disetujui atau ditandai untuk revisi berbasis evidence.
4. Physical-device gaps Fase 2 tetap terlihat sebagai release qualification, bukan dianggap lulus.
5. Requirement cukup stabil untuk menjadi input System Architecture, CV Specification, Database/ERD, API Contract, UI/UX, Testing, dan Roadmap.

## 23. Accepted decision record

Seluruh keputusan berikut disetujui product owner pada 2026-08-28.

| ID | Accepted decision | Impact |
|---|---|---|
| DEC-TR-001 | Minimum Android API 24. | Device reach luas, tetapi menambah version-specific branches dan test matrix. |
| DEC-TR-002 | Target/compile baseline 36/37 untuk initial implementation. | Mengikuti spike; baseline harus ditinjau saat release sesuai store policy. |
| DEC-TR-003 | Provisional performance targets pada Section 16.2. | Menjadi benchmark awal, bukan klaim kelulusan sebelum device tests. |
| DEC-TR-004 | Reference QR disimpan sebagai versioned keyed digest/HMAC, bukan raw payload/foto. | Privasi lebih baik; app-data reset tidak dapat dipulihkan tanpa registrasi ulang. |
| DEC-TR-005 | Math question set dipersist per instance dan dapat menghasilkan integer negatif. | Recovery deterministik dan input UI harus mendukung tanda minus. |
| DEC-TR-006 | Emergency stop path memiliki native capability dan tidak bergantung hanya pada JS. | Mengurangi risiko trapped alarm saat React Native runtime gagal. |

## 24. Deferred to later phases

- Fase 4: component boundaries, thread/process ownership, data flow, dependency diagram, dan failure containment.
- Fase 5: final pose landmarks, angles, thresholds, hysteresis, dataset, accuracy metrics, dan model selection.
- Fase 6: table/column/index/migration schema final, ERD, native module method/event payloads, serta error contract.
- Fase 7: screen flow, interaction details, copy, responsive layout, dan design tokens.
- Fase 8: full test cases, fixtures, device lab execution, coverage policy, dan release test report.
- Fase 9: work breakdown, dependency order, estimates, milestones, dan release plan.
