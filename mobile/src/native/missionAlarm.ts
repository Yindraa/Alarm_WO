import NativeMissionAlarm, {
  type AggregateCommandMeta,
  type AlarmDraftInput,
  type AlarmEditorSnapshot,
  type ActiveRuntimeSnapshot,
  type AnswerOutcome,
  type CommandAck,
  type ContractInfo,
  type HomeSnapshot,
  type LaunchAck,
  type NativeLaunchRequest,
  type SubmitMathAnswerInput,
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

export async function getHomeSnapshot(): Promise<HomeSnapshot> {
  const snapshot = await NativeMissionAlarm.getHomeSnapshot(
    MISSION_ALARM_CONTRACT_VERSION,
  );
  if (snapshot.alarms.length > 500 || snapshot.recentHistory.length > 5) {
    throw new Error('INTERNAL_CONTRACT_ERROR');
  }
  for (const alarm of snapshot.alarms) {
    requireUuid(alarm.id, 'snapshot.alarms.id');
    if (
      alarm.revision < 1 ||
      alarm.localTimeMinutes < 0 ||
      alarm.localTimeMinutes > 1439 ||
      alarm.target < 1
    ) {
      throw new Error('INTERNAL_CONTRACT_ERROR');
    }
  }
  if (snapshot.active !== null) {
    requireUuid(snapshot.active.instanceId, 'snapshot.active.instanceId');
    if (snapshot.active.revision < 1 || snapshot.active.target < 1) {
      throw new Error('INTERNAL_CONTRACT_ERROR');
    }
  }
  for (const history of snapshot.recentHistory) {
    requireUuid(history.instanceId, 'snapshot.recentHistory.instanceId');
  }
  return snapshot;
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

export type AlarmAggregateCommand = Omit<
  AggregateCommandMeta,
  'contractVersion'
>;

export async function enableAlarm(
  command: AlarmAggregateCommand,
): Promise<CommandAck> {
  validateAggregateCommand(command);
  return NativeMissionAlarm.enableAlarm({
    ...command,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

export async function disableAlarm(
  command: AlarmAggregateCommand,
): Promise<CommandAck> {
  validateAggregateCommand(command);
  return NativeMissionAlarm.disableAlarm({
    ...command,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

export async function deleteAlarm(
  command: AlarmAggregateCommand,
): Promise<CommandAck> {
  validateAggregateCommand(command);
  return NativeMissionAlarm.deleteAlarm({
    ...command,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

export async function startMission(
  command: AlarmAggregateCommand,
): Promise<CommandAck> {
  validateAggregateCommand(command);
  return NativeMissionAlarm.startMission({
    ...command,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

export type SubmitMathAnswer = Omit<
  SubmitMathAnswerInput,
  'contractVersion'
>;

export async function submitMathAnswer(
  command: SubmitMathAnswer,
): Promise<AnswerOutcome> {
  validateAggregateCommand(command);
  if (
    !Number.isInteger(command.questionOrdinal) ||
    command.questionOrdinal < 0 ||
    !Number.isInteger(command.answer) ||
    command.answer < -2_147_483_648 ||
    command.answer > 2_147_483_647
  ) {
    throw new Error('INVALID_ARGUMENT');
  }
  return NativeMissionAlarm.submitMathAnswer({
    ...command,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

function validateAggregateCommand(command: AlarmAggregateCommand): void {
  requireUuid(command.commandId, 'commandId');
  requireUuid(command.aggregateId, 'aggregateId');
  if (
    !Number.isInteger(command.expectedRevision) ||
    command.expectedRevision < 1
  ) {
    throw new Error('INVALID_ARGUMENT');
  }
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

export async function getActiveRuntimeSnapshot(): Promise<ActiveRuntimeSnapshot> {
  const snapshot = await NativeMissionAlarm.getActiveRuntimeSnapshot(
    MISSION_ALARM_CONTRACT_VERSION,
  );
  if (snapshot.found) {
    if (
      snapshot.instanceId === null ||
      snapshot.revision === null ||
      snapshot.revision < 1
    ) {
      throw new Error('INTERNAL_CONTRACT_ERROR');
    }
    requireUuid(snapshot.instanceId, 'snapshot.instanceId');
  } else if (snapshot.instanceId !== null || snapshot.revision !== null) {
    throw new Error('INTERNAL_CONTRACT_ERROR');
  }
  return snapshot;
}

export type LaunchActiveInstance = Omit<NativeLaunchRequest, 'contractVersion'>;

export async function launchActiveInstance(
  request: LaunchActiveInstance,
): Promise<LaunchAck> {
  requireUuid(request.requestId, 'requestId');
  requireUuid(request.aggregateId, 'aggregateId');
  if (
    !Number.isInteger(request.expectedRevision) ||
    request.expectedRevision < 1
  ) {
    throw new Error('INVALID_ARGUMENT');
  }
  return NativeMissionAlarm.launchActiveInstance({
    ...request,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

export async function launchQrRegistration(
  request: LaunchActiveInstance,
): Promise<LaunchAck> {
  requireUuid(request.requestId, 'requestId');
  requireUuid(request.aggregateId, 'aggregateId');
  if (
    !Number.isInteger(request.expectedRevision) ||
    request.expectedRevision < 1
  ) {
    throw new Error('INVALID_ARGUMENT');
  }
  return NativeMissionAlarm.launchQrRegistration({
    ...request,
    contractVersion: MISSION_ALARM_CONTRACT_VERSION,
  });
}

function validateDraft(draft: SaveAlarmDraft): void {
  requireUuid(draft.commandId, 'commandId');
  if (draft.alarmId !== null) {
    requireUuid(draft.alarmId, 'alarmId');
  }
  const revisionValid =
    (draft.alarmId === null && draft.expectedRevision === null) ||
    (draft.alarmId !== null &&
      Number.isInteger(draft.expectedRevision) &&
      Number(draft.expectedRevision) >= 1);
  if (
    !revisionValid ||
    draft.label.trim().length < 1 ||
    draft.label.trim().length > 80
  ) {
    throw new Error('VALIDATION_FAILED');
  }
  if (
    !Number.isInteger(draft.localTimeMinutes) ||
    draft.localTimeMinutes < 0 ||
    draft.localTimeMinutes > 1439
  ) {
    throw new Error('VALIDATION_FAILED');
  }
  const weekly =
    draft.scheduleKind === 'WEEKLY' &&
    Number.isInteger(draft.repeatDaysMask) &&
    draft.repeatDaysMask >= 1 &&
    draft.repeatDaysMask <= 127 &&
    draft.oneTimeAtUtcMs === null;
  const oneTime =
    draft.scheduleKind === 'ONE_TIME' &&
    draft.repeatDaysMask === 0 &&
    draft.oneTimeAtUtcMs !== null &&
    Number.isFinite(draft.oneTimeAtUtcMs) &&
    draft.oneTimeAtUtcMs >= 0;
  if (!weekly && !oneTime) {
    throw new Error('VALIDATION_FAILED');
  }
  if (draft.configuredTimezoneId.trim() === '' || draft.soundId.trim() === '') {
    throw new Error('VALIDATION_FAILED');
  }
  const pushup =
    draft.missionType === 'PUSH_UP' &&
    draft.target >= 1 &&
    draft.target <= 50 &&
    draft.pushupProfileVersion !== null &&
    draft.pushupProfileVersion.trim() !== '' &&
    draft.mathOperationsMask === null &&
    draft.mathGeneratorVersion === null;
  const math =
    draft.missionType === 'MATH' &&
    draft.target >= 1 &&
    draft.target <= 10 &&
    draft.pushupProfileVersion === null &&
    draft.mathOperationsMask !== null &&
    Number.isInteger(draft.mathOperationsMask) &&
    draft.mathOperationsMask >= 1 &&
    draft.mathOperationsMask <= 7 &&
    draft.mathGeneratorVersion !== null &&
    draft.mathGeneratorVersion.trim() !== '';
  const qr =
    draft.missionType === 'QR' &&
    draft.target === 1 &&
    draft.pushupProfileVersion === null &&
    draft.mathOperationsMask === null &&
    draft.mathGeneratorVersion === null;
  if (!Number.isInteger(draft.target) || (!pushup && !math && !qr)) {
    throw new Error('VALIDATION_FAILED');
  }
}

function requireUuid(value: string, _field: string): void {
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      value,
    )
  ) {
    throw new Error('INVALID_ARGUMENT');
  }
}

export type {
  ActiveRuntimeSnapshot,
  AlarmEditorSnapshot,
  CommandAck,
  ContractInfo,
  HomeSnapshot,
  LaunchAck,
};
