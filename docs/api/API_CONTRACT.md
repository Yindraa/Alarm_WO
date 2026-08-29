# Mission Alarm — Native API Contract

| Field | Value |
|---|---|
| Product | Mission Alarm |
| Document | Native API Contract |
| Version | 1.0 |
| Contract version | 1 |
| Status | Accepted |
| Scope | React Native ↔ Kotlin TurboModule; no backend API |
| Date | 2026-08-28 |
| Technical requirements | [`../requirements/TECHNICAL_REQUIREMENTS.md`](../requirements/TECHNICAL_REQUIREMENTS.md) v1.0 Accepted |
| System architecture | [`../architecture/SYSTEM_ARCHITECTURE.md`](../architecture/SYSTEM_ARCHITECTURE.md) v1.0 Accepted |
| Database design | [`../data/DATABASE_DESIGN.md`](../data/DATABASE_DESIGN.md) |

## 1. Purpose and boundary

MVP tidak memiliki backend atau REST/GraphQL API. Contract ini menetapkan satu typed Turbo Native Module yang menghubungkan React Native presentation dengan Kotlin application core.

```text
React components
    -> TypeScript contract wrapper + runtime validation
    -> Codegen TurboModule `NativeMissionAlarm`
    -> Kotlin input mapper/validator
    -> Application services/repositories
```

Contract hanya mengekspos commands, read-model queries, capability actions, native-screen launch requests, dan advisory invalidation events. Room entity, SQL, Android object, raw camera frame, landmark stream, QR payload, secret, serta completion authority tidak melewati boundary.

## 2. Contract principles

1. Kotlin selalu authoritative untuk durable state, revision, permission truth, verification, dan result.
2. Semua database query dan command bersifat asynchronous `Promise`; tidak ada synchronous DB call yang memblokir JavaScript thread.
3. Mutating command memiliki `commandId` untuk idempotency dan `expectedRevision` untuk optimistic concurrency.
4. Query mengembalikan immutable snapshot DTO, bukan live entity/reference.
5. Event hanya menginformasikan bahwa snapshot mungkin berubah; event bukan source of truth.
6. UI tidak melakukan optimistic success untuk enable, mission progress, atau terminal result.
7. Enum string yang tidak dikenal harus ditolak/ditangani sebagai incompatible contract, bukan dipetakan diam-diam.
8. Semua input dari JavaScript dianggap untrusted dan divalidasi ulang di Kotlin.

## 3. Module identity and Codegen

| Field | Value |
|---|---|
| Module name | `NativeMissionAlarm` |
| Spec file | `specs/NativeMissionAlarm.ts` |
| Codegen config name | `MissionAlarmSpec` |
| Android Java package | Product package `.bridge.codegen` |
| Registry | `TurboModuleRegistry.getEnforcing<Spec>('NativeMissionAlarm')` |
| Event mechanism | `CodegenTypes.EventEmitter<InvalidationEvent>` |

React Native Codegen menggunakan TypeScript spec untuk menghasilkan native interface. Build `generateCodegenArtifactsFromSchema` menjadi mandatory gate setiap perubahan contract.

## 4. Wire type conventions

| Domain value | Codegen type | Rule |
|---|---|---|
| ID/token/cursor/enum | `string` | Trim/format/allowlist validated natively |
| Revision/count/minute/mask | `CodegenTypes.Int32` | Range checked |
| Epoch/duration/sequence | `CodegenTypes.Double` | Must be safe nonfractional JS integer where domain expects integer |
| Boolean | `boolean` | No `0/1` on wire |
| Optional value | `T \| null` | `undefined` not used across native contract |
| Collection | `Array<T>` | Bounded size; no unbounded result |
| DTO | Object literal/type alias | No `Object`/`UnsafeObject` in public spec |
| Async result | `Promise<T>` | Rejects with stable native error code |

Public TypeScript wrapper adds string-union/branded types and validates native DTO. Raw Codegen spec may keep enum fields as `string` where Codegen compatibility requires it; application feature code must import the validated wrapper, not the raw spec.

## 5. Common DTOs

### 5.1 Command metadata and acknowledgement

```ts
type CommandMeta = Readonly<{
  contractVersion: CodegenTypes.Int32;
  commandId: string;
}>;

type AggregateCommandMeta = Readonly<{
  contractVersion: CodegenTypes.Int32;
  commandId: string;
  aggregateId: string;
  expectedRevision: CodegenTypes.Int32;
}>;

type CommandAck = Readonly<{
  commandId: string;
  aggregateType: string;
  aggregateId: string;
  revision: CodegenTypes.Int32;
  appliedAtMs: CodegenTypes.Double;
  replayed: boolean;
}>;
```

`commandId` adalah lowercase UUID v4. Ack hanya membuktikan persisted command. UI kemudian mengambil snapshot terbaru; ack bukan domain entity.

### 5.2 Contract information

```ts
type ContractInfo = Readonly<{
  contractVersion: CodegenTypes.Int32;
  minimumClientContractVersion: CodegenTypes.Int32;
  moduleName: string;
  nativeBuildVersion: string;
}>;
```

### 5.3 Mission configuration snapshot

```ts
type MissionConfigSnapshot = Readonly<{
  missionType: string;                 // PUSH_UP | MATH | QR
  configVersion: CodegenTypes.Int32;
  target: CodegenTypes.Int32;
  pushupProfileVersion: string | null;
  mathOperationsMask: CodegenTypes.Int32 | null;
  mathGeneratorVersion: string | null;
  qrRegistered: boolean;
  qrDigestVersion: string | null;
}>;
```

QR digest/key alias tidak dikirim ke JS; hanya readiness/version.

### 5.4 Alarm snapshot

```ts
type AlarmSnapshot = Readonly<{
  id: string;
  revision: CodegenTypes.Int32;
  label: string;
  enabled: boolean;
  scheduleKind: string;                // ONE_TIME | WEEKLY
  localTimeMinutes: CodegenTypes.Int32;
  repeatDaysMask: CodegenTypes.Int32;
  oneTimeAtUtcMs: CodegenTypes.Double | null;
  configuredTimezoneId: string;
  soundId: string;
  mission: MissionConfigSnapshot;
  nextOccurrenceAtUtcMs: CodegenTypes.Double | null;
  scheduleHealth: string;              // HEALTHY | PENDING | BLOCKED | FAILED | DISABLED
  scheduleErrorCode: string | null;
}>;
```

### 5.5 Capability DTOs

```ts
type CapabilityState = Readonly<{
  capability: string;
  status: string;                      // NOT_REQUESTED | GRANTED | DENIED | RESTRICTED | UNAVAILABLE
  requiredForEnable: boolean;
  canRequestInApp: boolean;
  canOpenSettings: boolean;
}>;

type CapabilitySnapshot = Readonly<{
  checkedAtMs: CodegenTypes.Double;
  androidApiLevel: CodegenTypes.Int32;
  exactAlarm: CapabilityState;
  notifications: CapabilityState;
  fullScreenIntent: CapabilityState;
  camera: CapabilityState;
}>;
```

Capability result selalu berasal dari fresh native inspection pada query/action, bukan cache JavaScript.

## 6. Query DTOs

### 6.1 Home snapshot

```ts
type AlarmListItem = Readonly<{
  id: string;
  revision: CodegenTypes.Int32;
  label: string;
  enabled: boolean;
  localTimeMinutes: CodegenTypes.Int32;
  repeatDaysMask: CodegenTypes.Int32;
  missionType: string;
  target: CodegenTypes.Int32;
  nextOccurrenceAtUtcMs: CodegenTypes.Double | null;
  scheduleHealth: string;
}>;

type ActiveSummary = Readonly<{
  instanceId: string;
  revision: CodegenTypes.Int32;
  state: string;
  missionType: string;
  target: CodegenTypes.Int32;
  committedProgress: CodegenTypes.Int32;
  queuedCount: CodegenTypes.Int32;
}>;

type HistorySummary = Readonly<{
  instanceId: string;
  endedAtMs: CodegenTypes.Double;
  scheduledAtUtcMs: CodegenTypes.Double;
  missionType: string;
  target: CodegenTypes.Int32;
  finalProgress: CodegenTypes.Int32;
  result: string;
}>;

type HomeSnapshot = Readonly<{
  generatedAtMs: CodegenTypes.Double;
  alarms: Array<AlarmListItem>;
  active: ActiveSummary | null;
  recentHistory: Array<HistorySummary>;
}>;
```

`recentHistory` dibatasi 5. Alarm list MVP dibatasi secara defensif maksimal 500 row; bila kelak terlampaui, contract version berikutnya menambahkan pagination.

### 6.2 Alarm editor snapshot

```ts
type AlarmEditorSnapshot = Readonly<{
  generatedAtMs: CodegenTypes.Double;
  isNewDraft: boolean;
  alarm: AlarmSnapshot | null;
  capabilities: CapabilitySnapshot;
  availablePushupProfileVersion: string;
  availableMathGeneratorVersion: string;
}>;
```

### 6.3 Active runtime snapshot

```ts
type MathQuestionView = Readonly<{
  ordinal: CodegenTypes.Int32;
  total: CodegenTypes.Int32;
  operation: string;
  operandA: CodegenTypes.Int32;
  operandB: CodegenTypes.Int32;
}>;

type ActiveRuntimeSnapshot = Readonly<{
  generatedAtMs: CodegenTypes.Double;
  found: boolean;
  instanceId: string | null;
  revision: CodegenTypes.Int32 | null;
  runtimeState: string | null;
  scheduledAtUtcMs: CodegenTypes.Double | null;
  actualTriggerAtMs: CodegenTypes.Double | null;
  missionType: string | null;
  target: CodegenTypes.Int32 | null;
  committedProgress: CodegenTypes.Int32 | null;
  feedbackCode: string | null;
  recoveryReasonCode: string | null;
  mathQuestion: MathQuestionView | null;
  queuedCount: CodegenTypes.Int32;
  terminalResult: string | null;
}>;
```

Tidak ada answer key, QR content/digest, raw CV confidence, angle, atau landmark.

### 6.4 History page/detail

```ts
type HistoryRecord = Readonly<{
  instanceId: string;
  scheduledAtUtcMs: CodegenTypes.Double;
  actualTriggerAtMs: CodegenTypes.Double | null;
  endedAtMs: CodegenTypes.Double;
  completionDurationMs: CodegenTypes.Double | null;
  missionType: string;
  target: CodegenTypes.Int32;
  finalProgress: CodegenTypes.Int32;
  result: string;
  dismissMethod: string;
  errorReasonCode: string | null;
  engineVersion: string;
  profileVersion: string | null;
}>;

type HistoryPage = Readonly<{
  generatedAtMs: CodegenTypes.Double;
  items: Array<HistoryRecord>;
  nextCursor: string | null;
}>;
```

Page limit valid `1..50`, default wrapper 20. Cursor opaque, versioned, base64url-safe, dan maksimal 256 karakter.

### 6.5 Scheduling health and sound catalog

```ts
type SchedulingHealth = Readonly<{
  alarmId: string;
  alarmRevision: CodegenTypes.Int32;
  enabled: boolean;
  health: string;
  nextOccurrenceAtUtcMs: CodegenTypes.Double | null;
  lastEffectStatus: string | null;
  reasonCode: string | null;
  checkedAtMs: CodegenTypes.Double;
}>;

type SoundCatalogItem = Readonly<{
  id: string;
  displayNameKey: string;
  durationMs: CodegenTypes.Double | null;
  available: boolean;
}>;
```

Sound asset path/URI tidak diekspos.

## 7. Command inputs

### 7.1 Save draft

```ts
type AlarmDraftInput = Readonly<{
  contractVersion: CodegenTypes.Int32;
  commandId: string;
  alarmId: string | null;
  expectedRevision: CodegenTypes.Int32 | null;
  label: string;
  scheduleKind: string;
  localTimeMinutes: CodegenTypes.Int32;
  repeatDaysMask: CodegenTypes.Int32;
  oneTimeAtUtcMs: CodegenTypes.Double | null;
  configuredTimezoneId: string;
  soundId: string;
  missionType: string;
  target: CodegenTypes.Int32;
  pushupProfileVersion: string | null;
  mathOperationsMask: CodegenTypes.Int32 | null;
  mathGeneratorVersion: string | null;
}>;
```

`alarmId=null` membuat draft baru. QR raw payload/digest tidak diterima; registration dilakukan native setelah alarm draft memiliki ID.

### 7.2 Aggregate command

Enable, disable, delete, start mission, retry, dan Math answer menggunakan metadata aggregate. Math answer menambah:

```ts
type SubmitMathAnswerInput = Readonly<{
  contractVersion: CodegenTypes.Int32;
  commandId: string;
  aggregateId: string;                 // instanceId
  expectedRevision: CodegenTypes.Int32;
  questionOrdinal: CodegenTypes.Int32;
  answer: CodegenTypes.Int32;
}>;
```

### 7.3 Native launch request

```ts
type NativeLaunchRequest = Readonly<{
  contractVersion: CodegenTypes.Int32;
  requestId: string;
  aggregateId: string;                 // alarmId or instanceId
  expectedRevision: CodegenTypes.Int32;
}>;

type LaunchAck = Readonly<{
  requestId: string;
  sessionId: string;
  launched: boolean;
  launchType: string;
}>;
```

Promise resolve berarti native activity launch diterima, bukan mission/registration selesai. Hasil dipersist native dan diumumkan melalui invalidation event.

`requestId` pada launch/capability action adalah process-scoped debounce/correlation token, bukan durable mutation idempotency key. Request yang sama tidak boleh membuka dua activity selama session masih dikenal; setelah process recreation UI membuat request baru dan native activity selalu membaca persisted aggregate state.

## 8. TurboModule method contract

### 8.1 Queries

| Method | Input | Success | Side effect |
|---|---|---|---|
| `getContractInfo()` | none | `ContractInfo` | None |
| `getHomeSnapshot(contractVersion)` | `Int32` | `HomeSnapshot` | None; foreground reconciliation berjalan independen |
| `getAlarmEditorSnapshot(contractVersion, alarmId)` | version + nullable ID | `AlarmEditorSnapshot` | Fresh capability inspection |
| `getActiveRuntimeSnapshot(contractVersion)` | `Int32` | `ActiveRuntimeSnapshot` | None |
| `getHistoryPage(contractVersion, cursor, limit)` | version, nullable cursor, `Int32` | `HistoryPage` | None |
| `getHistoryDetail(contractVersion, instanceId)` | version + ID | `HistoryRecord` | None |
| `getCapabilitySnapshot(contractVersion)` | `Int32` | `CapabilitySnapshot` | Reads OS state |
| `getSchedulingHealth(contractVersion, alarmId)` | version + ID | `SchedulingHealth` | Reads DB/OS-observable state |
| `getSoundCatalog(contractVersion)` | `Int32` | `Array<SoundCatalogItem>` | Verifies packaged resources |

All queries may be retried safely and never mutate business state. Reconciliation may update derived health/effects but may not fabricate user configuration/result.

### 8.2 Alarm commands

| Method | Input | Success | Notes |
|---|---|---|---|
| `saveAlarmConfiguration(input)` | `AlarmDraftInput` | `CommandAck` | Draft baru disabled; edit alarm enabled mempertahankan enabled state dan membuat reschedule effects |
| `enableAlarm(input)` | `AggregateCommandMeta` | `CommandAck` | Fresh capability check + schedule desired state |
| `disableAlarm(input)` | `AggregateCommandMeta` | `CommandAck` | Cancels future occurrences via outbox; active instance unaffected |
| `deleteAlarm(input)` | `AggregateCommandMeta` | `CommandAck` | Rejects if active; preserves history |
| `launchQrRegistration(input)` | `NativeLaunchRequest` | `LaunchAck` | No QR payload crosses bridge |
| `launchPushUpTest(input)` | `NativeLaunchRequest` | `LaunchAck` | No instance/history/audio |

### 8.3 Runtime commands

| Method | Input | Success | Notes |
|---|---|---|---|
| `startMission(input)` | instance `AggregateCommandMeta` | `CommandAck` | Native chooses Math/RN or camera activity route from snapshot |
| `submitMathAnswer(input)` | `SubmitMathAnswerInput` | `CommandAck` | Native validates question; wrong answer returns applied ack only if state records no progress? See rule below |
| `retryActiveMission(input)` | instance `AggregateCommandMeta` | `CommandAck` | New native session token; committed progress retained |
| `launchActiveInstance(input)` | instance `NativeLaunchRequest` | `LaunchAck` | Opens same validated instance |

Wrong Math answer is a valid interaction but does not mutate durable progress. It returns a nonpersisted `AnswerOutcome` rather than `CommandAck`:

```ts
type AnswerOutcome = Readonly<{
  commandId: string;
  instanceId: string;
  instanceRevision: CodegenTypes.Int32;
  correct: boolean;
  committedProgress: CodegenTypes.Int32;
  completed: boolean;
}>;
```

Correct answer uses command receipt/progress transaction. Wrong answer juga menyimpan receipt `NO_CHANGE/MATH_INCORRECT` tanpa menyimpan user answer, sehingga retry command yang sama mengembalikan outcome identik tanpa menambah progress atau answer history.

### 8.4 Capability actions

```ts
type CapabilityActionRequest = Readonly<{
  contractVersion: CodegenTypes.Int32;
  requestId: string;
  capability: string;
}>;
```

| Method | Allowed capability | Success |
|---|---|---|
| `requestCapability(input)` | `NOTIFICATIONS`, `CAMERA` when runtime request available | Fresh `CapabilitySnapshot` after prompt result |
| `openCapabilitySettings(input)` | `EXACT_ALARM`, `FULL_SCREEN_INTENT`, `NOTIFICATIONS`, `CAMERA`, `APP_DETAILS` | `LaunchAck`; state rechecked on resume/event |

Unsupported/inapplicable action rejects `CAPABILITY_ACTION_UNAVAILABLE`.

## 9. Forbidden methods and data

Contract MUST NOT expose:

- `completeMission`, `setProgress`, `incrementRep`, or arbitrary result mutation.
- Emergency dismissal command; emergency hold remains native trusted UI.
- Raw QR payload/reference digest/key alias.
- Math correct answer/seed.
- Camera image/frame/bitmap, pose landmarks, raw angles/confidence stream.
- SQL/query string, Room entity, file path, Android `Intent`/`PendingIntent`.
- Alarm audio stop command outside verified terminal/native emergency workflow.
- Debug/test-only method in release schema.

## 10. Idempotency and concurrency

### 10.1 Command ID

- Satu logical user action mendapat satu UUID v4 `commandId` yang dipertahankan saat timeout retry.
- Native menghitung canonical request hash tanpa volatile field.
- Receipt disimpan dalam transaction yang sama dengan mutation.
- Same ID + same hash/type returns `replayed=true` ack/outcome tanpa mutation.
- Same ID + different hash/type rejects `IDEMPOTENCY_KEY_REUSED`.
- Setelah retention 7 hari, old command ID tidak boleh diretry; wrapper menghasilkan ID baru hanya untuk explicit new user action.

### 10.2 Optimistic revision

- Existing aggregate mutation wajib membawa exact `expectedRevision` dari snapshot terakhir.
- Mismatch rejects `CONFLICT_REVISION`; native tidak auto-merge.
- Wrapper mengambil snapshot terbaru, lalu UI meminta user mengulang bila edit conflict memengaruhi form.
- Late camera/native engine callbacks memakai internal session/revision contract, bukan public bridge command.

### 10.3 Ordering

Promise completion order tidak dijamin lintas command. Kotlin serializes mutation per aggregate ID. UI harus await mutation yang saling bergantung dan tidak berasumsi event tiba sebelum/after Promise resolution.

## 11. Error contract

Promise rejection menggunakan stable `code`. Human-facing copy dipilih oleh UI melalui `messageKey`; native exception message/stack tidak ditampilkan.

Validated wrapper error:

```ts
type MissionAlarmError = Readonly<{
  code: string;
  messageKey: string;
  recoverable: boolean;
  recoveryAction: string | null;
  aggregateId: string | null;
  expectedRevision: number | null;
  actualRevision: number | null;
  fieldPaths: Array<string>;
  correlationId: string;
}>;
```

Raw Promise error `userInfo` divalidasi oleh wrapper sebelum menjadi type ini. Unknown/malformed error dipetakan ke `INTERNAL_CONTRACT_ERROR` dengan sanitized correlation ID.

| Code | When | Recoverability |
|---|---|---|
| `UNSUPPORTED_CONTRACT_VERSION` | Client/module version incompatible | App update/developer fix |
| `INVALID_ARGUMENT` | Malformed ID/type/range/cursor | Correct caller input |
| `VALIDATION_FAILED` | Domain field combination invalid | Show field errors |
| `NOT_FOUND` | Aggregate/history absent | Refresh/navigation fallback |
| `CONFLICT_REVISION` | Stale expected revision | Re-query snapshot |
| `IDEMPOTENCY_KEY_REUSED` | Same command ID, different request | Developer/client fix |
| `INVALID_STATE` | Command not allowed in current state | Re-query active snapshot |
| `CAPABILITY_REQUIRED` | Critical permission/special access missing | Permission education/settings |
| `CAPABILITY_ACTION_UNAVAILABLE` | OS cannot perform requested action | Alternative instructions |
| `ALARM_ACTIVE_DELETE_FORBIDDEN` | Delete would affect active instance | Resolve instance first |
| `QR_NOT_REGISTERED` | QR alarm enable without digest | Launch registration |
| `KEY_INVALIDATED` | QR HMAC key unusable | Re-register future QR; active recovery |
| `CAMERA_UNAVAILABLE` | No usable camera/session | Retry/settings/emergency active flow |
| `STORAGE_UNAVAILABLE` | DB full/I/O/locked unexpectedly | Free storage/retry; emergency safety path |
| `RATE_LIMITED` | Excessive launch/command calls | Retry after UI debounce |
| `INTERNAL_CONTRACT_ERROR` | Mapping/unknown internal failure | Safe retry + correlation ID |

Validation error must not reveal secret/internal SQL/exception detail.

## 12. Invalidation event contract

```ts
type InvalidationEvent = Readonly<{
  contractVersion: CodegenTypes.Int32;
  eventId: string;
  processSessionId: string;
  sequence: CodegenTypes.Double;
  scope: string;                       // ALARMS | ACTIVE_RUNTIME | HISTORY | CAPABILITY | SCHEDULING
  aggregateId: string | null;
  revision: CodegenTypes.Int32 | null;
  reason: string;
  occurredAtMs: CodegenTypes.Double;
}>;
```

Rules:

- Native spec property: `readonly onInvalidation: CodegenTypes.EventEmitter<InvalidationEvent>`.
- Event emitted only after durable commit atau fresh capability observation.
- Delivery best-effort/at-least-once selama JS process hidup; duplicate, loss, dan coalescing diperbolehkan.
- Sequence monotonic hanya dalam `processSessionId`; reset process menghasilkan session baru.
- Listener tidak menerapkan delta. Listener invalidates cache lalu memanggil relevant query.
- App foreground dan RN bootstrap selalu query snapshot walaupun tidak menerima event.
- Event tidak memuat raw state, error exception, QR/CV data, atau answer.

## 13. Conceptual Codegen spec

```ts
import type {TurboModule, CodegenTypes} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export interface Spec extends TurboModule {
  getContractInfo(): Promise<ContractInfo>;
  getHomeSnapshot(contractVersion: CodegenTypes.Int32): Promise<HomeSnapshot>;
  getAlarmEditorSnapshot(
    contractVersion: CodegenTypes.Int32,
    alarmId: string | null,
  ): Promise<AlarmEditorSnapshot>;
  getActiveRuntimeSnapshot(
    contractVersion: CodegenTypes.Int32,
  ): Promise<ActiveRuntimeSnapshot>;
  getHistoryPage(
    contractVersion: CodegenTypes.Int32,
    cursor: string | null,
    limit: CodegenTypes.Int32,
  ): Promise<HistoryPage>;
  getHistoryDetail(
    contractVersion: CodegenTypes.Int32,
    instanceId: string,
  ): Promise<HistoryRecord>;
  getCapabilitySnapshot(
    contractVersion: CodegenTypes.Int32,
  ): Promise<CapabilitySnapshot>;
  getSchedulingHealth(
    contractVersion: CodegenTypes.Int32,
    alarmId: string,
  ): Promise<SchedulingHealth>;
  getSoundCatalog(
    contractVersion: CodegenTypes.Int32,
  ): Promise<Array<SoundCatalogItem>>;

  saveAlarmConfiguration(input: AlarmDraftInput): Promise<CommandAck>;
  enableAlarm(input: AggregateCommandMeta): Promise<CommandAck>;
  disableAlarm(input: AggregateCommandMeta): Promise<CommandAck>;
  deleteAlarm(input: AggregateCommandMeta): Promise<CommandAck>;
  launchQrRegistration(input: NativeLaunchRequest): Promise<LaunchAck>;
  launchPushUpTest(input: NativeLaunchRequest): Promise<LaunchAck>;
  startMission(input: AggregateCommandMeta): Promise<CommandAck>;
  submitMathAnswer(input: SubmitMathAnswerInput): Promise<AnswerOutcome>;
  retryActiveMission(input: AggregateCommandMeta): Promise<CommandAck>;
  launchActiveInstance(input: NativeLaunchRequest): Promise<LaunchAck>;
  requestCapability(input: CapabilityActionRequest): Promise<CapabilitySnapshot>;
  openCapabilitySettings(input: CapabilityActionRequest): Promise<LaunchAck>;

  readonly onInvalidation: CodegenTypes.EventEmitter<InvalidationEvent>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeMissionAlarm');
```

Ini adalah normative method surface, tetapi exact syntax harus dibuktikan oleh React Native 0.87 Codegen. Penyesuaian mekanis terhadap Codegen type grammar boleh dilakukan tanpa mengubah semantic contract; perubahan semantic memerlukan contract decision/version update.

## 14. Native screen completion contracts

QR registration, Push-up Test, Push-up mission, dan QR mission adalah native activities:

- Launch request memvalidasi aggregate/revision sebelum activity dibuka.
- Activity menerima opaque native session ID, bukan trusted config dari JS.
- Activity query snapshot langsung dari repository.
- Result dipersist melalui native coordinator.
- Activity result code Android tidak menjadi completion authority.
- JS diberi invalidation event setelah commit jika hidup.
- Relaunch mengambil current persisted state, bukan mengulang result callback.

## 15. Permission action lifecycle

```text
JS requests/opens capability action
  -> Kotlin validates capability/action/API level
  -> Activity prompt or Settings intent launched
  -> Promise resolves on direct prompt result OR launch accepted
  -> onResume native re-inspects all relevant capabilities
  -> capability invalidation emitted if changed
  -> UI re-queries CapabilitySnapshot
```

Settings launch tidak boleh langsung mengembalikan `GRANTED`; hanya fresh post-resume inspection yang menentukan.

## 16. Security and privacy rules

- Module registered only within application; no exported IPC/service API.
- `getEnforcing` is used because module is mandatory for product correctness.
- Every command validates contract version, ID syntax, range, enum, revision, and current state.
- Error userInfo uses allowlisted fields and sanitized codes.
- DTO mapper has explicit allowlist; Room entities are never generically serialized.
- Event/DTO/log contains no raw QR payload/digest, Keystore alias, Math answer key, camera/CV data, or native file path.
- Release Codegen spec contains no test-only stop or state mutation method.
- Native completion/emergency paths do not accept arbitrary proof from JS.

## 17. Performance and lifecycle

- Query/mutation performs DB work off main and JS threads.
- Snapshot array/page sizes are bounded.
- Native emits coarse invalidations, not per-frame/per-landmark events.
- Repeated invalidations may be coalesced by scope while preserving eventual re-query.
- Module invalidation/React context teardown removes event listeners and cancels only JS-facing coroutine jobs; native alarm runtime continues.
- Contract wrapper uses request timeout only for UI feedback; timeout does not imply native command rollback. Retry must reuse command ID.

## 18. Contract compatibility/versioning

### 18.1 Version 1 rules

- Client sends `contractVersion=1` on every operation except `getContractInfo`.
- Native advertises minimum and current supported versions.
- Adding nullable DTO field, new query, event reason, or error code is backward-compatible if wrapper handles unknown values safely.
- Removing/renaming field, changing meaning/type, changing command semantics, or making optional field required is breaking and requires contract version 2.
- Database schema version is not exposed as API contract and may change independently.
- Native and JS ship in the same APK, tetapi explicit versioning protects OTA/bundle mismatch and migration errors.

### 18.2 Unknown values

Validated wrapper maps unknown enum to a distinct `UNKNOWN` presentation state only for non-authoritative display. Commands never send `UNKNOWN`; unknown critical state/result causes safe recovery UI and diagnostic event.

## 19. Contract test requirements

| Test | Required evidence |
|---|---|
| Codegen | `generateCodegenArtifactsFromSchema` succeeds |
| TypeScript | Strict typecheck and public wrapper contract tests pass |
| Kotlin mapping | Every DTO field/enumeration mapped explicitly |
| Round-trip | Representative command/query fixtures survive JS↔native conversion |
| Bounds/fuzz | Invalid IDs, enum, number, cursor, oversized arrays/strings rejected |
| Revision | Concurrent stale command returns `CONFLICT_REVISION` |
| Idempotency | Timeout retry with same ID applies once; changed request rejects reuse |
| Event loss | UI recovers correct state through bootstrap/re-query without events |
| Event duplicate | Repeated invalidation does not duplicate UI/domain mutation |
| Process recreation | Command committed before JS death visible after restart |
| Privacy | DTO/event/error inspection contains no forbidden data |
| Release surface | No emergency/completion/debug bypass methods |
| Version mismatch | Unsupported client returns stable error before mutation |

## 20. Traceability

| Source | Contract mechanism |
|---|---|
| Architecture Section 10 / ADR-006 | One typed facade, snapshot queries, advisory events |
| Architecture ADR-001/002/007 | Native commands/repositories; no direct completion/progress API |
| TR-SEC-004 | Native validation and error boundary |
| TR-INS-010 / TR-MIS-004 | Active snapshot recovery queries |
| TR-PER-001–008 | Capability DTO/actions and post-resume inspection |
| TR-DAT-007–008 | Immutable history cursor queries |
| TR-REL-001 | Command receipts, revision, event idempotency |
| TR-PRV-001–004 | Forbidden camera/QR/CV fields |
| DB Sections 8–10 | Command ack, snapshot read models, no entity exposure |

## 21. Accepted API decision record

Seluruh keputusan berikut disetujui product owner pada 2026-08-28.

| ID | Accepted decision | Impact/trade-off |
|---|---|---|
| API-ADR-001 | Satu required TurboModule `NativeMissionAlarm` menjadi facade seluruh native application core. | Boundary sederhana; internal Kotlin tetap modular. |
| API-ADR-002 | Semua DB-backed methods asynchronous Promise; no synchronous DB access. | Aman untuk thread/UI; setiap call membutuhkan async handling. |
| API-ADR-003 | Mutating command memakai UUID command ID + persisted 7-day receipt. | Strong retry idempotency; satu write tambahan. |
| API-ADR-004 | Existing aggregate mutation wajib optimistic `expectedRevision`; tidak ada auto-merge. | Mencegah stale overwrite; UI harus re-query conflict. |
| API-ADR-005 | Commands mengembalikan acknowledgement, sedangkan state selalu diambil lewat snapshot query. | Clear persistence semantics; beberapa flow menambah satu query. |
| API-ADR-006 | Native events hanya advisory invalidation dengan best-effort delivery. | Tahan JS/process loss; bukan delta stream real-time authority. |
| API-ADR-007 | Codegen spec memakai supported object/primitive types; validated TS wrapper menyediakan enum/domain type lebih ketat. | Kompatibel Codegen sambil menjaga app-level type safety. |
| API-ADR-008 | Native camera activities menyelesaikan registration/mission langsung ke coordinator; Activity result/JS callback bukan authority. | Reliable saat JS mati; perlu re-query/invalidation. |
| API-ADR-009 | Promise rejection memakai stable code + sanitized metadata; UI memiliki localized message mapping. | Aman dan konsisten; native tidak mengirim final user copy. |
| API-ADR-010 | Tidak ada backend/network API pada MVP; API Contract hanya in-process native boundary. | Offline/simple; future sync membutuhkan dokumen contract baru. |

## 22. Fase 6 API acceptance gates

- API-ADR-001–010 disetujui atau direvisi.
- Exact command/query/event surface tidak memberi bypass terhadap verification atau emergency hold.
- DTO tidak mengekspos entity, secret, QR payload/digest, answer, atau CV stream.
- Idempotency, revision conflict, error, event loss, compatibility, dan lifecycle semantics jelas.
- Conceptual spec terbukti dapat dikompilasi oleh React Native 0.87 Codegen saat scaffolding production dimulai.
- Database Design menyediakan semua read/write/idempotency data yang dibutuhkan contract.

## 23. Primary references

- [React Native Turbo Native Modules](https://reactnative.dev/docs/turbo-native-modules-introduction)
- [React Native Codegen](https://reactnative.dev/docs/the-new-architecture/using-codegen)
- [React Native Codegen type appendix](https://reactnative.dev/docs/appendix)
- [React Native native-module custom events](https://reactnative.dev/docs/the-new-architecture/native-modules-custom-events)
