import NativeMissionAlarm from '../src/native/specs/NativeMissionAlarm';
import {
  getAlarmEditorSnapshot,
  MISSION_ALARM_CONTRACT_VERSION,
  saveAlarmConfiguration,
  type SaveAlarmDraft,
} from '../src/native/missionAlarm';

jest.mock('../src/native/specs/NativeMissionAlarm', () => ({
  __esModule: true,
  default: {
    getContractInfo: jest.fn(),
    getAlarmEditorSnapshot: jest.fn(),
    saveAlarmConfiguration: jest.fn(),
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
      saveAlarmConfiguration({...validDraft(), commandId: 'not-a-uuid'}),
    ).rejects.toThrow('INVALID_ARGUMENT');
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
    exactAlarm: {capability: 'EXACT_ALARM', ...state},
    notifications: {capability: 'NOTIFICATIONS', ...state},
    fullScreenIntent: {capability: 'FULL_SCREEN_INTENT', ...state},
    camera: {capability: 'CAMERA', ...state},
  };
}

const COMMAND_ID = '126baf63-80fb-4449-89ac-37667b33ff44';
const ALARM_ID = '5a7464b0-77b6-4f75-8459-974dc6d44160';
