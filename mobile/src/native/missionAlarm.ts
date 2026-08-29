import NativeMissionAlarm, {
  type AggregateCommandMeta,
  type AlarmDraftInput,
  type AlarmEditorSnapshot,
  type CommandAck,
  type ContractInfo,
} from './specs/NativeMissionAlarm';

export const MISSION_ALARM_CONTRACT_VERSION = 1;

export async function getContractInfo(): Promise<ContractInfo> {
  const info = await NativeMissionAlarm.getContractInfo();

  if (
    info.contractVersion < MISSION_ALARM_CONTRACT_VERSION ||
    info.minimumClientContractVersion > MISSION_ALARM_CONTRACT_VERSION
  ) {
    throw new Error('UNSUPPORTED_CONTRACT_VERSION');
  }

  return info;
}

export type SaveAlarmDraft = Omit<AlarmDraftInput, 'contractVersion'>;

export async function saveAlarmConfiguration(
  draft: SaveAlarmDraft,
): Promise<CommandAck> {
  validateDraft(draft);
  return NativeMissionAlarm.saveAlarmConfiguration({
    ...draft,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

export type AlarmAggregateCommand = Omit<AggregateCommandMeta, 'contractVersion'>;

export async function enableAlarm(
  command: AlarmAggregateCommand,
): Promise<CommandAck> {
  requireUuid(command.commandId, 'commandId');
  requireUuid(command.aggregateId, 'aggregateId');
  if (!Number.isInteger(command.expectedRevision) || command.expectedRevision < 1) {
    throw new Error('INVALID_ARGUMENT');
  }
  return NativeMissionAlarm.enableAlarm({
    ...command,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

export async function getAlarmEditorSnapshot(
  alarmId: string | null,
): Promise<AlarmEditorSnapshot> {
  if (alarmId !== null) {
    requireUuid(alarmId, 'alarmId');
  }
  const snapshot = await NativeMissionAlarm.getAlarmEditorSnapshot(
    MISSION_ALARM_CONTRACT_VERSION,
    alarmId,
  );
  if (snapshot.alarm !== null) {
    requireUuid(snapshot.alarm.id, 'snapshot.alarm.id');
    if (snapshot.alarm.revision < 1) {
      throw new Error('INTERNAL_CONTRACT_ERROR');
    }
  }
  return snapshot;
}

function validateDraft(draft: SaveAlarmDraft): void {
  requireUuid(draft.commandId, 'commandId');
  if (draft.alarmId !== null) {
    requireUuid(draft.alarmId, 'alarmId');
  }
  if (draft.label.trim().length < 1 || draft.label.trim().length > 80) {
    throw new Error('VALIDATION_FAILED');
  }
  if (!Number.isInteger(draft.localTimeMinutes) || draft.localTimeMinutes < 0 || draft.localTimeMinutes > 1439) {
    throw new Error('VALIDATION_FAILED');
  }
  if (!Number.isInteger(draft.target) || draft.target < 1) {
    throw new Error('VALIDATION_FAILED');
  }
}

function requireUuid(value: string, _field: string): void {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)) {
    throw new Error('INVALID_ARGUMENT');
  }
}

export type {AlarmEditorSnapshot, CommandAck, ContractInfo};
