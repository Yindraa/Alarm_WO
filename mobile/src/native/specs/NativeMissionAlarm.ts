import type {CodegenTypes, TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export type ContractInfo = Readonly<{
  contractVersion: CodegenTypes.Int32;
  minimumClientContractVersion: CodegenTypes.Int32;
  moduleName: string;
  nativeBuildVersion: string;
}>;

export type MissionConfigSnapshot = Readonly<{
  missionType: string;
  configVersion: CodegenTypes.Int32;
  target: CodegenTypes.Int32;
  pushupProfileVersion: string | null;
  mathOperationsMask: CodegenTypes.Int32 | null;
  mathGeneratorVersion: string | null;
}>;

export type AlarmSnapshot = Readonly<{
  id: string;
  revision: CodegenTypes.Int32;
  label: string;
  enabled: boolean;
  scheduleKind: string;
  localTimeMinutes: CodegenTypes.Int32;
  repeatDaysMask: CodegenTypes.Int32;
  oneTimeAtUtcMs: CodegenTypes.Double | null;
  configuredTimezoneId: string;
  soundId: string;
  mission: MissionConfigSnapshot;
  nextOccurrenceAtUtcMs: CodegenTypes.Double | null;
  scheduleHealth: string;
  scheduleErrorCode: string | null;
}>;

export type CapabilityState = Readonly<{
  capability: string;
  status: string;
  requiredForEnable: boolean;
  canRequestInApp: boolean;
  canOpenSettings: boolean;
}>;

export type CapabilitySnapshot = Readonly<{
  checkedAtMs: CodegenTypes.Double;
  androidApiLevel: CodegenTypes.Int32;
  exactAlarm: CapabilityState;
  notifications: CapabilityState;
  fullScreenIntent: CapabilityState;
  camera: CapabilityState;
}>;

export type AlarmEditorSnapshot = Readonly<{
  generatedAtMs: CodegenTypes.Double;
  isNewDraft: boolean;
  alarm: AlarmSnapshot | null;
  capabilities: CapabilitySnapshot;
  availablePushupProfileVersion: string;
  availableMathGeneratorVersion: string;
}>;

export type AlarmListItem = Readonly<{
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

export type ActiveSummary = Readonly<{
  instanceId: string;
  revision: CodegenTypes.Int32;
  state: string;
  missionType: string;
  target: CodegenTypes.Int32;
  committedProgress: CodegenTypes.Int32;
  queuedCount: CodegenTypes.Int32;
}>;

export type HistorySummary = Readonly<{
  instanceId: string;
  endedAtMs: CodegenTypes.Double;
  scheduledAtUtcMs: CodegenTypes.Double;
  missionType: string;
  target: CodegenTypes.Int32;
  finalProgress: CodegenTypes.Int32;
  result: string;
}>;

export type HomeSnapshot = Readonly<{
  generatedAtMs: CodegenTypes.Double;
  alarms: ReadonlyArray<AlarmListItem>;
  active: ActiveSummary | null;
  recentHistory: ReadonlyArray<HistorySummary>;
}>;

export type AlarmDraftInput = Readonly<{
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

export type AggregateCommandMeta = Readonly<{
  contractVersion: CodegenTypes.Int32;
  commandId: string;
  aggregateId: string;
  expectedRevision: CodegenTypes.Int32;
}>;

export type CommandAck = Readonly<{
  commandId: string;
  aggregateType: string;
  aggregateId: string;
  revision: CodegenTypes.Int32;
  appliedAtMs: CodegenTypes.Double;
  replayed: boolean;
}>;

export type SubmitMathAnswerInput = Readonly<{
  contractVersion: CodegenTypes.Int32;
  commandId: string;
  aggregateId: string;
  expectedRevision: CodegenTypes.Int32;
  questionOrdinal: CodegenTypes.Int32;
  answer: CodegenTypes.Int32;
}>;

export type AnswerOutcome = Readonly<{
  commandId: string;
  instanceId: string;
  instanceRevision: CodegenTypes.Int32;
  correct: boolean;
  committedProgress: CodegenTypes.Int32;
  completed: boolean;
  appliedAtMs: CodegenTypes.Double;
  replayed: boolean;
}>;

export type MathQuestionView = Readonly<{
  ordinal: CodegenTypes.Int32;
  total: CodegenTypes.Int32;
  operation: string;
  operandA: CodegenTypes.Int32;
  operandB: CodegenTypes.Int32;
}>;

export type ActiveRuntimeSnapshot = Readonly<{
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

export type NativeLaunchRequest = Readonly<{
  contractVersion: CodegenTypes.Int32;
  requestId: string;
  aggregateId: string;
  expectedRevision: CodegenTypes.Int32;
}>;

export type LaunchAck = Readonly<{
  requestId: string;
  sessionId: string;
  launched: boolean;
  launchType: string;
}>;

export interface Spec extends TurboModule {
  getContractInfo(): Promise<ContractInfo>;
  getHomeSnapshot(
    contractVersion: CodegenTypes.Int32,
  ): Promise<HomeSnapshot>;
  getAlarmEditorSnapshot(
    contractVersion: CodegenTypes.Int32,
    alarmId: string | null,
  ): Promise<AlarmEditorSnapshot>;
  getActiveRuntimeSnapshot(
    contractVersion: CodegenTypes.Int32,
  ): Promise<ActiveRuntimeSnapshot>;
  saveAlarmConfiguration(input: AlarmDraftInput): Promise<CommandAck>;
  enableAlarm(input: AggregateCommandMeta): Promise<CommandAck>;
  disableAlarm(input: AggregateCommandMeta): Promise<CommandAck>;
  deleteAlarm(input: AggregateCommandMeta): Promise<CommandAck>;
  startMission(input: AggregateCommandMeta): Promise<CommandAck>;
  submitMathAnswer(input: SubmitMathAnswerInput): Promise<AnswerOutcome>;
  launchActiveInstance(input: NativeLaunchRequest): Promise<LaunchAck>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeMissionAlarm');
