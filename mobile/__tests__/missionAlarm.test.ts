import NativeMissionAlarm from '../src/native/specs/NativeMissionAlarm';
import {
  deleteAlarm,
  disableAlarm,
  enableAlarm,
  getActiveRuntimeSnapshot,
  getAlarmEditorSnapshot,
  getHomeSnapshot,
  launchActiveInstance,
  MISSION_ALARM_CONTRACT_VERSION,
  saveAlarmConfiguration,
  type SaveAlarmDraft,
} from '../src/native/missionAlarm';

jest.mock('../src/native/specs/NativeMissionAlarm', () => ({
  __esModule: true,
  default: {
    getContractInfo: jest.fn(),
    getAlarmEditorSnapshot: jest.fn(),
    getHomeSnapshot: jest.fn(),
    getActiveRuntimeSnapshot: jest.fn(),
    saveAlarmConfiguration: jest.fn(),
    enableAlarm: jest.fn(),
    disableAlarm: jest.fn(),
    deleteAlarm: jest.fn(),
    launchActiveInstance: jest.fn(),
  },
}));

const native = jest.mocked(NativeMissionAlarm);

describe('mission alarm native wrapper', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('adds the contract version before saving a validated draft', async () => {
    native.saveAlarmConfiguration.mockResolvedValue({
      commandId: COMMAND_ID,
      aggregateType: 'ALARM',
      aggregateId: ALARM_ID,
      revision: 1,
      appliedAtMs: 1000,
      replayed: false,
    });

    const result = await saveAlarmConfiguration(validDraft());

    expect(result.aggregateId).toBe(ALARM_ID);
    expect(native.saveAlarmConfiguration).toHaveBeenCalledWith({
      ...validDraft(),
      contractVersion: MISSION_ALARM_CONTRACT_VERSION,
    });
  });

  it('rejects malformed identifiers before calling native', async () => {
    await expect(
      saveAlarmConfiguration({ ...validDraft(), commandId: 'not-a-uuid' }),
    ).rejects.toThrow('INVALID_ARGUMENT');
    expect(native.saveAlarmConfiguration).not.toHaveBeenCalled();
  });

  it('rejects inconsistent schedule and mission configurations before native', async () => {
    await expect(
      saveAlarmConfiguration({
        ...validDraft(),
        scheduleKind: 'WEEKLY',
        repeatDaysMask: 0,
      }),
    ).rejects.toThrow('VALIDATION_FAILED');
    await expect(
      saveAlarmConfiguration({
        ...validDraft(),
        missionType: 'QR',
        target: 3,
      }),
    ).rejects.toThrow('VALIDATION_FAILED');
    expect(native.saveAlarmConfiguration).not.toHaveBeenCalled();
  });

  it('queries an editor snapshot with the current contract version', async () => {
    native.getAlarmEditorSnapshot.mockResolvedValue({
      generatedAtMs: 1000,
      isNewDraft: false,
      alarm: {
        id: ALARM_ID,
        revision: 1,
        label: 'Wake up',
        enabled: false,
        scheduleKind: 'WEEKLY',
        localTimeMinutes: 420,
        repeatDaysMask: 31,
        oneTimeAtUtcMs: null,
        configuredTimezoneId: 'Asia/Makassar',
        soundId: 'classic',
        mission: {
          missionType: 'MATH',
          configVersion: 1,
          target: 3,
          pushupProfileVersion: null,
          mathOperationsMask: 7,
          mathGeneratorVersion: 'math-v1',
          qrRegistered: false,
          qrDigestVersion: null,
        },
        nextOccurrenceAtUtcMs: null,
        scheduleHealth: 'DISABLED',
        scheduleErrorCode: null,
      },
      capabilities: capabilitySnapshot(),
      availablePushupProfileVersion: 'pushup-profile-v1',
      availableMathGeneratorVersion: 'math-v1',
    });

    const result = await getAlarmEditorSnapshot(ALARM_ID);

    expect(result.alarm?.label).toBe('Wake up');
    expect(native.getAlarmEditorSnapshot).toHaveBeenCalledWith(
      MISSION_ALARM_CONTRACT_VERSION,
      ALARM_ID,
    );
  });

  it('queries and validates the authoritative Home snapshot', async () => {
    native.getHomeSnapshot.mockResolvedValue(homeSnapshot());

    const result = await getHomeSnapshot();

    expect(result.alarms[0].label).toBe('Wake up');
    expect(native.getHomeSnapshot).toHaveBeenCalledWith(
      MISSION_ALARM_CONTRACT_VERSION,
    );
  });

  it('queries the active runtime with the current contract version', async () => {
    native.getActiveRuntimeSnapshot.mockResolvedValue(noActiveSnapshot());

    const result = await getActiveRuntimeSnapshot();

    expect(result.found).toBe(false);
    expect(native.getActiveRuntimeSnapshot).toHaveBeenCalledWith(
      MISSION_ALARM_CONTRACT_VERSION,
    );
  });

  it('rejects an inconsistent active runtime snapshot', async () => {
    native.getActiveRuntimeSnapshot.mockResolvedValue({
      ...noActiveSnapshot(),
      instanceId: ALARM_ID,
    });

    await expect(getActiveRuntimeSnapshot()).rejects.toThrow(
      'INTERNAL_CONTRACT_ERROR',
    );
  });

  it('adds contract metadata before launching an active instance', async () => {
    native.launchActiveInstance.mockResolvedValue({
      requestId: COMMAND_ID,
      sessionId: SESSION_ID,
      launched: true,
      launchType: 'ALARM_HOST',
    });

    const result = await launchActiveInstance({
      requestId: COMMAND_ID,
      aggregateId: ALARM_ID,
      expectedRevision: 2,
    });

    expect(result.launched).toBe(true);
    expect(native.launchActiveInstance).toHaveBeenCalledWith({
      contractVersion: MISSION_ALARM_CONTRACT_VERSION,
      requestId: COMMAND_ID,
      aggregateId: ALARM_ID,
      expectedRevision: 2,
    });
  });

  it('adds contract metadata before enabling an alarm', async () => {
    native.enableAlarm.mockResolvedValue({
      commandId: COMMAND_ID,
      aggregateType: 'ALARM',
      aggregateId: ALARM_ID,
      revision: 2,
      appliedAtMs: 1000,
      replayed: false,
    });

    const result = await enableAlarm({
      commandId: COMMAND_ID,
      aggregateId: ALARM_ID,
      expectedRevision: 1,
    });

    expect(result.revision).toBe(2);
    expect(native.enableAlarm).toHaveBeenCalledWith({
      contractVersion: MISSION_ALARM_CONTRACT_VERSION,
      commandId: COMMAND_ID,
      aggregateId: ALARM_ID,
      expectedRevision: 1,
    });
  });

  it('rejects an invalid enable revision before calling native', async () => {
    await expect(
      enableAlarm({
        commandId: COMMAND_ID,
        aggregateId: ALARM_ID,
        expectedRevision: 0,
      }),
    ).rejects.toThrow('INVALID_ARGUMENT');
    expect(native.enableAlarm).not.toHaveBeenCalled();
  });

  it.each([
    ['disable', disableAlarm, native.disableAlarm],
    ['delete', deleteAlarm, native.deleteAlarm],
  ] as const)(
    'adds contract metadata before %s',
    async (_name, command, nativeMethod) => {
      nativeMethod.mockResolvedValue({
        commandId: COMMAND_ID,
        aggregateType: 'ALARM',
        aggregateId: ALARM_ID,
        revision: 3,
        appliedAtMs: 1000,
        replayed: false,
      });
      const input = {
        commandId: COMMAND_ID,
        aggregateId: ALARM_ID,
        expectedRevision: 2,
      };

      const result = await command(input);

      expect(result.revision).toBe(3);
      expect(nativeMethod).toHaveBeenCalledWith({
        ...input,
        contractVersion: MISSION_ALARM_CONTRACT_VERSION,
      });
    },
  );
});

function validDraft(): SaveAlarmDraft {
  return {
    commandId: COMMAND_ID,
    alarmId: null,
    expectedRevision: null,
    label: 'Wake up',
    scheduleKind: 'WEEKLY',
    localTimeMinutes: 420,
    repeatDaysMask: 31,
    oneTimeAtUtcMs: null,
    configuredTimezoneId: 'Asia/Makassar',
    soundId: 'classic',
    missionType: 'MATH',
    target: 3,
    pushupProfileVersion: null,
    mathOperationsMask: 7,
    mathGeneratorVersion: 'math-v1',
  };
}

function capabilitySnapshot() {
  const state = {
    status: 'GRANTED',
    requiredForEnable: true,
    canRequestInApp: false,
    canOpenSettings: true,
  };
  return {
    checkedAtMs: 1000,
    androidApiLevel: 37,
    exactAlarm: { capability: 'EXACT_ALARM', ...state },
    notifications: { capability: 'NOTIFICATIONS', ...state },
    fullScreenIntent: { capability: 'FULL_SCREEN_INTENT', ...state },
    camera: { capability: 'CAMERA', ...state },
  };
}

function homeSnapshot() {
  return {
    generatedAtMs: 1000,
    alarms: [
      {
        id: ALARM_ID,
        revision: 2,
        label: 'Wake up',
        enabled: true,
        localTimeMinutes: 420,
        repeatDaysMask: 31,
        missionType: 'MATH',
        target: 3,
        nextOccurrenceAtUtcMs: 2000,
        scheduleHealth: 'HEALTHY',
      },
    ],
    active: null,
    recentHistory: [],
  } as const;
}

function noActiveSnapshot() {
  return {
    generatedAtMs: 1000,
    found: false,
    instanceId: null,
    revision: null,
    runtimeState: null,
    scheduledAtUtcMs: null,
    actualTriggerAtMs: null,
    missionType: null,
    target: null,
    committedProgress: null,
    feedbackCode: null,
    recoveryReasonCode: null,
    mathQuestion: null,
    queuedCount: 0,
    terminalResult: null,
  } as const;
}

const COMMAND_ID = '126baf63-80fb-4449-89ac-37667b33ff44';
const ALARM_ID = '5a7464b0-77b6-4f75-8459-974dc6d44160';
const SESSION_ID = '87f88d28-b149-4843-9490-477c3630dc8c';
