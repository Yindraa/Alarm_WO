# Mission Alarm — Database Design & ERD

| Field | Value |
|---|---|
| Product | Mission Alarm |
| Document | Database Design & ERD |
| Version | 1.0 |
| Status | Accepted |
| Scope | Android MVP local persistence |
| Date | 2026-08-28 |
| Technical requirements | [`../requirements/TECHNICAL_REQUIREMENTS.md`](../requirements/TECHNICAL_REQUIREMENTS.md) v1.0 Accepted |
| System architecture | [`../architecture/SYSTEM_ARCHITECTURE.md`](../architecture/SYSTEM_ARCHITECTURE.md) v1.0 Accepted |
| API contract | [`../api/API_CONTRACT.md`](../api/API_CONTRACT.md) |

## 1. Purpose

Dokumen ini menetapkan canonical local data model, constraints, indexes, transaction boundaries, retention, migration, backup, dan device-protected direct-boot mirror. Room menjadi abstraction/verification layer di atas SQLite dan hanya diakses dari Kotlin native repositories.

## 2. Storage topology

Mission Alarm memakai dua database yang mempunyai tujuan berbeda:

| Store | Android storage | Authority | Content |
|---|---|---|---|
| `mission_alarm.db` | Credential-protected app storage | Canonical source of truth setelah user unlock | Alarm, mission config, occurrence, instance, progress, Math questions, history, command receipts, outbox, diagnostics |
| `mission_alarm_boot.db` | Device-protected storage | Derived mirror + pre-unlock recovery journal | Minimal upcoming schedules, built-in sound reference, mission type/target, trigger/emergency journal |

`mission_alarm_boot.db` bukan source of truth konfigurasi. Setelah `USER_UNLOCKED`, canonical database mengimpor journal secara idempotent lalu membangun ulang mirror.

Tidak ada database JavaScript, AsyncStorage authority, backend, atau cloud replica pada MVP.

## 3. Global conventions

| Concern | Convention |
|---|---|
| Identifier | Lowercase UUID v4 string, 36 characters |
| Timestamp | Signed 64-bit UTC epoch milliseconds |
| Local date | ISO `YYYY-MM-DD` only where calendar audit is required |
| Local time | Integer minute-of-day `0..1439` |
| Timezone | IANA timezone ID plus UTC offset snapshot |
| Boolean | SQLite `INTEGER` constrained to `0` or `1` |
| Enum | Uppercase `TEXT` with `CHECK` constraint |
| Revision | Positive monotonic integer, incremented on every aggregate mutation |
| JSON | Allowed only for versioned outbox payload and sanitized diagnostic metadata; not for core domain fields |
| Blob | HMAC/digest bytes only; no camera image/video/frame |
| Money/float | Not used in core schema |

Durations dihitung dari timestamps menggunakan monotonic measurement bila satu boot session tersedia, tetapi persisted user-facing duration tetap integer milliseconds dan tidak boleh negatif.

## 4. Canonical ERD

```mermaid
erDiagram
    ALARM ||--|| ALARM_MISSION_CONFIG : configures
    ALARM o|--o{ ALARM_OCCURRENCE : schedules
    ALARM_OCCURRENCE ||--o| ALARM_INSTANCE : triggers
    ALARM o|--o{ ALARM_INSTANCE : originates
    ALARM_INSTANCE ||--|| INSTANCE_MISSION : snapshots
    ALARM_INSTANCE ||--o{ MATH_QUESTION : contains
    ALARM_INSTANCE ||--o| ALARM_HISTORY : produces
    ALARM_INSTANCE o|--o{ RUNTIME_EFFECT : drives
    ALARM_OCCURRENCE o|--o{ RUNTIME_EFFECT : drives
    ALARM o|--o{ RUNTIME_EFFECT : drives
    ALARM o|--o{ COMMAND_RECEIPT : receives
    ALARM_INSTANCE o|--o{ DIAGNOSTIC_EVENT : correlates

    ALARM {
      text id PK
      integer revision
      text label
      integer enabled
      text schedule_kind
      integer local_time_minutes
      integer repeat_days_mask
      integer one_time_at_utc_ms
      text configured_timezone_id
      text sound_id
      integer created_at_ms
      integer updated_at_ms
    }

    ALARM_MISSION_CONFIG {
      text alarm_id PK_FK
      text mission_type
      integer config_version
      integer target
      text pushup_profile_version
      integer math_operations_mask
      text math_generator_version
      blob qr_reference_digest
      text qr_digest_version
      text qr_key_alias
    }

    ALARM_OCCURRENCE {
      text id PK
      text dedupe_key UK
      text alarm_id FK
      integer alarm_revision
      integer scheduled_at_utc_ms
      text scheduled_local_date
      integer scheduled_local_time_minutes
      text timezone_id
      integer utc_offset_seconds
      text state
      text last_error_code
    }

    ALARM_INSTANCE {
      text id PK
      text occurrence_id UK_FK
      text alarm_id FK
      integer revision
      text runtime_state
      integer queue_order
      integer attention_slot UK
      integer scheduled_at_utc_ms
      integer actual_trigger_at_ms
      integer terminal_at_ms
      text terminal_result
      text dismiss_method
      text error_reason_code
      text label_snapshot
      text sound_id_snapshot
    }

    INSTANCE_MISSION {
      text instance_id PK_FK
      text mission_type
      integer snapshot_version
      integer target
      integer committed_progress
      text runtime_status
      text engine_version
      text pushup_profile_version
      text math_generator_version
      blob qr_reference_digest
      text qr_digest_version
      text qr_key_alias
    }

    MATH_QUESTION {
      text instance_id PK_FK
      integer ordinal PK
      text operation
      integer operand_a
      integer operand_b
      integer correct_answer
      integer answered
      integer answered_at_ms
    }

    ALARM_HISTORY {
      text instance_id PK
      integer scheduled_at_utc_ms
      integer actual_trigger_at_ms
      integer ended_at_ms
      integer completion_duration_ms
      text mission_type
      integer target
      integer final_progress
      text result
      text dismiss_method
      text error_reason_code
      text engine_version
      text profile_version
    }

    RUNTIME_EFFECT {
      text id PK
      text effect_key UK
      text aggregate_type
      text aggregate_id
      text effect_type
      integer payload_version
      text payload_json
      text status
      integer attempt_count
      integer next_attempt_at_ms
      text lease_owner
      integer lease_until_ms
    }

    COMMAND_RECEIPT {
      text command_id PK
      text command_type
      text request_hash
      text aggregate_type
      text aggregate_id
      integer result_revision
      text status
      text outcome_code
      integer created_at_ms
      integer expires_at_ms
    }

    DIAGNOSTIC_EVENT {
      text id PK
      integer occurred_at_ms
      integer elapsed_realtime_ms
      text event_code
      text reason_code
      text alarm_id
      text occurrence_id
      text instance_id
      text mission_session_id
      text metadata_json
      integer expires_at_ms
    }
```

## 5. Canonical table specifications

### 5.1 `alarm`

| Column | Type | Null | Rules |
|---|---|---:|---|
| `id` | TEXT | No | PK, UUID v4 |
| `revision` | INTEGER | No | `>=1`; optimistic concurrency token |
| `label` | TEXT | No | Trimmed, default `Alarm`, length 1–80 |
| `enabled` | INTEGER | No | `0/1` |
| `schedule_kind` | TEXT | No | `ONE_TIME`, `WEEKLY` |
| `local_time_minutes` | INTEGER | No | `0..1439` |
| `repeat_days_mask` | INTEGER | No | 7-bit Monday-first mask `0..127` |
| `one_time_at_utc_ms` | INTEGER | Yes | Required only for `ONE_TIME` |
| `configured_timezone_id` | TEXT | No | IANA zone when configuration last saved |
| `sound_id` | TEXT | No | Stable identifier from packaged sound catalog |
| `created_at_ms` | INTEGER | No | Immutable creation timestamp |
| `updated_at_ms` | INTEGER | No | Last successful mutation timestamp |

Schedule checks:

```sql
CHECK (
  (schedule_kind = 'ONE_TIME'
    AND one_time_at_utc_ms IS NOT NULL
    AND repeat_days_mask = 0)
  OR
  (schedule_kind = 'WEEKLY'
    AND one_time_at_utc_ms IS NULL
    AND repeat_days_mask BETWEEN 1 AND 127)
)
```

MVP weekly schedule mengikuti current device local timezone. `configured_timezone_id` disimpan untuk audit dan reconciliation, bukan untuk mengunci recurrence ke zona lama.

### 5.2 `alarm_mission_config`

Tepat satu row per alarm.

| Column | Type | Null | Rules |
|---|---|---:|---|
| `alarm_id` | TEXT | No | PK, FK `alarm(id) ON DELETE CASCADE` |
| `mission_type` | TEXT | No | `PUSH_UP`, `MATH`, `QR` |
| `config_version` | INTEGER | No | `>=1` |
| `target` | INTEGER | No | Push-up `1..50`; Math `1..10`; QR `1` |
| `pushup_profile_version` | TEXT | Yes | Required for Push-up |
| `math_operations_mask` | INTEGER | Yes | Addition=1, subtraction=2, multiplication=4; nonzero subset of 7 |
| `math_generator_version` | TEXT | Yes | Required for Math |
| `qr_reference_digest` | BLOB | Yes | Required for QR; HMAC output only |
| `qr_digest_version` | TEXT | Yes | Normalization/digest contract version |
| `qr_key_alias` | TEXT | Yes | Keystore alias, not secret material |

Type-specific nullability diperiksa oleh database `CHECK` dan domain validator. Field mission lain harus `NULL`, sehingga stale configuration tidak ikut tersnapshot.

### 5.3 `alarm_occurrence`

Merepresentasikan satu planned scheduled instant dan identity OS scheduling.

| Column | Type | Null | Rules |
|---|---|---:|---|
| `id` | TEXT | No | PK |
| `dedupe_key` | TEXT | No | UNIQUE; `occ:v1:{alarmId}:{alarmRevision}:{scheduledAtUtcMs}` |
| `alarm_id` | TEXT | Yes | FK `alarm(id) ON DELETE SET NULL` |
| `alarm_revision` | INTEGER | No | Config revision yang menghasilkan occurrence |
| `scheduled_at_utc_ms` | INTEGER | No | Exact desired instant |
| `scheduled_local_date` | TEXT | No | Calendar audit snapshot |
| `scheduled_local_time_minutes` | INTEGER | No | `0..1439` |
| `timezone_id` | TEXT | No | Zone used for this occurrence |
| `utc_offset_seconds` | INTEGER | No | Offset at scheduled instant |
| `state` | TEXT | No | `PENDING_OS`, `SCHEDULED_OS`, `FIRED`, `CANCELLED`, `FAILED` |
| `last_error_code` | TEXT | Yes | Sanitized scheduling reason |
| `created_at_ms` | INTEGER | No | Creation |
| `updated_at_ms` | INTEGER | No | Last transition |

`dedupe_key`, bukan request code integer, adalah identity domain. Android `PendingIntent` menggunakan explicit component dan URI yang mengandung `occurrence.id` agar dapat direkonstruksi untuk cancel/idempotency.

### 5.4 `alarm_instance`

| Column | Type | Null | Rules |
|---|---|---:|---|
| `id` | TEXT | No | PK |
| `occurrence_id` | TEXT | No | UNIQUE FK occurrence; enforces at-most-one instance |
| `alarm_id` | TEXT | Yes | FK `alarm(id) ON DELETE SET NULL` |
| `revision` | INTEGER | No | `>=1`; every runtime mutation increments |
| `runtime_state` | TEXT | No | `TRIGGERED`, `PENDING_ATTENTION`, `MISSION_LOCKED`, `MISSION_IN_PROGRESS`, `RECOVERY_REQUIRED`, `TERMINAL` |
| `queue_order` | INTEGER | No | UNIQUE monotonic FIFO tie-breaker, generated transactionally as `max+1` under serialized instance transaction |
| `attention_slot` | INTEGER | Yes | UNIQUE; value `1` only for currently attended nonterminal instance, otherwise null |
| `scheduled_at_utc_ms` | INTEGER | No | Snapshot |
| `actual_trigger_at_ms` | INTEGER | Yes | Wall-clock callback time |
| `trigger_elapsed_realtime_ms` | INTEGER | Yes | Monotonic measurement within boot |
| `boot_session_token` | TEXT | Yes | Correlates monotonic value |
| `terminal_at_ms` | INTEGER | Yes | Required iff terminal |
| `terminal_result` | TEXT | Yes | `SUCCESS`, `EMERGENCY_DISMISSED`, `FAILED`, `CANCELLED` |
| `dismiss_method` | TEXT | Yes | `MISSION_VERIFIED`, `EMERGENCY_HOLD`, `SYSTEM_RECOVERY`, or versioned future value |
| `error_reason_code` | TEXT | Yes | Sanitized, no exception text/user content |
| `label_snapshot` | TEXT | No | Immutable display snapshot |
| `sound_id_snapshot` | TEXT | No | Immutable built-in sound snapshot |
| `created_at_ms` | INTEGER | No | Creation |
| `updated_at_ms` | INTEGER | No | Last mutation |

Terminal check:

```sql
CHECK (
  (runtime_state = 'TERMINAL'
    AND terminal_result IS NOT NULL
    AND terminal_at_ms IS NOT NULL)
  OR
  (runtime_state <> 'TERMINAL'
    AND terminal_result IS NULL
    AND terminal_at_ms IS NULL)
)
```

### 5.5 `instance_mission`

Immutable configuration snapshot + mutable committed progress.

| Column | Type | Null | Rules |
|---|---|---:|---|
| `instance_id` | TEXT | No | PK, FK instance `ON DELETE CASCADE` |
| `mission_type` | TEXT | No | `PUSH_UP`, `MATH`, `QR` |
| `snapshot_version` | INTEGER | No | Snapshot schema version |
| `target` | INTEGER | No | Positive, copied at trigger |
| `committed_progress` | INTEGER | No | `0..target`, monotonic |
| `runtime_status` | TEXT | No | `READY`, `IN_PROGRESS`, `RECOVERY_REQUIRED`, `COMPLETED` |
| `engine_version` | TEXT | No | Mission engine used by instance |
| `pushup_profile_version` | TEXT | Yes | Push-up only |
| `math_generator_version` | TEXT | Yes | Math only |
| `qr_reference_digest` | BLOB | Yes | QR only; digest snapshot, never raw payload |
| `qr_digest_version` | TEXT | Yes | QR only |
| `qr_key_alias` | TEXT | Yes | QR only |
| `updated_at_ms` | INTEGER | No | Last committed progress/state change |

No transient Push-up half-rep/filter/angle state disimpan.

### 5.6 `math_question`

| Column | Type | Null | Rules |
|---|---|---:|---|
| `instance_id` | TEXT | No | Composite PK, FK instance `ON DELETE CASCADE` |
| `ordinal` | INTEGER | No | Composite PK, zero-based, `< target` |
| `operation` | TEXT | No | `ADD`, `SUBTRACT`, `MULTIPLY` |
| `operand_a` | INTEGER | No | Generator-owned safe integer range |
| `operand_b` | INTEGER | No | Generator-owned safe integer range |
| `correct_answer` | INTEGER | No | Local validation authority |
| `answered` | INTEGER | No | `0/1` |
| `answered_at_ms` | INTEGER | Yes | Required iff answered |

Wrong answers are not persisted for MVP. Questions are generated and inserted in one transaction before Math mission becomes presentable.

### 5.7 `alarm_history`

Immutable terminal audit record, created in the same transaction that terminals an instance.

| Column | Type | Null | Rules |
|---|---|---:|---|
| `instance_id` | TEXT | No | PK, FK instance; one history per terminal instance |
| `scheduled_at_utc_ms` | INTEGER | No | Snapshot |
| `actual_trigger_at_ms` | INTEGER | Yes | Snapshot |
| `ended_at_ms` | INTEGER | No | Terminal time |
| `completion_duration_ms` | INTEGER | Yes | Nonnegative; null if trigger unavailable |
| `mission_type` | TEXT | No | Snapshot |
| `target` | INTEGER | No | Snapshot |
| `final_progress` | INTEGER | No | `0..target` |
| `result` | TEXT | No | Terminal classification |
| `dismiss_method` | TEXT | No | Terminal method |
| `error_reason_code` | TEXT | Yes | Sanitized |
| `engine_version` | TEXT | No | Audit/reproducibility |
| `profile_version` | TEXT | Yes | CV profile if relevant |
| `created_at_ms` | INTEGER | No | Same terminal transaction |

DAO tidak menyediakan update/delete. Database triggers `BEFORE UPDATE/DELETE ... RAISE(ABORT, 'history_immutable')` menjaga accidental mutation. Schema migration tetap dapat membangun tabel baru dan menyalin data melalui controlled migration.

### 5.8 `runtime_effect`

| Column | Type | Null | Rules |
|---|---|---:|---|
| `id` | TEXT | No | PK |
| `effect_key` | TEXT | No | UNIQUE deterministic idempotency key |
| `aggregate_type` | TEXT | No | `ALARM`, `OCCURRENCE`, `INSTANCE`, `SYSTEM` |
| `aggregate_id` | TEXT | No | Owning identifier |
| `effect_type` | TEXT | No | Allowlisted architecture effect type |
| `payload_version` | INTEGER | No | Decoder version |
| `payload_json` | TEXT | No | Minimal validated payload; no raw camera/QR/secret |
| `status` | TEXT | No | `PENDING`, `LEASED`, `ACKNOWLEDGED`, `RETRYABLE`, `BLOCKED_CAPABILITY`, `DEAD_LETTER` |
| `attempt_count` | INTEGER | No | `>=0` |
| `next_attempt_at_ms` | INTEGER | Yes | Retry time |
| `lease_owner` | TEXT | Yes | Worker/session token |
| `lease_until_ms` | INTEGER | Yes | Expired lease is reclaimable |
| `last_error_code` | TEXT | Yes | Sanitized |
| `created_at_ms` | INTEGER | No | Creation |
| `updated_at_ms` | INTEGER | No | Last transition |
| `acknowledged_at_ms` | INTEGER | Yes | Required iff acknowledged |

Effect payload decoder rejects unknown version/type into `DEAD_LETTER`; it never guesses schema.

### 5.9 `diagnostic_event`

| Column | Type | Null | Rules |
|---|---|---:|---|
| `id` | TEXT | No | PK |
| `occurred_at_ms` | INTEGER | No | Wall clock |
| `elapsed_realtime_ms` | INTEGER | Yes | Monotonic within boot |
| `event_code` | TEXT | No | Allowlisted code |
| `reason_code` | TEXT | Yes | Allowlisted/sanitized |
| `alarm_id` | TEXT | Yes | Correlation only; no FK requirement |
| `occurrence_id` | TEXT | Yes | Correlation |
| `instance_id` | TEXT | Yes | Correlation |
| `mission_session_id` | TEXT | Yes | Correlation |
| `metadata_json` | TEXT | Yes | Allowlisted scalar metadata, size bounded |
| `expires_at_ms` | INTEGER | No | Retention cleanup |

Logging failure tidak membatalkan core transaction kecuali minimal terminal reason yang berada di instance/history.

### 5.10 `command_receipt`

Mencegah command mutation dari React Native diterapkan dua kali setelah timeout/retry.

| Column | Type | Null | Rules |
|---|---|---:|---|
| `command_id` | TEXT | No | PK; UUID supplied once per logical command |
| `command_type` | TEXT | No | Allowlisted API command name |
| `request_hash` | TEXT | No | SHA-256 canonical non-secret request representation |
| `aggregate_type` | TEXT | No | `ALARM` atau `INSTANCE` |
| `aggregate_id` | TEXT | No | Resulting/affected aggregate ID |
| `result_revision` | INTEGER | No | Revision immediately after application |
| `status` | TEXT | No | `APPLIED`, `NO_CHANGE`; rejected commands are not receipted |
| `outcome_code` | TEXT | Yes | Minimal stable outcome needed to replay, e.g. `MATH_CORRECT`/`MATH_INCORRECT` |
| `created_at_ms` | INTEGER | No | Application time |
| `expires_at_ms` | INTEGER | No | Cleanup eligibility |

Replay dengan `command_id` dan `request_hash` yang sama mengembalikan stored acknowledgement/outcome tanpa mutation. Reuse `command_id` dengan hash/type berbeda menghasilkan `IDEMPOTENCY_KEY_REUSED` dan tidak dijalankan. Outcome tidak boleh menyimpan user answer, answer key, QR payload, atau content sensitif.

## 6. Device-protected database

### 6.1 `boot_schedule`

| Column | Type | Null | Rules |
|---|---|---:|---|
| `occurrence_id` | TEXT | No | PK; same canonical occurrence ID |
| `dedupe_key` | TEXT | No | UNIQUE |
| `scheduled_at_utc_ms` | INTEGER | No | Upcoming instant |
| `sound_id` | TEXT | No | Packaged asset identifier |
| `mission_type` | TEXT | No | For pre-unlock message only |
| `target` | INTEGER | No | For pre-unlock message only |
| `alarm_revision` | INTEGER | No | Mirror generation source |
| `mirror_revision` | INTEGER | No | Monotonic mirror revision |
| `state` | TEXT | No | `ACTIVE`, `FIRED`, `CANCELLED` |
| `updated_at_ms` | INTEGER | No | Mirror write time |

Tidak menyimpan label user, QR digest/key alias, Math question/answer, CV data, atau history.

### 6.2 `boot_journal`

| Column | Type | Null | Rules |
|---|---|---:|---|
| `id` | TEXT | No | PK |
| `idempotency_key` | TEXT | No | UNIQUE |
| `occurrence_id` | TEXT | No | Canonical occurrence reference |
| `event_type` | TEXT | No | `TRIGGERED`, `EMERGENCY_DISMISSED`, `RUNTIME_STOPPED` |
| `occurred_at_ms` | INTEGER | No | Event time |
| `sound_started_at_ms` | INTEGER | Yes | Minimal runtime audit |
| `import_state` | TEXT | No | `PENDING`, `IMPORTED`, `QUARANTINED` |
| `imported_at_ms` | INTEGER | Yes | Required iff imported |
| `reason_code` | TEXT | Yes | Sanitized |

Import uses `idempotency_key`, canonical occurrence uniqueness, dan terminal-result uniqueness. Journal is deleted only after canonical commit and mirror reconciliation are acknowledged.

## 7. Index plan

| Index | Columns/predicate | Purpose |
|---|---|---|
| `idx_alarm_enabled` | `alarm(enabled)` | Reconciliation scan |
| `idx_occurrence_due` | `alarm_occurrence(state, scheduled_at_utc_ms)` | Pending/due schedule |
| `idx_occurrence_alarm_time` | `alarm_occurrence(alarm_id, scheduled_at_utc_ms)` | Alarm schedule history/reconcile |
| `idx_instance_active_fifo` | `alarm_instance(runtime_state, scheduled_at_utc_ms, queue_order)` | Active/oldest queued lookup |
| `idx_instance_alarm` | `alarm_instance(alarm_id, created_at_ms)` | Correlation |
| `idx_history_recent` | `alarm_history(ended_at_ms DESC, instance_id)` | Cursor history pagination |
| `idx_effect_claim` | `runtime_effect(status, next_attempt_at_ms, lease_until_ms, created_at_ms)` | Worker claim |
| `idx_effect_aggregate` | `runtime_effect(aggregate_type, aggregate_id)` | Reconciliation |
| `idx_command_expiry` | `command_receipt(expires_at_ms)` | Receipt cleanup |
| `idx_command_aggregate` | `command_receipt(aggregate_type, aggregate_id, created_at_ms)` | Diagnosis/idempotency audit |
| `idx_diag_expiry` | `diagnostic_event(expires_at_ms)` | Cleanup |
| `idx_diag_instance` | `diagnostic_event(instance_id, occurred_at_ms)` | Local diagnosis |
| `idx_boot_schedule_due` | `boot_schedule(state, scheduled_at_utc_ms)` | Direct-boot reconciliation |
| `idx_boot_journal_import` | `boot_journal(import_state, occurred_at_ms)` | Unlock import |

`EXPLAIN QUERY PLAN` tests harus membuktikan active FIFO, recent history, due effects, dan due occurrence tidak melakukan full scan yang tidak perlu.

## 8. Transaction boundaries

### 8.1 Save/enable/edit alarm

```text
BEGIN
  lookup command receipt; replay or reject reused key
  validate expected alarm revision
  insert/update alarm
  replace type-safe mission config
  cancel superseded pending occurrences
  create next occurrence with unique dedupe key
  append CANCEL/SCHEDULE/SYNC_MIRROR effects
  insert command receipt with result revision
COMMIT
```

OS scheduling terjadi setelah commit melalui effect runner.

### 8.2 Trigger get-or-create

```text
BEGIN IMMEDIATE-equivalent Room transaction
  load occurrence by trusted ID
  if instance exists: return it
  reject cancelled/failed occurrence
  mark occurrence FIRED
  snapshot alarm + mission config
  insert instance and instance_mission
  generate Math questions if Math
  assign active or FIFO pending state
  disable one-time alarm / create next weekly occurrence
  append runtime/schedule/mirror effects
COMMIT
```

Unique `occurrence_id` pada instance membuat duplicate receiver aman.

### 8.3 Verified progress and success

```text
BEGIN
  load instance + mission with expected revision
  validate nonterminal/current evidence idempotency
  update committed progress
  if target reached:
    set instance TERMINAL/SUCCESS
    set mission COMPLETED
    insert immutable history
    append STOP_RUNTIME and PROMOTE_NEXT effects
COMMIT
```

Evidence idempotency key implementation ditetapkan per engine. Push-up memakai mission session + committed rep sequence; Math memakai question ordinal; QR memakai one-shot session evidence.

### 8.4 Emergency dismissal

Canonical path terminals instance and inserts history in one transaction. Jika canonical database unavailable setelah bounded retry, native controller stops runtime untuk safety dan inserts device-protected `boot_journal` record. Reconciliation later creates `EMERGENCY_DISMISSED`; it never infers `SUCCESS`.

### 8.5 Effect claim

Worker atomically changes one due effect to `LEASED` with owner and expiry. ACK/retry requires matching lease owner. Crashed worker leaves reclaimable expired lease.

## 9. State constraints and invariants

Database + application service jointly enforce:

1. One mission config per alarm.
2. One occurrence per dedupe key.
3. At most one instance per occurrence.
4. At most one nonqueued attended instance; nullable UNIQUE `attention_slot=1` enforces this without version-dependent partial-index behavior.
5. Progress cannot decrease or exceed target.
6. Mission `COMPLETED` requires progress=target.
7. Instance `SUCCESS` requires mission completed.
8. Non-success terminal result cannot be rewritten to success.
9. One immutable history row per terminal instance.
10. Alarm deletion does not delete instance/history.
11. Effect key is unique and acknowledged effect cannot return to pending.
12. Applied external command ID is unique and cannot be reused with different content.
13. Raw camera/QR payload fields do not exist in schema.

Room entity validation alone tidak cukup untuk race condition; critical invariants divalidasi ulang di dalam transaction.

## 10. Query/read-model contracts

Native repository menghasilkan read models berikut tanpa mengekspos Room entity:

| Read model | Source |
|---|---|
| Home snapshot | Enabled alarms + next pending occurrence + active instance + recent history |
| Alarm editor | Alarm + mission config + fresh capability summary |
| Active runtime | Oldest attended instance + mission snapshot/progress + queued count |
| History page | `alarm_history` ordered by `(ended_at_ms DESC, instance_id DESC)` |
| Scheduling health | Alarm, next occurrence, last schedule effect state/error |

History memakai opaque cursor yang mengenkode last `ended_at_ms` dan `instance_id`; offset pagination tidak digunakan.

## 11. Retention and cleanup

| Data | Retention |
|---|---|
| Alarm/config | Sampai user delete/app data clear |
| Active/queued instance | Sampai terminal; tidak pernah dibersihkan saat aktif |
| History | Indefinite pada MVP; user edit/delete alarm tidak memengaruhi |
| Terminal instance | Indefinite selama terkait history pada MVP |
| Math question rows | Boleh dibersihkan 30 hari setelah terminal + history verified |
| Applied command receipts | 7 hari; tidak dibersihkan selama aggregate terkait memiliki command in-flight |
| Acknowledged effects | 7 hari |
| Dead-letter effects | 30 hari atau sampai diagnostic export/review, mana lebih dulu |
| Diagnostic events | 14 hari dan maksimal 5.000 events; oldest-first eviction |
| Imported boot journal | 7 hari setelah canonical reconciliation |
| Cancelled boot schedules | Dihapus setelah mirror reconciliation acknowledged |

Cleanup memakai bounded batches dan tidak berjalan pada critical alarm trigger path.

## 12. Migration and schema lifecycle

- Canonical dan boot database memiliki independent monotonically increasing schema versions.
- Room schema JSON diekspor dan disimpan di repository.
- Setiap production version memiliki explicit forward migration; destructive fallback dilarang.
- Migration tests membuka database fixture dari setiap prior production schema dan memvalidasi invariants/queries.
- Enum addition harus backward-readable; enum removal memerlukan data migration.
- Outbox payload version decoder minimal mendukung payload yang masih pending dari versi sebelumnya.
- Direct-boot mirror boleh direbuild dari canonical DB, tetapi unimported journal tidak boleh dihapus oleh migration.
- App downgrade tidak didukung; failure harus jelas dan tidak mencoba schema destructive.

## 13. Journal mode, integrity, and performance

- Canonical Room database menggunakan WAL untuk concurrent readers dan serialized writes.
- Foreign keys diaktifkan.
- Critical transaction tidak menjalankan network, camera, audio, notification, atau React callbacks.
- Database open performs lightweight integrity/version checks; full integrity check hanya pada diagnostic/test flow.
- `PRAGMA` customization selain Room-supported configuration harus didokumentasikan dan diuji lintas API 24+.
- P95 critical write target ≤100 ms pada reference mid-range device.

## 14. Backup, encryption, and sensitive data

- MVP menetapkan Android backup disabled (`allowBackup=false` plus applicable data-extraction rules) karena tidak ada cloud backup/sync product capability.
- Database mengandalkan Android app sandbox; SQLCipher tidak ditambahkan pada MVP tanpa threat-model requirement baru.
- QR raw payload tidak disimpan. Hanya HMAC digest dan Keystore alias/version.
- Keystore key bytes tidak masuk database.
- Camera frame, image, pose landmark stream, atau biometric template tidak memiliki storage path.
- Database file tidak boleh diekspor dari production UI.
- Debug database inspection hanya pada debuggable build dan tidak boleh memuat production user data.

## 15. Failure and recovery rules

| Failure | Required behavior |
|---|---|
| Unique constraint race | Treat as idempotent lookup, not generic crash |
| Stale revision | Return `CONFLICT_REVISION` with current revision/snapshot hint |
| DB full/I/O | No false success; expose storage recovery; emergency safety journal remains available |
| Corrupt canonical DB | Do not destructive-reset automatically; safe alarm stop/recovery UI and diagnostic path |
| Keystore invalidation | QR config becomes invalid for future enable; active QR instance requires recovery/emergency |
| Pending effect from old app version | Decode by payload version or dead-letter with visible scheduling health issue |
| Boot journal import conflict | Existing canonical terminal result wins only if already valid; conflicting event quarantined and audited |
| Cleanup crash | Idempotent next run; never touches active rows |

## 16. Traceability

| Source | Database mechanism |
|---|---|
| TR-DAT-001–006 | Canonical Room schema, transactions, IDs, timestamps, migrations |
| TR-DAT-007 | Immutable `alarm_history`; no cascade from alarm |
| TR-DAT-008 | Section 7 indexes and query-plan tests |
| TR-DAT-009–010 | Backup disabled, separate derived boot/CV data policy |
| TR-INV-003–004 | Occurrence/instance/history unique constraints |
| TR-INV-007 | `instance_mission`, label/sound snapshot |
| TR-INV-009–010 | Transactional outbox and terminal cleanup effects |
| TR-OVR-001–006 | Instance FIFO state + queue order indexes |
| TR-MAT-003–008 | Persisted versioned `math_question` and generator version |
| TR-QR-002–007 | HMAC-only typed fields, no raw QR storage |
| TR-PUP-009/012 | Committed progress only; no transient/camera fields |
| ADR-002/005/008 | Native-only Room, outbox, credential/direct-boot split |

## 17. Accepted database decision record

Seluruh keputusan berikut disetujui product owner pada 2026-08-28.

| ID | Accepted decision | Impact/trade-off |
|---|---|---|
| DB-ADR-001 | Gunakan dua Room databases: canonical credential-protected dan minimal device-protected mirror/journal. | Mendukung pre-unlock reliability dengan reconciliation complexity. |
| DB-ADR-002 | Semua SQLite access native-only; RN menerima DTO snapshot melalui TurboModule. | Satu authority/writer; membutuhkan query facade. |
| DB-ADR-003 | UUID v4 text IDs dan readable deterministic occurrence dedupe key. | Debuggable dan collision-safe; storage lebih besar dari integer ID. |
| DB-ADR-004 | Domain enum disimpan sebagai constrained uppercase TEXT. | Inspectable/migration-friendly; lebih besar dari integer enum. |
| DB-ADR-005 | History adalah tabel immutable terpisah yang dibuat dalam terminal transaction. | Audit kuat; sebagian data snapshot terduplikasi. |
| DB-ADR-006 | Transactional outbox berada dalam canonical DB dengan leased idempotent effect processing. | Crash consistency kuat; memerlukan cleanup/retry state. |
| DB-ADR-007 | Core domain memakai typed columns; JSON hanya untuk versioned effect payload/diagnostic metadata. | Constraint/query lebih kuat; schema lebih eksplisit. |
| DB-ADR-008 | Android cloud/auto backup dinonaktifkan dan SQLCipher tidak digunakan pada MVP. | Konsisten dengan no-cloud MVP; data tidak pulih setelah uninstall/device loss. |
| DB-ADR-009 | Alarm config dihapus secara hard delete, sementara occurrence/instance link menjadi null dan immutable history tetap ada. | Memenuhi delete tanpa menghapus audit; snapshot harus lengkap. |
| DB-ADR-010 | Mutating TurboModule commands menggunakan persisted `command_receipt` selama tujuh hari. | Aman terhadap timeout/retry; menambah satu write dan cleanup kecil per command. |

## 18. Fase 6 database acceptance gates

- DB-ADR-001–010 disetujui atau direvisi.
- Semua state/invariant Fase 3–5 memiliki durable representation atau explicit transient policy.
- ERD, FK/delete behavior, unique constraints, indexes, and transaction boundaries tidak ambigu.
- Direct-boot mirror tidak menyimpan secret/raw mission content.
- Migration, backup, retention, and failure behavior terdefinisi.
- API Contract hanya mengekspos read model/commands, bukan entity/SQL.

## 19. Primary references

- [Android Room persistence library](https://developer.android.com/training/data-storage/room)
- [Android Room database testing and migrations](https://developer.android.com/training/data-storage/room/testing-db)
