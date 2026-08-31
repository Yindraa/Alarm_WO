import {act, fireEvent, render, waitFor} from '@testing-library/react-native';
import {AppState, type AppStateStatus} from 'react-native';
import App from '../App';
import {
  getActiveRuntimeSnapshot,
  getAlarmEditorSnapshot,
  getContractInfo,
  getHomeSnapshot,
  launchActiveInstance,
} from '../src/native/missionAlarm';

jest.mock('../src/native/missionAlarm', () => ({
  getContractInfo: jest.fn(),
  getActiveRuntimeSnapshot: jest.fn(),
  getAlarmEditorSnapshot: jest.fn(),
  getHomeSnapshot: jest.fn(),
  launchActiveInstance: jest.fn(),
}));

const getContractInfoMock = jest.mocked(getContractInfo);
const getActiveRuntimeSnapshotMock = jest.mocked(getActiveRuntimeSnapshot);
const getAlarmEditorSnapshotMock = jest.mocked(getAlarmEditorSnapshot);
const getHomeSnapshotMock = jest.mocked(getHomeSnapshot);
const launchActiveInstanceMock = jest.mocked(launchActiveInstance);

describe('application startup recovery gate', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getContractInfoMock.mockResolvedValue(CONTRACT_INFO);
    getHomeSnapshotMock.mockResolvedValue(emptyHomeSnapshot());
    getAlarmEditorSnapshotMock.mockResolvedValue(newEditorSnapshot());
  });

  it('opens Home only after native confirms no active alarm', async () => {
    let resolveSnapshot!: (snapshot: ReturnType<typeof noActiveSnapshot>) => void;
    getActiveRuntimeSnapshotMock.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          resolveSnapshot = resolve;
        }),
    ).mockResolvedValue(noActiveSnapshot());

    const view = await render(<App />);

    expect(view.getByText('Memeriksa alarm aktif…')).toBeOnTheScreen();
    expect(view.queryByText('Belum ada alarm')).not.toBeOnTheScreen();
    await act(async () => resolveSnapshot(noActiveSnapshot()));
    expect(await view.findByText('Belum ada alarm')).toBeOnTheScreen();
    expect(view.getByRole('button', {name: 'Tambah alarm'})).toBeEnabled();
  });

  it('routes an active alarm without revealing Home', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(activeSnapshot());
    launchActiveInstanceMock.mockResolvedValue({
      requestId: INSTANCE_ID,
      sessionId: SESSION_ID,
      launched: true,
      launchType: 'ALARM_HOST',
    });

    const view = await render(<App />);

    expect(await view.findByText('Membuka alarm aktif…')).toBeOnTheScreen();
    expect(view.queryByText('Belum ada alarm')).not.toBeOnTheScreen();
    expect(launchActiveInstanceMock).toHaveBeenCalledWith({
      requestId: INSTANCE_ID,
      aggregateId: INSTANCE_ID,
      expectedRevision: 3,
    });
  });

  it('fails closed and retries the recovery query', async () => {
    getActiveRuntimeSnapshotMock
      .mockRejectedValueOnce(new Error('STORAGE_ERROR'))
      .mockResolvedValueOnce(noActiveSnapshot());

    const view = await render(<App />);

    expect(await view.findByText('Data aplikasi perlu dipulihkan')).toBeOnTheScreen();
    expect(view.queryByText('Belum ada alarm')).not.toBeOnTheScreen();
    await act(async () => {
      fireEvent.press(view.getByRole('button', {name: 'Coba lagi'}));
    });
    expect(await view.findByText('Belum ada alarm')).toBeOnTheScreen();
    expect(getActiveRuntimeSnapshotMock).toHaveBeenCalledTimes(2);
  });

  it('rechecks native state when returning to the foreground', async () => {
    let appStateHandler: ((state: AppStateStatus) => void) | undefined;
    const listener = jest
      .spyOn(AppState, 'addEventListener')
      .mockImplementation((_type, handler) => {
        appStateHandler = handler;
        return {remove: jest.fn()};
      });
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());

    try {
      const view = await render(<App />);
      expect(await view.findByText('Belum ada alarm')).toBeOnTheScreen();

      await act(async () => {
        appStateHandler?.('background');
        appStateHandler?.('active');
      });

      await waitFor(() =>
        expect(getActiveRuntimeSnapshotMock).toHaveBeenCalledTimes(2),
      );
    } finally {
      listener.mockRestore();
    }
  });

  it('opens a native-backed new alarm editor from Home', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await act(async () => {
      fireEvent.press(view.getByRole('button', {name: 'Tambah alarm'}));
    });

    expect(await view.findByText('Buat alarm')).toBeOnTheScreen();
    expect(view.getByText('07:00')).toBeOnTheScreen();
    expect(getAlarmEditorSnapshotMock).toHaveBeenCalledWith(null);
    expect(view.getByRole('button', {name: 'Simpan alarm'})).toBeDisabled();
  });

  it('routes an active instance found by the atomic Home snapshot', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    getHomeSnapshotMock.mockResolvedValue({
      ...emptyHomeSnapshot(),
      active: {
        instanceId: INSTANCE_ID,
        revision: 4,
        state: 'TRIGGERED',
        missionType: 'MATH',
        target: 3,
        committedProgress: 0,
        queuedCount: 0,
      },
    });
    launchActiveInstanceMock.mockResolvedValue({
      requestId: INSTANCE_ID,
      sessionId: SESSION_ID,
      launched: true,
      launchType: 'ACTIVE_INSTANCE',
    });

    const view = await render(<App />);

    expect(await view.findByText('Membuka alarm aktif…')).toBeOnTheScreen();
    expect(view.queryByText('Belum ada alarm')).not.toBeOnTheScreen();
    expect(launchActiveInstanceMock).toHaveBeenCalledWith({
      requestId: INSTANCE_ID,
      aggregateId: INSTANCE_ID,
      expectedRevision: 4,
    });
  });

  it('renders persisted alarms and opens the selected editor identity', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    getHomeSnapshotMock.mockResolvedValue({
      ...emptyHomeSnapshot(),
      alarms: [
        {
          id: ALARM_ID,
          revision: 2,
          label: 'Pagi kerja',
          enabled: true,
          localTimeMinutes: 390,
          repeatDaysMask: 31,
          missionType: 'MATH',
          target: 3,
          nextOccurrenceAtUtcMs: 2000,
          scheduleHealth: 'HEALTHY',
        },
      ],
    });

    const view = await render(<App />);

    expect((await view.findAllByText('06:30')).length).toBeGreaterThan(0);
    expect(view.getAllByText(/Pagi kerja/).length).toBeGreaterThan(0);
    expect(view.getByText('Sen–Jum')).toBeOnTheScreen();
    await act(async () => {
      fireEvent.press(view.getByRole('button', {name: 'Edit alarm Pagi kerja'}));
    });
    expect(await view.findByText('Edit alarm')).toBeOnTheScreen();
    expect(getAlarmEditorSnapshotMock).toHaveBeenCalledWith(ALARM_ID);
  });
});

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

function activeSnapshot() {
  return {
    ...noActiveSnapshot(),
    found: true,
    instanceId: INSTANCE_ID,
    revision: 3,
    runtimeState: 'RINGING',
    scheduledAtUtcMs: 900,
    actualTriggerAtMs: 1000,
    missionType: 'MATH',
    target: 3,
    committedProgress: 0,
  } as const;
}

function emptyHomeSnapshot() {
  return {
    generatedAtMs: 1000,
    alarms: [],
    active: null,
    recentHistory: [],
  } as const;
}

function newEditorSnapshot() {
  const capability = {
    capability: 'EXACT_ALARM',
    status: 'GRANTED',
    requiredForEnable: true,
    canRequestInApp: false,
    canOpenSettings: true,
  };
  return {
    generatedAtMs: 1000,
    isNewDraft: true,
    alarm: null,
    capabilities: {
      checkedAtMs: 1000,
      androidApiLevel: 37,
      exactAlarm: capability,
      notifications: {...capability, capability: 'NOTIFICATIONS'},
      fullScreenIntent: {...capability, capability: 'FULL_SCREEN_INTENT'},
      camera: {...capability, capability: 'CAMERA'},
    },
    availablePushupProfileVersion: 'pushup-profile-v1',
    availableMathGeneratorVersion: 'math-v1',
  } as const;
}

const CONTRACT_INFO = {
  contractVersion: 1,
  minimumClientContractVersion: 1,
  moduleName: 'NativeMissionAlarm',
  nativeBuildVersion: '1.0',
};
const INSTANCE_ID = '5a7464b0-77b6-4f75-8459-974dc6d44160';
const SESSION_ID = '126baf63-80fb-4449-89ac-37667b33ff44';
const ALARM_ID = '87f88d28-b149-4843-9490-477c3630dc8c';
