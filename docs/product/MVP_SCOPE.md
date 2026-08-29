# Mission Alarm — MVP Scope & Product Decisions

| Field | Value |
|---|---|
| Product | Mission Alarm |
| Document | MVP Scope & Product Decisions |
| Version | 1.0 |
| Status | Accepted |
| Source | Mission Alarm PRD v1.0 dan BRS v1.0 |
| Target release | Android MVP |
| Last updated | 2026-08-28 |

## 1. Tujuan dokumen

Dokumen ini menormalkan PRD dan BRS menjadi batas MVP yang tunggal dan dapat diuji. Dokumen ini menyelesaikan konflik ruang lingkup yang ditemukan pada kedua sumber, mencatat keputusan sementara, dan menentukan syarat kelulusan Fase 1.

Dokumen ini tidak mengubah dokumen sumber. Baseline telah disetujui untuk menjadi acuan Technical Feasibility serta Technical Requirements.

## 2. Product statement

Mission Alarm adalah aplikasi alarm offline-first yang mengharuskan pengguna menyelesaikan satu misi terverifikasi sebelum alarm dapat dihentikan melalui jalur normal.

```text
Alarm dijadwalkan
    -> Alarm dipicu
    -> Alarm instance dikunci
    -> Pengguna menjalankan misi
    -> Sistem memverifikasi progress
    -> Target terpenuhi
    -> Alarm berhenti
    -> Hasil SUCCESS dicatat
```

Jika misi tidak dapat diselesaikan karena keadaan darurat atau kegagalan sistem, pengguna tetap memiliki jalur emergency dismissal. Jalur tersebut menghentikan alarm tetapi tidak menyelesaikan misi dan tidak dicatat sebagai keberhasilan.

## 3. Prinsip produk yang tidak dapat dinegosiasikan

1. Tidak ada verifikasi valid berarti tidak ada normal dismissal.
2. Emergency dismissal selalu tersedia sebagai mekanisme keselamatan dan recovery.
3. Alarm scheduling, mission execution, verification, dan history dasar tidak bergantung pada internet.
4. Physical mission tidak memiliki tombol manual untuk menambah progress atau menyelesaikan misi.
5. Raw video kamera tidak disimpan dan tidak dikirim ke server secara default.
6. Kegagalan permission, kamera, atau verification engine tidak boleh dianggap sebagai mission success.
7. Setiap alarm instance yang berakhir memiliki result yang dapat diaudit.
8. Perubahan konfigurasi tidak berlaku retroaktif pada alarm instance aktif atau history.
9. Sistem harus mengutamakan recoverability agar pengguna tidak terjebak oleh alarm yang rusak.
10. Produk hanya menjanjikan penguncian melalui alur normal aplikasi. Produk tidak mengklaim dapat mencegah force-stop, perangkat dimatikan, atau kontrol sistem operasi lain.

## 4. Baseline MVP

### 4.1 Platform

| Decision | Status | Rationale |
|---|---|---|
| Android-first | Accepted | Mengurangi risiko lintas platform dan memungkinkan validasi exact alarm, lock-screen, background execution, audio, serta CV lebih awal. |
| iOS tidak masuk release MVP pertama | Accepted | iOS tetap menjadi target produk, tetapi implementasi dimulai setelah perilaku Android stabil dan feasibility AlarmKit divalidasi. |
| Dukungan minimum OS ditentukan pada Fase 2 | Accepted | Harus mengikuti hasil feasibility dan cakupan perangkat target, bukan asumsi dokumen. |

### 4.2 Mission

MVP pertama mendukung tepat satu mission untuk setiap alarm.

| Mission | MVP | Verification |
|---|---:|---|
| Push-up | Ya | Kamera, pose estimation, dan exercise state machine on-device |
| Math challenge | Ya | Validasi jawaban lokal |
| QR code scan | Ya | Pemindaian dan pencocokan identifier lokal |
| Squat | Tidak | Iterasi setelah push-up detection stabil |
| Plank | Tidak | Iterasi setelah push-up detection stabil |
| Shake, location, custom mission | Tidak | Post-MVP |
| Multiple missions per alarm | Tidak | Post-MVP |

Pemilihan tiga mission menunjukkan tiga mekanisme verifikasi berbeda tanpa memperluas CV ke beberapa gerakan sebelum fondasinya stabil.

### 4.3 Alarm management

MVP mencakup:

- Membuat alarm.
- Mengedit alarm.
- Menghapus alarm yang tidak sedang aktif berbunyi.
- Mengaktifkan dan menonaktifkan alarm.
- Memilih waktu lokal.
- One-time alarm.
- Repeat berdasarkan hari dalam minggu.
- Memilih satu mission dan mengatur targetnya.
- Memilih satu suara dari daftar bawaan aplikasi.
- Menampilkan next alarm.
- Mencegah duplicate trigger untuk alarm instance yang sama.
- Menjadwalkan ulang alarm setelah perubahan konfigurasi, permission kembali tersedia, atau perangkat reboot jika didukung OS.

MVP tidak mencakup:

- Alarm berbasis kalender atau tanggal kompleks.
- Sinkronisasi alarm antardevice.
- Download/custom audio dari file pengguna.
- Smartwatch atau smart-home integration.
- Alarm berbasis lokasi.

### 4.4 Snooze

Snooze tidak tersedia pada MVP pertama. Keputusan ini menjaga prinsip produk dan menghindari state partial-mission tambahan sebelum core alarm terbukti reliable.

Mission Snooze tetap menjadi kandidat post-MVP dan tidak boleh diimplementasikan sebagai snooze tanpa syarat.

### 4.5 History

History lokal MVP menyimpan:

- Alarm instance ID.
- Scheduled time.
- Actual trigger time jika tersedia.
- End/completion time.
- Snapshot mission type dan target.
- Verified progress akhir.
- Completion duration.
- Result: `SUCCESS`, `EMERGENCY_DISMISSED`, `FAILED`, atau `CANCELLED` bila relevan.
- Dismiss method.
- Error/recovery reason yang tidak mengandung raw camera data.

History tidak dapat diedit oleh pengguna pada MVP. Menghapus atau mengubah alarm tidak menghapus history terkait.

### 4.6 Account, backend, dan analytics

MVP tidak memiliki akun, authentication, cloud backup, atau synchronization. Semua data inti disimpan lokal.

Analytics pihak ketiga bukan kebutuhan MVP. Logging diagnostik awal harus bersifat lokal, minimal, dan tidak menyimpan frame atau video kamera.

### 4.7 Gamification dan statistics

Tidak termasuk MVP:

- Streak.
- XP.
- Achievement.
- Badge.
- Advanced statistics.
- Leaderboard.

Home MVP hanya menampilkan next alarm, daftar alarm, dan recent history. Data model boleh disiapkan agar statistik dapat dihitung kemudian, tetapi UI dan aturan gamification tidak menjadi Definition of Done.

## 5. Mission rules

### 5.1 Push-up

Baseline flow:

```text
CAMERA_SETUP
    -> READY
    -> TOP_POSITION
    -> DOWN_POSITION
    -> UP_POSITION
    -> VALID_REP
```

Aturan MVP:

- Target default: 10 repetisi.
- Guardrail target sementara: 1–50 repetisi; angka final divalidasi pada fase CV/UX.
- Hanya urutan posisi valid yang menambah counter.
- Gerakan parsial, landmark tidak memadai, atau body alignment tidak valid tidak dihitung.
- Satu siklus gerakan hanya dapat dihitung satu kali.
- Counter tidak dapat diedit manual.
- UI memberikan feedback singkat untuk posisi, framing, cahaya, dan kualitas deteksi.
- Mencapai target langsung menyelesaikan mission dan memulai normal dismissal.
- Test Mission tersedia saat konfigurasi, tetapi tidak menghasilkan history atau alarm result.

Threshold sudut, confidence, hysteresis, cooldown, dan toleransi postur tidak ditentukan pada Fase 1. Nilainya harus berasal dari CV spike dan Computer Vision Specification.

### 5.2 Math challenge

Aturan MVP:

- Target default: 3 soal.
- Guardrail jumlah soal sementara: 1–10.
- Operasi awal: penjumlahan, pengurangan, dan perkalian bilangan bulat.
- Pembagian dan ekspresi bertingkat tidak masuk MVP awal.
- Jawaban divalidasi secara lokal.
- Jawaban salah tidak menambah progress dan tidak mengganti soal.
- Tidak ada tombol skip atau bypass.
- Mission selesai setelah seluruh soal dijawab benar.
- Soal untuk alarm instance harus dibuat dan disimpan secara deterministik agar recovery tidak menghasilkan set soal baru secara diam-diam.

### 5.3 QR code

Aturan MVP:

- Pengguna mendaftarkan satu reference QR saat mengonfigurasi mission.
- Sistem menyimpan identifier/reference yang diperlukan, bukan foto QR.
- Reference harus tersedia secara lokal sebelum alarm dapat diaktifkan.
- Hanya identifier yang cocok yang menyelesaikan mission.
- QR yang salah menampilkan feedback dan tidak menambah progress.
- QR mission selesai dalam satu scan valid.
- QR tidak dapat ditandai selesai secara manual.

## 6. Emergency dismissal

Baseline yang diajukan:

- Emergency dismissal tersedia dari active alarm dan mission screen.
- Aksesnya terlihat tetapi tidak menjadi tombol aksi utama.
- Pengguna harus menekan dan menahan kontrol selama 5 detik.
- UI menjelaskan bahwa tindakan menghentikan alarm tanpa menyelesaikan mission.
- Alarm harus berhenti segera setelah hold berhasil; alasan tidak boleh menjadi syarat.
- Hasil dicatat sebagai `EMERGENCY_DISMISSED`.
- Emergency dismissal tidak menghasilkan reward atau success.
- Mekanisme tetap tersedia ketika kamera, permission, atau verification engine gagal.

Durasi 5 detik adalah baseline **Accepted** dan tetap harus diuji pada usability testing. Tujuannya mengurangi aktivasi tidak sengaja, bukan menyembunyikan jalur keselamatan.

## 7. Retry, recovery, dan progress

Keputusan baseline:

- Temporary detection failure tidak menghapus progress valid.
- Keluar dari mission screen tidak menyelesaikan atau membatalkan mission.
- Membuka kembali aplikasi mencoba memulihkan alarm instance aktif.
- Permission prompt tidak membuat alarm instance baru.
- Setelah permission dipulihkan, pengguna melanjutkan instance yang sama.
- Retry setelah body-not-detected atau camera setup failure mempertahankan progress valid.
- Restart manual seluruh mission tidak disediakan pada MVP.
- Jika state tidak dapat dipulihkan dengan aman, sistem kembali ke state mission-locked dengan progress tersimpan terakhir dan tetap menyediakan emergency dismissal.
- Verification engine error tidak boleh menghasilkan success.

## 8. Overlapping alarm policy

Kasus alarm kedua terpicu ketika alarm pertama masih aktif belum dijelaskan oleh PRD/BRS. Baseline yang diajukan:

1. Setiap jadwal tetap menghasilkan alarm instance tersendiri.
2. Hanya satu mission dapat tampil aktif pada satu waktu.
3. Instance berikutnya masuk antrean FIFO dengan status internal `PENDING_ATTENTION`.
4. Audio alarm tetap aktif tanpa menumpuk beberapa audio stream.
5. Setelah instance aktif berakhir, pengguna diarahkan ke instance tertua berikutnya.
6. Setiap instance tetap memiliki result sendiri.

Kebijakan ini berstatus **Accepted** dan harus divalidasi pada Fase 2 karena berpengaruh pada scheduler, persistence, dan UX.

## 9. Permission scope

Permission diminta just-in-time, bukan seluruhnya sekaligus.

| Permission/capability | Waktu permintaan |
|---|---|
| Notification | Saat onboarding/alarm pertama sebelum aktivasi |
| Exact alarm/special access | Saat alarm pertama akan diaktifkan |
| Camera | Saat Test Mission atau mission Push-up/QR pertama digunakan |
| Foreground service/full-screen capability | Dijelaskan saat alarm pertama diaktifkan sesuai kebutuhan OS |
| Motion/activity | Tidak diminta pada MVP jika tidak digunakan |
| Location | Tidak diminta pada MVP |

Alarm tidak boleh dapat diaktifkan bila permission kritis untuk scheduling belum tersedia. Mission configuration tetap dapat disimpan sebagai draft.

## 10. Screen scope

Screen MVP:

1. Launch dan active-instance recovery.
2. Onboarding singkat.
3. Permission education/recovery.
4. Home dan alarm list.
5. Create/edit alarm.
6. Mission selection.
7. Push-up configuration dan Test Mission.
8. Math configuration.
9. QR registration/configuration.
10. Active alarm.
11. Camera setup dan push-up mission.
12. Math mission.
13. QR mission.
14. Mission completion.
15. Emergency dismissal confirmation/hold state.
16. History list dan detail.
17. Settings dasar.

Tidak termasuk screen account, social, leaderboard, achievement, subscription, cloud sync, atau advanced statistics.

## 11. Result classification

| Result | Kondisi |
|---|---|
| `SUCCESS` | Mission mencapai seluruh target melalui verification yang valid dan alarm berhenti melalui normal flow. |
| `EMERGENCY_DISMISSED` | Pengguna menggunakan emergency mechanism sebelum mission selesai. |
| `FAILED` | Instance berakhir tanpa completion sesuai failure policy yang kelak didefinisikan secara eksplisit. Timeout tidak otomatis berarti success. |
| `CANCELLED` | Instance/jadwal dibatalkan melalui tindakan yang memang diizinkan sebelum trigger atau kebijakan sistem yang eksplisit. |

MVP tidak menyediakan normal cancellation untuk alarm yang sudah aktif berbunyi.

## 12. In scope

- Android application.
- Local alarm scheduling.
- One-time dan weekly repeat alarm.
- Alarm create, edit, delete, enable, disable.
- Satu mission per alarm.
- Push-up, Math, dan QR mission.
- On-device verification.
- Offline operation.
- Built-in alarm sounds.
- Active instance persistence dan recovery.
- Emergency dismissal.
- Local history.
- Permission education dan recovery.
- Basic accessibility: ukuran teks, kontras, label, serta feedback visual dan audio.
- Basic privacy controls dan no raw camera storage.

## 13. Out of scope

- iOS release pada MVP pertama.
- Squat dan Plank.
- Multiple missions, random mission, atau mission sequence.
- Snooze dan Mission Snooze.
- User account dan authentication.
- Backend, cloud backup, atau synchronization.
- Social, leaderboard, sharing, dan marketplace.
- Statistics dashboard, streak, XP, dan achievements.
- Custom downloadable sounds.
- Wearable, health platform, smart-home, dan location mission.
- Sleep tracking dan diagnosis kesehatan.
- Raw camera recording atau remote CV inference.
- User-created/custom verification logic.
- Monetization dan subscription.

## 14. MVP acceptance criteria

### 14.1 Alarm management

- **AC-ALM-001** User dapat membuat alarm valid dengan waktu, schedule, suara, dan tepat satu mission.
- **AC-ALM-002** Konfigurasi yang tidak lengkap tidak dapat diaktifkan.
- **AC-ALM-003** User dapat mengedit, enable, disable, dan menghapus alarm nonaktif.
- **AC-ALM-004** Perubahan konfigurasi berlaku pada instance berikutnya dan tidak mengubah instance aktif.
- **AC-ALM-005** Satu jadwal hanya menghasilkan satu alarm instance.
- **AC-ALM-006** Alarm terjadwal dapat dipicu tanpa koneksi internet.
- **AC-ALM-007** Repeat alarm dijadwalkan kembali sesuai hari yang dipilih.

### 14.2 Alarm lock dan dismissal

- **AC-DIS-001** Normal dismiss tidak tersedia sebelum mission completed.
- **AC-DIS-002** Keluar dan membuka kembali aplikasi tidak menandai mission completed.
- **AC-DIS-003** Mission completion menghentikan alarm melalui normal flow.
- **AC-DIS-004** Emergency dismissal dapat menghentikan alarm dari setiap recoverable mission state.
- **AC-DIS-005** Emergency dismissal dicatat berbeda dari success.

### 14.3 Push-up

- **AC-PUP-001** Counter hanya bertambah setelah urutan gerakan valid.
- **AC-PUP-002** Gerakan parsial tidak menambah counter.
- **AC-PUP-003** Event gerakan tunggal tidak dihitung lebih dari sekali.
- **AC-PUP-004** Low-confidence atau body-not-detected tidak menambah counter.
- **AC-PUP-005** User menerima feedback yang dapat ditindaklanjuti ketika framing atau pose tidak valid.
- **AC-PUP-006** Target valid terakhir langsung menyelesaikan mission.
- **AC-PUP-007** Tidak tersedia input manual untuk counter atau completion.

Target akurasi numerik akan ditambahkan setelah CV feasibility spike.

### 14.4 Math

- **AC-MAT-001** Jumlah soal sesuai konfigurasi alarm instance.
- **AC-MAT-002** Jawaban benar menambah progress tepat satu kali.
- **AC-MAT-003** Jawaban salah tidak menambah progress.
- **AC-MAT-004** Tidak tersedia skip/bypass.
- **AC-MAT-005** Set soal dapat dipulihkan setelah app restart.
- **AC-MAT-006** Seluruh soal harus benar sebelum mission completed.

### 14.5 QR

- **AC-QR-001** Alarm dengan QR mission tidak dapat diaktifkan tanpa reference valid.
- **AC-QR-002** QR yang cocok menyelesaikan mission.
- **AC-QR-003** QR yang tidak cocok tidak menyelesaikan mission.
- **AC-QR-004** Verification dapat berjalan tanpa internet.
- **AC-QR-005** Tidak tersedia manual completion.

### 14.6 Recovery, history, dan privacy

- **AC-REC-001** Permission denial ditangani tanpa crash dan tanpa false completion.
- **AC-REC-002** Temporary camera/detection failure dapat dipulihkan dalam instance yang sama.
- **AC-REC-003** Progress valid tidak hilang akibat temporary detection failure.
- **AC-REC-004** Aplikasi mencoba memulihkan active instance setelah process restart.
- **AC-HIS-001** Setiap instance yang berakhir menyimpan result dan snapshot mission.
- **AC-HIS-002** Perubahan atau penghapusan alarm tidak mengubah history lama.
- **AC-PRV-001** Tidak ada raw frame atau video kamera yang disimpan atau dikirim secara default.

## 15. Definition of Done — Fase 1

Fase 1 selesai ketika:

- Platform MVP disepakati.
- Daftar mission MVP disepakati.
- Keputusan satu mission per alarm disepakati.
- Snooze policy disepakati.
- Emergency dismissal baseline disepakati.
- Overlapping alarm policy disepakati atau diberi keputusan alternatif.
- In-scope dan out-of-scope disetujui.
- Acceptance criteria produk disetujui.
- Tidak ada konflik scope yang belum tercatat antara PRD, BRS, dan dokumen ini.

## 16. Approval checklist

Keputusan berikut membutuhkan persetujuan product owner:

- [x] Android-first; iOS setelah Android MVP stabil.
- [x] MVP missions: Push-up, Math, dan QR.
- [x] Satu mission per alarm.
- [x] Tanpa snooze pada MVP.
- [x] Tanpa akun/backend, gamification, streak, atau statistics dashboard.
- [x] Emergency dismiss menggunakan hold selama 5 detik.
- [x] Valid progress dipertahankan saat temporary failure/retry.
- [x] Alarm yang overlap menggunakan antrean FIFO dan satu audio stream.
- [x] Guardrail sementara Push-up 1–50 dan Math 1–10 soal.
- [x] Seluruh acceptance criteria pada Bagian 14 diterima sebagai baseline.

## 17. Dampak terhadap PRD/BRS

Dokumen ini menormalkan konflik berikut:

| Area | PRD/BRS sebelumnya | Baseline MVP |
|---|---|---|
| Platform | Android dan iOS; DoD hanya menyebut basic Android testing | Android-first |
| Mission | Lima mission disebut MVP; multiple types juga ditempatkan P1 | Push-up, Math, QR |
| Squat/Plank | Disebut MVP | Post-MVP setelah push-up stabil |
| Snooze | No Snooze atau Mission Snooze belum dipilih | No Snooze |
| Statistics | Disebut P1/MVP dan juga roadmap v1.1 | Post-MVP |
| Streak | Muncul pada dashboard tetapi dikategorikan P2/post-MVP | Post-MVP |
| Difficulty | Muncul pada configuration namun terkait gamification lanjutan | Tidak masuk MVP UI |
| Backend | Opsional | Tidak digunakan pada MVP |
| Mission count | Minimal satu, future multiple-mode | Tepat satu per alarm |

Business rules inti BRS tetap berlaku. Jika terdapat konflik antara baseline yang telah disetujui dan contoh UI/fitur pada PRD v1.0, baseline MVP ini menjadi acuan release pertama tanpa mengubah visi produk jangka panjang.
