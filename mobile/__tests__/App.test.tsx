import {
  act,
  fireEvent,
  render,
  userEvent,
  waitFor,
} from '@testing-library/react-native';
import { AppState, type AppStateStatus } from 'react-native';
import App from '../App';
import {
  disableAlarm,
  enableAlarm,
  getActiveRuntimeSnapshot,
  getAlarmEditorSnapshot,
  getContractInfo,
  getHomeSnapshot,
  launchActiveInstance,
  saveAlarmConfiguration,
} from '../src/native/missionAlarm';

jest.mock('../src/native/missionAlarm', () => ({
  disableAlarm: jest.fn(),
  enableAlarm: jest.fn(),
  getContractInfo: jest.fn(),
  getActiveRuntimeSnapshot: jest.fn(),
  getAlarmEditorSnapshot: jest.fn(),
  getHomeSnapshot: jest.fn(),
  launchActiveInstance: jest.fn(),
  saveAlarmConfiguration: jest.fn(),
}));

const getContractInfoMock = jest.mocked(getContractInfo);
const disableAlarmMock = jest.mocked(disableAlarm);
const enableAlarmMock = jest.mocked(enableAlarm);
const getActiveRuntimeSnapshotMock = jest.mocked(getActiveRuntimeSnapshot);
const getAlarmEditorSnapshotMock = jest.mocked(getAlarmEditorSnapshot);
const getHomeSnapshotMock = jest.mocked(getHomeSnapshot);
const launchActiveInstanceMock = jest.mocked(launchActiveInstance);
const saveAlarmConfigurationMock = jest.mocked(saveAlarmConfiguration);

describe('application startup recovery gate', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getContractInfoMock.mockResolvedValue(CONTRACT_INFO);
    getHomeSnapshotMock.mockResolvedValue(emptyHomeSnapshot());
    getAlarmEditorSnapshotMock.mockResolvedValue(newEditorSnapshot());
    saveAlarmConfigurationMock.mockResolvedValue({
      commandId: COMMAND_ID,
      aggregateType: 'ALARM',
      aggregateId: ALARM_ID,
      revision: 1,
      appliedAtMs: 1000,
      replayed: false,
    });
    enableAlarmMock.mockResolvedValue(commandAck(2));
    disableAlarmMock.mockResolvedValue(commandAck(3));
  });

  it('opens Home only after native confirms no active alarm', async () => {
    let resolveSnapshot!: (
      snapshot: ReturnType<typeof noActiveSnapshot>,
    ) => void;
    getActiveRuntimeSnapshotMock
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            resolveSnapshot = resolve;
          }),
      )
      .mockResolvedValue(noActiveSnapshot());

    const view = await render(<App />);

    expect(view.getByText('Memeriksa alarm aktif…')).toBeOnTheScreen();
    expect(view.queryByText('Belum ada alarm')).not.toBeOnTheScreen();
    await act(async () => resolveSnapshot(noActiveSnapshot()));
    expect(await view.findByText('Belum ada alarm')).toBeOnTheScreen();
    expect(view.getByRole('button', { name: 'Tambah alarm' })).toBeEnabled();
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

    expect(
      await view.findByText('Data aplikasi perlu dipulihkan'),
    ).toBeOnTheScreen();
    expect(view.queryByText('Belum ada alarm')).not.toBeOnTheScreen();
    await act(async () => {
      fireEvent.press(view.getByRole('button', { name: 'Coba lagi' }));
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
        return { remove: jest.fn() };
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
    const user = userEvent.setup();

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await user.press(view.getByRole('button', { name: 'Tambah alarm' }));

    expect(await view.findByText('Buat alarm')).toBeOnTheScreen();
    expect(view.getByLabelText('Jam alarm')).toHaveDisplayValue('07');
    expect(view.getByLabelText('Menit alarm')).toHaveDisplayValue('00');
    expect(getAlarmEditorSnapshotMock).toHaveBeenCalledWith(null);
    expect(view.getByRole('button', { name: 'Simpan alarm' })).toBeEnabled();
  });

  it('adjusts the alarm time and shows a readable weekly summary', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    const user = userEvent.setup();

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await user.press(view.getByRole('button', { name: 'Tambah alarm' }));
    await view.findByText('Buat alarm');

    await user.press(view.getByRole('button', { name: 'Tambah jam' }));
    await user.press(view.getByRole('button', { name: 'Kurangi menit' }));

    expect(view.getByLabelText('Jam alarm')).toHaveDisplayValue('08');
    expect(view.getByLabelText('Menit alarm')).toHaveDisplayValue('55');
    expect(view.getByText('Sen–Jum • Pukul 08:55')).toBeOnTheScreen();
  });

  it('exposes weekly days as independent checkboxes', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    const user = userEvent.setup();

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await user.press(view.getByRole('button', { name: 'Tambah alarm' }));
    await view.findByText('Buat alarm');

    expect(
      view.getByRole('checkbox', { name: 'Senin', checked: true }),
    ).toBeOnTheScreen();
    const saturday = view.getByRole('checkbox', {
      name: 'Sabtu',
      checked: false,
    });
    await user.press(saturday);
    expect(
      view.getByRole('checkbox', { name: 'Sabtu', checked: true }),
    ).toBeOnTheScreen();
  });

  it('configures Scan without asking the user to register a code', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    const user = userEvent.setup();

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await user.press(view.getByRole('button', { name: 'Tambah alarm' }));
    await view.findByText('Buat alarm');
    await user.press(view.getByRole('radio', { name: 'Scan' }));

    expect(view.getByText('Tidak perlu mendaftarkan kode')).toBeOnTheScreen();
    expect(view.queryByLabelText('Target misi')).not.toBeOnTheScreen();
    expect(view.queryByRole('button', { name: /Pindai/ })).not.toBeOnTheScreen();

    await user.press(view.getByRole('button', { name: 'Simpan alarm' }));
    await waitFor(() =>
      expect(saveAlarmConfigurationMock).toHaveBeenCalledWith(
        expect.objectContaining({
          missionType: 'QR',
          target: 1,
          pushupProfileVersion: null,
          mathOperationsMask: null,
          mathGeneratorVersion: null,
        }),
      ),
    );
  });

  it('saves an edited weekly draft and refreshes authoritative Home', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    const user = userEvent.setup();

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await user.press(view.getByRole('button', { name: 'Tambah alarm' }));
    await view.findByText('Buat alarm');

    await user.clear(view.getByLabelText('Jam alarm'));
    await user.type(view.getByLabelText('Jam alarm'), '6');
    await user.clear(view.getByLabelText('Menit alarm'));
    await user.type(view.getByLabelText('Menit alarm'), '30');
    await user.type(view.getByLabelText('Label alarm'), 'Pagi kerja');
    await user.clear(view.getByLabelText('Target misi'));
    await user.type(view.getByLabelText('Target misi'), '5');
    await waitFor(() => {
      expect(view.getByLabelText('Jam alarm')).toHaveDisplayValue('6');
      expect(view.getByLabelText('Menit alarm')).toHaveDisplayValue('30');
      expect(view.getByLabelText('Label alarm')).toHaveDisplayValue(
        'Pagi kerja',
      );
      expect(view.getByLabelText('Target misi')).toHaveDisplayValue('5');
    });
    await user.press(view.getByRole('button', { name: 'Simpan alarm' }));

    await waitFor(() =>
      expect(saveAlarmConfigurationMock).toHaveBeenCalledTimes(1),
    );
    expect(saveAlarmConfigurationMock).toHaveBeenCalledWith(
      expect.objectContaining({
        commandId: expect.stringMatching(
          /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
        ),
        alarmId: null,
        expectedRevision: null,
        label: 'Pagi kerja',
        scheduleKind: 'WEEKLY',
        localTimeMinutes: 390,
        repeatDaysMask: 31,
        oneTimeAtUtcMs: null,
        soundId: 'classic',
        missionType: 'MATH',
        target: 5,
        mathOperationsMask: 7,
        mathGeneratorVersion: 'math-v1',
      }),
    );
    expect(getHomeSnapshotMock).toHaveBeenCalledTimes(2);
    expect(await view.findByText('Belum ada alarm')).toBeOnTheScreen();
  });

  it('builds a future one-time Push-up draft from the editor choices', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    const user = userEvent.setup();

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await user.press(view.getByRole('button', { name: 'Tambah alarm' }));
    await view.findByText('Buat alarm');
    await user.press(view.getByRole('radio', { name: 'Sekali' }));
    await user.press(view.getByRole('radio', { name: 'Push-up' }));
    const beforeSave = Date.now();
    await user.press(view.getByRole('button', { name: 'Simpan alarm' }));

    await waitFor(() =>
      expect(saveAlarmConfigurationMock).toHaveBeenCalledTimes(1),
    );
    const draft = saveAlarmConfigurationMock.mock.calls[0][0];
    expect(draft).toEqual(
      expect.objectContaining({
        scheduleKind: 'ONE_TIME',
        repeatDaysMask: 0,
        missionType: 'PUSH_UP',
        target: 10,
        pushupProfileVersion: 'pushup-profile-v0',
        mathOperationsMask: null,
        mathGeneratorVersion: null,
      }),
    );
    expect(draft.oneTimeAtUtcMs).not.toBeNull();
    expect(Number(draft.oneTimeAtUtcMs)).toBeGreaterThan(beforeSave);
    expect(Number(draft.oneTimeAtUtcMs)).toBeLessThanOrEqual(
      beforeSave + 24 * 60 * 60 * 1000,
    );
  });

  it('keeps the command ID stable when a failed save is retried', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    const user = userEvent.setup();
    saveAlarmConfigurationMock
      .mockRejectedValueOnce(new Error('STORAGE_ERROR'))
      .mockResolvedValueOnce({
        commandId: COMMAND_ID,
        aggregateType: 'ALARM',
        aggregateId: ALARM_ID,
        revision: 1,
        appliedAtMs: 1000,
        replayed: true,
      });

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await user.press(view.getByRole('button', { name: 'Tambah alarm' }));
    await view.findByText('Buat alarm');

    await user.press(view.getByRole('button', { name: 'Simpan alarm' }));
    expect(
      await view.findByText(
        'Alarm belum tersimpan. Periksa input lalu coba lagi.',
      ),
    ).toBeOnTheScreen();
    await user.press(view.getByRole('button', { name: 'Simpan alarm' }));

    await waitFor(() =>
      expect(saveAlarmConfigurationMock).toHaveBeenCalledTimes(2),
    );
    const firstCommand = saveAlarmConfigurationMock.mock.calls[0][0].commandId;
    const secondCommand = saveAlarmConfigurationMock.mock.calls[1][0].commandId;
    expect(secondCommand).toBe(firstCommand);
  });

  it('blocks save when the entered time is invalid', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    const user = userEvent.setup();

    const view = await render(<App />);
    await view.findByText('Belum ada alarm');
    await user.press(view.getByRole('button', { name: 'Tambah alarm' }));
    await view.findByText('Buat alarm');
    await user.clear(view.getByLabelText('Jam alarm'));
    await user.type(view.getByLabelText('Jam alarm'), '25');

    await waitFor(() =>
      expect(view.getByRole('button', { name: 'Simpan alarm' })).toBeDisabled(),
    );
    expect(
      view.getByText('Masukkan waktu yang valid antara 00:00 dan 23:59.'),
    ).toBeOnTheScreen();
    expect(saveAlarmConfigurationMock).not.toHaveBeenCalled();
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
      fireEvent.press(
        view.getByRole('button', {
          name: /Pagi kerja, pukul 06:30, Sen–Jum, Math · 3/,
        }),
      );
    });
    expect(await view.findByText('Edit alarm')).toBeOnTheScreen();
    expect(getAlarmEditorSnapshotMock).toHaveBeenCalledWith(ALARM_ID);
  });

  it('enables a draft through native and renders the refreshed state', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    getHomeSnapshotMock
      .mockResolvedValueOnce(homeWithAlarm(false, 1))
      .mockResolvedValueOnce(homeWithAlarm(true, 2));
    const user = userEvent.setup();

    const view = await render(<App />);
    const toggle = await view.findByRole('switch', {
      name: 'Aktifkan alarm Pagi kerja',
    });
    await user.press(toggle);

    expect(enableAlarmMock).toHaveBeenCalledWith({
      commandId: expect.stringMatching(
        /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      ),
      aggregateId: ALARM_ID,
      expectedRevision: 1,
    });
    expect(
      await view.findByRole('switch', {
        name: 'Nonaktifkan alarm Pagi kerja',
      }),
    ).toBeOnTheScreen();
  });

  it('disables an enabled alarm through the exact snapshot revision', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    getHomeSnapshotMock
      .mockResolvedValueOnce(homeWithAlarm(true, 4))
      .mockResolvedValueOnce(homeWithAlarm(false, 5));
    const user = userEvent.setup();

    const view = await render(<App />);
    await user.press(
      await view.findByRole('switch', {
        name: 'Nonaktifkan alarm Pagi kerja',
      }),
    );

    expect(disableAlarmMock).toHaveBeenCalledWith(
      expect.objectContaining({ aggregateId: ALARM_ID, expectedRevision: 4 }),
    );
    expect(
      await view.findByRole('switch', { name: 'Aktifkan alarm Pagi kerja' }),
    ).toBeOnTheScreen();
  });

  it('keeps the enable command stable across capability recovery retry', async () => {
    getActiveRuntimeSnapshotMock.mockResolvedValue(noActiveSnapshot());
    getHomeSnapshotMock
      .mockResolvedValueOnce(homeWithAlarm(false, 1))
      .mockResolvedValueOnce(homeWithAlarm(true, 2));
    enableAlarmMock
      .mockRejectedValueOnce(
        Object.assign(new Error('CAPABILITY_REQUIRED'), {
          code: 'CAPABILITY_REQUIRED',
        }),
      )
      .mockResolvedValueOnce(commandAck(2));
    const user = userEvent.setup();

    const view = await render(<App />);
    await user.press(
      await view.findByRole('switch', { name: 'Aktifkan alarm Pagi kerja' }),
    );
    expect(
      await view.findByText(/Akses Alarm & pengingat belum tersedia/),
    ).toBeOnTheScreen();
    await user.press(
      view.getByRole('button', {
        name: 'Coba lagi mengubah status alarm',
      }),
    );

    await waitFor(() => expect(enableAlarmMock).toHaveBeenCalledTimes(2));
    expect(enableAlarmMock.mock.calls[1][0].commandId).toBe(
      enableAlarmMock.mock.calls[0][0].commandId,
    );
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

function homeWithAlarm(enabled: boolean, revision: number) {
  return {
    ...emptyHomeSnapshot(),
    alarms: [
      {
        id: ALARM_ID,
        revision,
        label: 'Pagi kerja',
        enabled,
        localTimeMinutes: 390,
        repeatDaysMask: 31,
        missionType: 'MATH',
        target: 3,
        nextOccurrenceAtUtcMs: enabled ? 2000 : null,
        scheduleHealth: enabled ? 'HEALTHY' : 'DISABLED',
      },
    ],
  } as const;
}

function commandAck(revision: number) {
  return {
    commandId: COMMAND_ID,
    aggregateType: 'ALARM',
    aggregateId: ALARM_ID,
    revision,
    appliedAtMs: 1000,
    replayed: false,
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
      notifications: { ...capability, capability: 'NOTIFICATIONS' },
      fullScreenIntent: { ...capability, capability: 'FULL_SCREEN_INTENT' },
      camera: { ...capability, capability: 'CAMERA' },
    },
    availablePushupProfileVersion: 'pushup-profile-v0',
    availableMathGeneratorVersion: 'math-v1',
  } as const;
}

const CONTRACT_INFO = {
  contractVersion: 2,
  minimumClientContractVersion: 2,
  moduleName: 'NativeMissionAlarm',
  nativeBuildVersion: '1.0',
};
const INSTANCE_ID = '5a7464b0-77b6-4f75-8459-974dc6d44160';
const SESSION_ID = '126baf63-80fb-4449-89ac-37667b33ff44';
const ALARM_ID = '87f88d28-b149-4843-9490-477c3630dc8c';
const COMMAND_ID = 'a24b1cd8-f8e4-4e78-bff3-20607ccbbd47';
