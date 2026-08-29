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
  qrRegistered: boolean;
  qrDigestVersion: string | null;
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

export interface Spec extends TurboModule {
  getContractInfo(): Promise<ContractInfo>;
  getAlarmEditorSnapshot(
    contractVersion: CodegenTypes.Int32,
    alarmId: string | null,
  ): Promise<AlarmEditorSnapshot>;
  saveAlarmConfiguration(input: AlarmDraftInput): Promise<CommandAck>;
  enableAlarm(input: AggregateCommandMeta): Promise<CommandAck>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeMissionAlarm');
