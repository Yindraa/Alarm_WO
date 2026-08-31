import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  AppState,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  useColorScheme,
  View,
} from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import {
  disableAlarm,
  enableAlarm,
  getActiveRuntimeSnapshot,
  getAlarmEditorSnapshot,
  getContractInfo,
  getHomeSnapshot,
  launchActiveInstance,
  saveAlarmConfiguration,
  type AlarmEditorSnapshot,
  type ContractInfo,
  type HomeSnapshot,
  type SaveAlarmDraft,
} from './src/native/missionAlarm';

type BootstrapState =
  | Readonly<{ status: 'loading'; slow: boolean }>
  | Readonly<{ status: 'routing' }>
  | Readonly<{ status: 'ready'; info: ContractInfo; home: HomeSnapshot }>
  | Readonly<{ status: 'error' }>;

type Screen =
  | Readonly<{ name: 'home' }>
  | Readonly<{ name: 'editor'; alarmId: string | null }>;

function App() {
  const isDarkMode = useColorScheme() === 'dark';
  const lastAppState = useRef(AppState.currentState);
  const [attempt, setAttempt] = useState(0);
  const [screen, setScreen] = useState<Screen>({ name: 'home' });
  const [state, setState] = useState<BootstrapState>({
    status: 'loading',
    slow: false,
  });

  useEffect(() => {
    const subscription = AppState.addEventListener('change', nextState => {
      const returningToForeground =
        lastAppState.current !== 'active' && nextState === 'active';
      lastAppState.current = nextState;
      if (returningToForeground) {
        setAttempt(current => current + 1);
      }
    });
    return () => subscription?.remove();
  }, []);

  useEffect(() => {
    let active = true;
    if (attempt > 0) {
      setState({ status: 'loading', slow: false });
    }
    const slowTimer = setTimeout(() => {
      if (active) {
        setState(current =>
          current.status === 'loading'
            ? { status: 'loading', slow: true }
            : current,
        );
      }
    }, 2000);

    async function routeActive(instanceId: string, revision: number) {
      setState({ status: 'routing' });
      await launchActiveInstance({
        requestId: instanceId,
        aggregateId: instanceId,
        expectedRevision: revision,
      });
    }

    async function bootstrap() {
      try {
        const info = await getContractInfo();
        const runtime = await getActiveRuntimeSnapshot();
        if (!active) {
          return;
        }
        if (runtime.found) {
          if (runtime.instanceId === null || runtime.revision === null) {
            throw new Error('INTERNAL_CONTRACT_ERROR');
          }
          await routeActive(runtime.instanceId, runtime.revision);
          return;
        }

        const home = await getHomeSnapshot();
        if (!active) {
          return;
        }
        if (home.active !== null) {
          await routeActive(home.active.instanceId, home.active.revision);
          return;
        }
        setState({ status: 'ready', info, home });
      } catch {
        if (active) {
          setState({ status: 'error' });
        }
      } finally {
        clearTimeout(slowTimer);
      }
    }

    bootstrap();
    return () => {
      active = false;
      clearTimeout(slowTimer);
    };
  }, [attempt]);

  const colors = isDarkMode ? darkColors : lightColors;

  const refreshReadyHome = async (info: ContractInfo) => {
    const home = await getHomeSnapshot();
    if (home.active !== null) {
      setState({ status: 'routing' });
      await launchActiveInstance({
        requestId: home.active.instanceId,
        aggregateId: home.active.instanceId,
        expectedRevision: home.active.revision,
      });
      return;
    }
    setState({ status: 'ready', info, home });
    setScreen({ name: 'home' });
  };

  let content;
  if (state.status !== 'ready') {
    content = (
      <RecoveryGate
        colors={colors}
        state={state}
        onRetry={() => setAttempt(current => current + 1)}
      />
    );
  } else if (screen.name === 'editor') {
    content = (
      <EditorShell
        alarmId={screen.alarmId}
        colors={colors}
        onBack={() => setScreen({ name: 'home' })}
        onSaved={() => refreshReadyHome(state.info)}
      />
    );
  } else {
    content = (
      <HomeShell
        colors={colors}
        home={state.home}
        info={state.info}
        onOpenEditor={alarmId => setScreen({ name: 'editor', alarmId })}
        onRefresh={() => refreshReadyHome(state.info)}
      />
    );
  }

  return (
    <SafeAreaProvider>
      <SafeAreaView
        edges={['top', 'right', 'bottom', 'left']}
        style={[styles.safeArea, { backgroundColor: colors.background }]}
      >
        <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
        {content}
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

type Colors = typeof lightColors;

function RecoveryGate({
  colors,
  state,
  onRetry,
}: Readonly<{
  colors: Colors;
  state: Exclude<BootstrapState, { status: 'ready' }>;
  onRetry: () => void;
}>) {
  return (
    <View style={styles.gateContainer}>
      <Text
        accessibilityRole="header"
        style={[styles.title, { color: colors.text }]}
      >
        Mission Alarm
      </Text>
      {state.status !== 'error' ? (
        <View accessibilityLiveRegion="polite" style={styles.statusGroup}>
          <ActivityIndicator color={colors.primary} size="large" />
          <Text style={[styles.heading, { color: colors.text }]}>
            {state.status === 'routing'
              ? 'Membuka alarm aktif…'
              : 'Memeriksa alarm aktif…'}
          </Text>
          {state.status === 'loading' && state.slow && (
            <Text style={[styles.body, { color: colors.secondary }]}>
              Menyiapkan status alarm dengan aman.
            </Text>
          )}
        </View>
      ) : (
        <View accessibilityLiveRegion="assertive" style={styles.statusGroup}>
          <Text style={[styles.heading, { color: colors.danger }]}>
            Data aplikasi perlu dipulihkan
          </Text>
          <Text style={[styles.body, { color: colors.secondary }]}>
            Beranda dikunci sementara agar status alarm tidak ditampilkan
            keliru.
          </Text>
          <PrimaryButton colors={colors} label="Coba lagi" onPress={onRetry} />
        </View>
      )}
    </View>
  );
}

function HomeShell({
  colors,
  home,
  info,
  onOpenEditor,
  onRefresh,
}: Readonly<{
  colors: Colors;
  home: HomeSnapshot;
  info: ContractInfo;
  onOpenEditor: (alarmId: string | null) => void;
  onRefresh: () => Promise<void>;
}>) {
  const [pendingAlarmId, setPendingAlarmId] = useState<string | null>(null);
  const [failedToggle, setFailedToggle] = useState<FailedAlarmToggle | null>(
    null,
  );
  const nextAlarm = home.alarms
    .filter(alarm => alarm.enabled && alarm.nextOccurrenceAtUtcMs !== null)
    .sort(
      (left, right) =>
        Number(left.nextOccurrenceAtUtcMs) -
        Number(right.nextOccurrenceAtUtcMs),
    )[0];

  const runToggle = async (
    alarm: HomeSnapshot['alarms'][number],
    retryAction?: AlarmToggleAction,
  ) => {
    if (pendingAlarmId !== null) {
      return;
    }
    const desiredEnabled = !alarm.enabled;
    const action =
      retryAction !== undefined &&
      retryAction.alarmId === alarm.id &&
      retryAction.expectedRevision === alarm.revision &&
      retryAction.desiredEnabled === desiredEnabled
        ? retryAction
        : {
            alarmId: alarm.id,
            expectedRevision: alarm.revision,
            desiredEnabled,
            commandId: createUuidV4(),
          };
    setPendingAlarmId(alarm.id);
    setFailedToggle(null);
    try {
      const command = {
        commandId: action.commandId,
        aggregateId: action.alarmId,
        expectedRevision: action.expectedRevision,
      };
      if (action.desiredEnabled) {
        await enableAlarm(command);
      } else {
        await disableAlarm(command);
      }
      await onRefresh();
    } catch (error) {
      const code = nativeErrorCode(error);
      if (code === 'CONFLICT_REVISION' || code === 'INVALID_STATE') {
        try {
          await onRefresh();
        } catch {
          // The actionable mutation error remains more useful than a secondary refresh error.
        }
      }
      setFailedToggle({
        action,
        label: alarm.label,
        message: toggleErrorCopy(code, action.desiredEnabled),
      });
    } finally {
      setPendingAlarmId(null);
    }
  };

  const retryFailedToggle = async () => {
    if (failedToggle === null) {
      return;
    }
    const alarm = home.alarms.find(
      item => item.id === failedToggle.action.alarmId,
    );
    if (alarm === undefined) {
      setFailedToggle(null);
      return;
    }
    await runToggle(alarm, failedToggle.action);
  };

  return (
    <View style={styles.homeContainer}>
      <View style={styles.topBar}>
        <Text
          accessibilityRole="header"
          style={[styles.homeTitle, { color: colors.text }]}
        >
          Mission Alarm
        </Text>
        <Text style={[styles.offlineLabel, { color: colors.secondary }]}>
          Offline
        </Text>
      </View>
      <ScrollView contentContainerStyle={styles.homeContent}>
        {failedToggle !== null && (
          <View
            accessibilityLiveRegion="assertive"
            style={[
              styles.homeErrorCard,
              { backgroundColor: colors.dangerSurface },
            ]}
          >
            <Text style={[styles.homeErrorTitle, { color: colors.danger }]}>
              Status {failedToggle.label} belum berubah
            </Text>
            <Text style={[styles.homeErrorBody, { color: colors.text }]}>
              {failedToggle.message}
            </Text>
            <View style={styles.homeErrorActions}>
              <Pressable
                accessibilityRole="button"
                onPress={() => setFailedToggle(null)}
                style={styles.inlineAction}
              >
                <Text
                  style={[
                    styles.inlineActionLabel,
                    { color: colors.secondary },
                  ]}
                >
                  Tutup
                </Text>
              </Pressable>
              <Pressable
                accessibilityRole="button"
                disabled={pendingAlarmId !== null}
                onPress={retryFailedToggle}
                style={styles.inlineAction}
              >
                <Text
                  style={[styles.inlineActionLabel, { color: colors.primary }]}
                >
                  Coba lagi
                </Text>
              </Pressable>
            </View>
          </View>
        )}
        {nextAlarm && (
          <View style={[styles.nextCard, { backgroundColor: colors.primary }]}>
            <Text style={styles.nextEyebrow}>ALARM BERIKUTNYA</Text>
            <Text style={styles.nextTime}>
              {formatTime(nextAlarm.localTimeMinutes)}
            </Text>
            <Text style={styles.nextLabel}>
              {nextAlarm.label} ·{' '}
              {missionLabel(nextAlarm.missionType, nextAlarm.target)}
            </Text>
          </View>
        )}

        <View style={styles.sectionHeader}>
          <Text style={[styles.sectionTitle, { color: colors.text }]}>
            Alarm
          </Text>
          <Text style={[styles.countLabel, { color: colors.secondary }]}>
            {home.alarms.length}
          </Text>
        </View>

        {home.alarms.length === 0 ? (
          <View style={[styles.emptyCard, { backgroundColor: colors.surface }]}>
            <Text style={[styles.heading, { color: colors.text }]}>
              Belum ada alarm
            </Text>
            <Text style={[styles.body, { color: colors.secondary }]}>
              Buat alarm pertamamu dan pilih misi yang harus diselesaikan saat
              alarm berbunyi.
            </Text>
          </View>
        ) : (
          <View style={styles.alarmList}>
            {home.alarms.map(alarm => {
              const pending = pendingAlarmId === alarm.id;
              return (
                <View
                  key={alarm.id}
                  style={[styles.alarmRow, { backgroundColor: colors.surface }]}
                >
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={`Edit alarm ${alarm.label}`}
                    disabled={pending}
                    onPress={() => onOpenEditor(alarm.id)}
                    style={styles.alarmEditAction}
                  >
                    <View style={styles.alarmTimeColumn}>
                      <Text style={[styles.alarmTime, { color: colors.text }]}>
                        {formatTime(alarm.localTimeMinutes)}
                      </Text>
                      <Text
                        style={[
                          styles.alarmSchedule,
                          { color: colors.secondary },
                        ]}
                      >
                        {repeatLabel(alarm.repeatDaysMask)}
                      </Text>
                    </View>
                    <View style={styles.alarmDetail}>
                      <Text
                        numberOfLines={1}
                        style={[styles.alarmLabel, { color: colors.text }]}
                      >
                        {alarm.label}
                      </Text>
                      <Text
                        style={[
                          styles.alarmMission,
                          { color: colors.secondary },
                        ]}
                      >
                        {missionLabel(alarm.missionType, alarm.target)}
                      </Text>
                    </View>
                  </Pressable>
                  <Pressable
                    accessibilityLabel={`${
                      alarm.enabled ? 'Nonaktifkan' : 'Aktifkan'
                    } alarm ${alarm.label}`}
                    accessibilityRole="switch"
                    accessibilityState={{
                      checked: alarm.enabled,
                      disabled: pending,
                    }}
                    disabled={pending}
                    onPress={() => runToggle(alarm)}
                    style={[
                      styles.alarmToggle,
                      {
                        backgroundColor: alarm.enabled
                          ? colors.success
                          : colors.disabled,
                      },
                    ]}
                  >
                    {pending ? (
                      <ActivityIndicator color="#FFFFFF" size="small" />
                    ) : (
                      <Text style={styles.alarmState}>
                        {alarm.enabled ? 'ON' : 'OFF'}
                      </Text>
                    )}
                  </Pressable>
                </View>
              );
            })}
          </View>
        )}

        {home.recentHistory.length > 0 && (
          <View style={styles.historySection}>
            <Text style={[styles.sectionTitle, { color: colors.text }]}>
              Riwayat terbaru
            </Text>
            {home.recentHistory.map(item => (
              <Text
                key={item.instanceId}
                style={[styles.historyItem, { color: colors.secondary }]}
              >
                {item.result === 'SUCCESS' ? '✓' : '•'}{' '}
                {missionLabel(item.missionType, item.target)}
              </Text>
            ))}
          </View>
        )}
      </ScrollView>
      <PrimaryButton
        colors={colors}
        label="Tambah alarm"
        onPress={() => onOpenEditor(null)}
      />
      <Text style={[styles.buildInfo, { color: colors.secondary }]}>
        Kontrak native v{info.contractVersion} · build {info.nativeBuildVersion}
      </Text>
    </View>
  );
}

type AlarmToggleAction = Readonly<{
  alarmId: string;
  expectedRevision: number;
  desiredEnabled: boolean;
  commandId: string;
}>;

type FailedAlarmToggle = Readonly<{
  action: AlarmToggleAction;
  label: string;
  message: string;
}>;

function toggleErrorCopy(code: string, desiredEnabled: boolean): string {
  if (code === 'CAPABILITY_REQUIRED') {
    return 'Akses Alarm & pengingat belum tersedia. Izinkan Mission Alarm melalui Setelan aplikasi, kembali ke aplikasi, lalu coba lagi.';
  }
  if (code === 'QR_NOT_REGISTERED') {
    return 'QR belum didaftarkan. Alarm tetap tersimpan sebagai draft sampai registrasi QR tersedia.';
  }
  if (code === 'CONFLICT_REVISION' || code === 'INVALID_STATE') {
    return 'Data alarm telah diperbarui. Beranda sudah dimuat ulang; periksa status terbaru sebelum mencoba lagi.';
  }
  return desiredEnabled
    ? 'Alarm belum dapat diaktifkan. Status lama tetap dipertahankan; coba lagi dengan command yang sama.'
    : 'Alarm belum dapat dinonaktifkan. Status lama tetap dipertahankan; coba lagi dengan command yang sama.';
}

function EditorShell({
  alarmId,
  colors,
  onBack,
  onSaved,
}: Readonly<{
  alarmId: string | null;
  colors: Colors;
  onBack: () => void;
  onSaved: () => Promise<void>;
}>) {
  const [state, setState] = useState<
    | Readonly<{ status: 'loading' }>
    | Readonly<{
        status: 'ready';
        snapshot: AlarmEditorSnapshot;
        form: EditorForm;
        saving: boolean;
        pendingCommandId: string | null;
        error: string | null;
      }>
    | Readonly<{ status: 'error' }>
  >({ status: 'loading' });

  useEffect(() => {
    let active = true;
    getAlarmEditorSnapshot(alarmId)
      .then(snapshot => {
        if (active) {
          setState({
            status: 'ready',
            snapshot,
            form: editorForm(snapshot),
            saving: false,
            pendingCommandId: null,
            error: null,
          });
        }
      })
      .catch(() => {
        if (active) {
          setState({ status: 'error' });
        }
      });
    return () => {
      active = false;
    };
  }, [alarmId]);

  const updateForm = (change: Partial<EditorForm>) => {
    setState(current =>
      current.status === 'ready'
        ? {
            ...current,
            form: { ...current.form, ...change },
            pendingCommandId: null,
            error: null,
          }
        : current,
    );
  };

  const save = async () => {
    if (state.status !== 'ready' || state.saving) {
      return;
    }
    const validation = validateEditorForm(state.form);
    if (validation !== null) {
      setState({ ...state, error: validation });
      return;
    }
    const commandId = state.pendingCommandId ?? createUuidV4();
    setState({
      ...state,
      saving: true,
      pendingCommandId: commandId,
      error: null,
    });
    try {
      await saveAlarmConfiguration(
        editorDraft(state.snapshot, state.form, commandId),
      );
      await onSaved();
    } catch (error) {
      setState(current =>
        current.status === 'ready'
          ? {
              ...current,
              saving: false,
              pendingCommandId: commandId,
              error: saveErrorCopy(error),
            }
          : current,
      );
    }
  };

  const currentValidation =
    state.status === 'ready' ? validateEditorForm(state.form) : null;

  return (
    <View style={styles.editorContainer}>
      <Pressable
        accessibilityRole="button"
        onPress={onBack}
        style={styles.backButton}
      >
        <Text style={[styles.backLabel, { color: colors.primary }]}>
          ‹ Kembali
        </Text>
      </Pressable>
      <Text
        accessibilityRole="header"
        style={[styles.homeTitle, { color: colors.text }]}
      >
        {alarmId === null ? 'Buat alarm' : 'Edit alarm'}
      </Text>
      {state.status === 'loading' && (
        <View style={styles.editorStatus}>
          <ActivityIndicator color={colors.primary} />
          <Text style={[styles.body, { color: colors.secondary }]}>
            Memuat konfigurasi…
          </Text>
        </View>
      )}
      {state.status === 'error' && (
        <View style={styles.editorStatus}>
          <Text style={[styles.heading, { color: colors.danger }]}>
            Konfigurasi belum tersedia
          </Text>
          <Text style={[styles.body, { color: colors.secondary }]}>
            Kembali dan coba buka editor lagi.
          </Text>
        </View>
      )}
      {state.status === 'ready' && (
        <ScrollView
          contentContainerStyle={styles.editorContent}
          keyboardShouldPersistTaps="handled"
        >
          <View
            style={[styles.editorCard, { backgroundColor: colors.surface }]}
          >
            <Text style={[styles.fieldTitle, { color: colors.text }]}>
              Waktu
            </Text>
            <View style={styles.timeInputs}>
              <TextInput
                accessibilityLabel="Jam alarm"
                keyboardType="number-pad"
                maxLength={2}
                onChangeText={hour => updateForm({ hour: digitsOnly(hour) })}
                selectTextOnFocus
                style={[
                  styles.timeInput,
                  { borderColor: colors.border, color: colors.text },
                ]}
                value={state.form.hour}
              />
              <Text style={[styles.timeSeparator, { color: colors.text }]}>
                :
              </Text>
              <TextInput
                accessibilityLabel="Menit alarm"
                keyboardType="number-pad"
                maxLength={2}
                onChangeText={minute =>
                  updateForm({ minute: digitsOnly(minute) })
                }
                selectTextOnFocus
                style={[
                  styles.timeInput,
                  { borderColor: colors.border, color: colors.text },
                ]}
                value={state.form.minute}
              />
            </View>

            <Text style={[styles.fieldTitle, { color: colors.text }]}>
              Jadwal
            </Text>
            <View style={styles.choiceRow}>
              <ChoiceChip
                colors={colors}
                label="Mingguan"
                selected={state.form.scheduleKind === 'WEEKLY'}
                onPress={() => updateForm({ scheduleKind: 'WEEKLY' })}
              />
              <ChoiceChip
                colors={colors}
                label="Sekali"
                selected={state.form.scheduleKind === 'ONE_TIME'}
                onPress={() => updateForm({ scheduleKind: 'ONE_TIME' })}
              />
            </View>
            {state.form.scheduleKind === 'WEEKLY' ? (
              <View style={styles.dayRow}>
                {WEEKDAYS.map(day => (
                  <ChoiceChip
                    compact
                    colors={colors}
                    key={day.bit}
                    label={day.label}
                    selected={hasDay(state.form.repeatDaysMask, day.bit)}
                    onPress={() =>
                      updateForm({
                        repeatDaysMask: toggleDay(
                          state.form.repeatDaysMask,
                          day.bit,
                        ),
                      })
                    }
                  />
                ))}
              </View>
            ) : (
              <Text style={[styles.fieldHelp, { color: colors.secondary }]}>
                Alarm dijadwalkan pada waktu berikutnya yang tersedia, hari ini
                atau besok.
              </Text>
            )}

            <Text style={[styles.fieldTitle, { color: colors.text }]}>
              Label
            </Text>
            <TextInput
              accessibilityLabel="Label alarm"
              maxLength={80}
              onChangeText={label => updateForm({ label })}
              placeholder="Alarm"
              placeholderTextColor={colors.secondary}
              style={[
                styles.textInput,
                { borderColor: colors.border, color: colors.text },
              ]}
              value={state.form.label}
            />

            <Text style={[styles.fieldTitle, { color: colors.text }]}>
              Misi
            </Text>
            <View style={styles.missionChoices}>
              <ChoiceChip
                colors={colors}
                label="Math"
                selected={state.form.missionType === 'MATH'}
                onPress={() => updateForm({ missionType: 'MATH', target: '3' })}
              />
              <ChoiceChip
                colors={colors}
                label="Push-up"
                selected={state.form.missionType === 'PUSH_UP'}
                onPress={() =>
                  updateForm({ missionType: 'PUSH_UP', target: '10' })
                }
              />
              <ChoiceChip
                colors={colors}
                label="QR"
                selected={state.form.missionType === 'QR'}
                onPress={() => updateForm({ missionType: 'QR', target: '1' })}
              />
            </View>
            {state.form.missionType !== 'QR' ? (
              <View style={styles.targetRow}>
                <Text style={[styles.fieldHelp, { color: colors.secondary }]}>
                  Target
                </Text>
                <TextInput
                  accessibilityLabel="Target misi"
                  keyboardType="number-pad"
                  maxLength={2}
                  onChangeText={target =>
                    updateForm({ target: digitsOnly(target) })
                  }
                  style={[
                    styles.targetInput,
                    { borderColor: colors.border, color: colors.text },
                  ]}
                  value={state.form.target}
                />
              </View>
            ) : (
              <Text style={[styles.fieldHelp, { color: colors.secondary }]}>
                QR dapat disimpan sebagai draft; pendaftaran QR dilakukan pada
                tahap QR berikutnya.
              </Text>
            )}

            <View style={styles.soundRow}>
              <Text style={[styles.fieldTitle, { color: colors.text }]}>
                Suara
              </Text>
              <Text style={[styles.fieldValue, { color: colors.secondary }]}>
                Classic
              </Text>
            </View>
            <Text style={[styles.fieldHelp, { color: colors.secondary }]}>
              {state.snapshot.alarm === null
                ? 'Alarm baru disimpan nonaktif. Aktifkan dari Beranda setelah konfigurasi ditinjau.'
                : state.snapshot.alarm.enabled
                ? 'Alarm ini tetap aktif dan jadwal native diperbarui setelah Simpan berhasil.'
                : 'Perubahan disimpan sebagai draft nonaktif.'}
            </Text>
            {(state.error ?? currentValidation) !== null && (
              <Text
                accessibilityLiveRegion="assertive"
                style={[styles.editorError, { color: colors.danger }]}
              >
                {state.error ?? currentValidation}
              </Text>
            )}
            <Pressable
              accessibilityRole="button"
              disabled={state.saving || currentValidation !== null}
              onPress={save}
              style={[
                styles.editorSaveButton,
                {
                  backgroundColor:
                    state.saving || currentValidation !== null
                      ? colors.disabled
                      : colors.primary,
                },
              ]}
            >
              {state.saving ? (
                <ActivityIndicator color="#FFFFFF" />
              ) : (
                <Text style={styles.primaryLabel}>Simpan alarm</Text>
              )}
            </Pressable>
          </View>
        </ScrollView>
      )}
    </View>
  );
}

function ChoiceChip({
  colors,
  label,
  selected,
  onPress,
  compact = false,
}: Readonly<{
  colors: Colors;
  label: string;
  selected: boolean;
  onPress: () => void;
  compact?: boolean;
}>) {
  return (
    <Pressable
      accessibilityRole="radio"
      accessibilityState={{ checked: selected }}
      onPress={onPress}
      style={[
        styles.choiceChip,
        compact && styles.compactChip,
        {
          backgroundColor: selected ? colors.primary : colors.background,
          borderColor: selected ? colors.primary : colors.border,
        },
      ]}
    >
      <Text
        style={[
          styles.choiceLabel,
          { color: colors.text },
          selected && styles.choiceSelectedLabel,
        ]}
      >
        {label}
      </Text>
    </Pressable>
  );
}

type MissionChoice = 'MATH' | 'PUSH_UP' | 'QR';
type ScheduleChoice = 'WEEKLY' | 'ONE_TIME';
type EditorForm = Readonly<{
  hour: string;
  minute: string;
  label: string;
  scheduleKind: ScheduleChoice;
  repeatDaysMask: number;
  missionType: MissionChoice;
  target: string;
}>;

const WEEKDAYS = [
  { label: 'Sen', bit: 1 },
  { label: 'Sel', bit: 2 },
  { label: 'Rab', bit: 4 },
  { label: 'Kam', bit: 8 },
  { label: 'Jum', bit: 16 },
  { label: 'Sab', bit: 32 },
  { label: 'Min', bit: 64 },
] as const;

function editorForm(snapshot: AlarmEditorSnapshot): EditorForm {
  const alarm = snapshot.alarm;
  const minutes = alarm?.localTimeMinutes ?? 420;
  const missionType = (alarm?.mission.missionType ?? 'MATH') as MissionChoice;
  return {
    hour: Math.floor(minutes / 60)
      .toString()
      .padStart(2, '0'),
    minute: (minutes % 60).toString().padStart(2, '0'),
    label: alarm?.label ?? '',
    scheduleKind: alarm?.scheduleKind === 'ONE_TIME' ? 'ONE_TIME' : 'WEEKLY',
    repeatDaysMask: alarm?.repeatDaysMask || 31,
    missionType,
    target: (alarm?.mission.target ?? 3).toString(),
  };
}

function validateEditorForm(form: EditorForm): string | null {
  const hour = Number(form.hour);
  const minute = Number(form.minute);
  const target = Number(form.target);
  if (
    form.hour === '' ||
    form.minute === '' ||
    !Number.isInteger(hour) ||
    hour < 0 ||
    hour > 23 ||
    !Number.isInteger(minute) ||
    minute < 0 ||
    minute > 59
  ) {
    return 'Masukkan waktu yang valid antara 00:00 dan 23:59.';
  }
  if (form.label.trim().length > 80) {
    return 'Label maksimal 80 karakter.';
  }
  if (form.scheduleKind === 'WEEKLY' && form.repeatDaysMask === 0) {
    return 'Pilih setidaknya satu hari.';
  }
  if (
    form.missionType === 'MATH' &&
    (!Number.isInteger(target) || target < 1 || target > 10)
  ) {
    return 'Target Math harus antara 1 dan 10.';
  }
  if (
    form.missionType === 'PUSH_UP' &&
    (!Number.isInteger(target) || target < 1 || target > 50)
  ) {
    return 'Target Push-up harus antara 1 dan 50.';
  }
  return null;
}

function editorDraft(
  snapshot: AlarmEditorSnapshot,
  form: EditorForm,
  commandId: string,
): SaveAlarmDraft {
  const hour = Number(form.hour);
  const minute = Number(form.minute);
  const alarm = snapshot.alarm;
  const target = form.missionType === 'QR' ? 1 : Number(form.target);
  return {
    commandId,
    alarmId: alarm?.id ?? null,
    expectedRevision: alarm?.revision ?? null,
    label: form.label.trim() || 'Alarm',
    scheduleKind: form.scheduleKind,
    localTimeMinutes: hour * 60 + minute,
    repeatDaysMask: form.scheduleKind === 'WEEKLY' ? form.repeatDaysMask : 0,
    oneTimeAtUtcMs:
      form.scheduleKind === 'ONE_TIME' ? nextLocalTime(hour, minute) : null,
    configuredTimezoneId: alarm?.configuredTimezoneId ?? deviceTimezone(),
    soundId: alarm?.soundId ?? 'classic',
    missionType: form.missionType,
    target,
    pushupProfileVersion:
      form.missionType === 'PUSH_UP'
        ? alarm?.mission.missionType === 'PUSH_UP' &&
          alarm.mission.pushupProfileVersion !== null
          ? alarm.mission.pushupProfileVersion
          : snapshot.availablePushupProfileVersion
        : null,
    mathOperationsMask:
      form.missionType === 'MATH'
        ? alarm?.mission.missionType === 'MATH' &&
          alarm.mission.mathOperationsMask !== null
          ? alarm.mission.mathOperationsMask
          : 7
        : null,
    mathGeneratorVersion:
      form.missionType === 'MATH'
        ? alarm?.mission.missionType === 'MATH' &&
          alarm.mission.mathGeneratorVersion !== null
          ? alarm.mission.mathGeneratorVersion
          : snapshot.availableMathGeneratorVersion
        : null,
  };
}

function digitsOnly(value: string): string {
  return value.replace(/[^0-9]/g, '');
}

function hasDay(mask: number, bit: number): boolean {
  return Math.floor(mask / bit) % 2 === 1;
}

function toggleDay(mask: number, bit: number): number {
  return hasDay(mask, bit) ? mask - bit : mask + bit;
}

function nextLocalTime(hour: number, minute: number): number {
  const now = new Date();
  const next = new Date(now);
  next.setHours(hour, minute, 0, 0);
  if (next.getTime() <= now.getTime()) {
    next.setDate(next.getDate() + 1);
  }
  return next.getTime();
}

function deviceTimezone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
}

function createUuidV4(): string {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, token => {
    const value =
      token === 'x'
        ? Math.floor(Math.random() * 16)
        : 8 + Math.floor(Math.random() * 4);
    return value.toString(16);
  });
}

function saveErrorCopy(error: unknown): string {
  const code = nativeErrorCode(error);
  if (code.includes('CONFLICT_REVISION')) {
    return 'Alarm berubah di tempat lain. Kembali ke Beranda lalu buka kembali editor.';
  }
  if (code.includes('CAPABILITY_REQUIRED')) {
    return 'Kapabilitas alarm perlu dipulihkan sebelum alarm aktif dapat diubah.';
  }
  return 'Alarm belum tersimpan. Periksa input lalu coba lagi.';
}

function nativeErrorCode(error: unknown): string {
  const nativeCode =
    typeof error === 'object' && error !== null && 'code' in error
      ? String(error.code)
      : '';
  const message = error instanceof Error ? error.message : '';
  const combined = `${nativeCode} ${message}`;
  return (
    [
      'CAPABILITY_REQUIRED',
      'QR_NOT_REGISTERED',
      'CONFLICT_REVISION',
      'INVALID_STATE',
    ].find(code => combined.includes(code)) ?? 'UNKNOWN'
  );
}

function PrimaryButton({
  colors,
  label,
  onPress,
}: Readonly<{ colors: Colors; label: string; onPress: () => void }>) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={[styles.primaryButton, { backgroundColor: colors.primary }]}
    >
      <Text style={styles.primaryLabel}>{label}</Text>
    </Pressable>
  );
}

function formatTime(minutes: number): string {
  const hour = Math.floor(minutes / 60)
    .toString()
    .padStart(2, '0');
  const minute = (minutes % 60).toString().padStart(2, '0');
  return `${hour}:${minute}`;
}

function repeatLabel(mask: number): string {
  if (mask === 0) {
    return 'Sekali';
  }
  if (mask === 31) {
    return 'Sen–Jum';
  }
  if (mask === 96) {
    return 'Sab–Min';
  }
  if (mask === 127) {
    return 'Setiap hari';
  }
  const days = ['Sen', 'Sel', 'Rab', 'Kam', 'Jum', 'Sab', 'Min'];
  const dayBits = [1, 2, 4, 8, 16, 32, 64];
  return days
    .filter((_day, index) => Math.floor(mask / dayBits[index]) % 2 === 1)
    .join(', ');
}

function missionLabel(type: string, target: number): string {
  const name = type === 'PUSH_UP' ? 'Push-up' : type === 'QR' ? 'QR' : 'Math';
  return type === 'QR' ? name : `${name} · ${target}`;
}

const lightColors = {
  background: '#F8FAFC',
  surface: '#FFFFFF',
  text: '#0F172A',
  secondary: '#475569',
  primary: '#0369A1',
  border: '#CBD5E1',
  disabled: '#94A3B8',
  danger: '#B91C1C',
  dangerSurface: '#FEE2E2',
  success: '#166534',
};

const darkColors: Colors = {
  background: '#07111F',
  surface: '#111E2E',
  text: '#F8FAFC',
  secondary: '#CBD5E1',
  primary: '#0369A1',
  border: '#334155',
  disabled: '#475569',
  danger: '#F87171',
  dangerSurface: '#3F151D',
  success: '#4ADE80',
};

const styles = StyleSheet.create({
  safeArea: { flex: 1 },
  gateContainer: {
    alignItems: 'center',
    flex: 1,
    justifyContent: 'center',
    padding: 24,
  },
  homeContainer: { flex: 1, paddingHorizontal: 24, paddingTop: 20 },
  homeContent: { paddingBottom: 24 },
  topBar: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 24,
  },
  title: { fontSize: 32, fontWeight: '700', marginBottom: 40 },
  homeTitle: { fontSize: 28, fontWeight: '700' },
  offlineLabel: { fontSize: 14, fontWeight: '600' },
  statusGroup: { alignItems: 'center', gap: 16, maxWidth: 480 },
  nextCard: { borderRadius: 20, marginBottom: 28, padding: 24 },
  nextEyebrow: {
    color: '#E0F2FE',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1,
  },
  nextTime: { color: '#FFFFFF', fontSize: 38, fontWeight: '800', marginTop: 8 },
  nextLabel: { color: '#E0F2FE', fontSize: 15, marginTop: 4 },
  sectionHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
    marginBottom: 12,
  },
  sectionTitle: { fontSize: 20, fontWeight: '700' },
  countLabel: { fontSize: 14, fontWeight: '600' },
  emptyCard: { borderRadius: 20, gap: 12, padding: 24 },
  homeErrorCard: { borderRadius: 16, gap: 8, marginBottom: 20, padding: 16 },
  homeErrorTitle: { fontSize: 15, fontWeight: '700' },
  homeErrorBody: { fontSize: 14, lineHeight: 20 },
  homeErrorActions: { flexDirection: 'row', justifyContent: 'flex-end' },
  inlineAction: {
    justifyContent: 'center',
    minHeight: 44,
    paddingHorizontal: 12,
  },
  inlineActionLabel: { fontSize: 14, fontWeight: '700' },
  alarmList: { gap: 12 },
  alarmRow: {
    alignItems: 'center',
    borderRadius: 16,
    flexDirection: 'row',
    minHeight: 80,
    padding: 16,
  },
  alarmEditAction: { alignItems: 'center', flex: 1, flexDirection: 'row' },
  alarmTimeColumn: { width: 82 },
  alarmTime: { fontSize: 22, fontWeight: '700' },
  alarmSchedule: { fontSize: 12, marginTop: 2 },
  alarmDetail: { flex: 1, paddingHorizontal: 10 },
  alarmLabel: { fontSize: 16, fontWeight: '700' },
  alarmMission: { fontSize: 13, marginTop: 4 },
  alarmToggle: {
    alignItems: 'center',
    borderRadius: 999,
    height: 38,
    justifyContent: 'center',
    minWidth: 58,
  },
  alarmState: { color: '#FFFFFF', fontSize: 13, fontWeight: '800' },
  historySection: { gap: 10, marginTop: 28 },
  historyItem: { fontSize: 14 },
  heading: { fontSize: 20, fontWeight: '600', textAlign: 'center' },
  body: { fontSize: 16, lineHeight: 24, textAlign: 'center' },
  primaryButton: {
    alignItems: 'center',
    borderRadius: 14,
    marginVertical: 12,
    padding: 16,
  },
  primaryLabel: { color: '#FFFFFF', fontSize: 16, fontWeight: '700' },
  buildInfo: { fontSize: 12, paddingBottom: 8, textAlign: 'center' },
  editorContainer: { flex: 1, padding: 24 },
  backButton: {
    alignSelf: 'flex-start',
    minHeight: 48,
    justifyContent: 'center',
  },
  backLabel: { fontSize: 16, fontWeight: '700' },
  editorStatus: {
    alignItems: 'center',
    flex: 1,
    gap: 16,
    justifyContent: 'center',
  },
  editorContent: { paddingBottom: 32 },
  editorCard: { borderRadius: 20, gap: 12, marginTop: 24, padding: 20 },
  fieldTitle: { fontSize: 16, fontWeight: '700', marginTop: 6 },
  fieldHelp: { fontSize: 13, lineHeight: 19 },
  fieldValue: { fontSize: 15, fontWeight: '600' },
  timeInputs: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'center',
  },
  timeInput: {
    borderRadius: 12,
    borderWidth: 1,
    fontSize: 32,
    fontWeight: '800',
    height: 60,
    textAlign: 'center',
    width: 82,
  },
  timeSeparator: { fontSize: 32, fontWeight: '800', marginHorizontal: 8 },
  textInput: {
    borderRadius: 12,
    borderWidth: 1,
    fontSize: 16,
    minHeight: 50,
    paddingHorizontal: 14,
  },
  choiceRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  missionChoices: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  dayRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  choiceChip: {
    borderRadius: 999,
    borderWidth: 1,
    minHeight: 42,
    justifyContent: 'center',
    paddingHorizontal: 16,
  },
  compactChip: { minHeight: 38, paddingHorizontal: 11 },
  choiceLabel: { fontSize: 14, fontWeight: '700' },
  choiceSelectedLabel: { color: '#FFFFFF' },
  targetRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  targetInput: {
    borderRadius: 10,
    borderWidth: 1,
    fontSize: 17,
    fontWeight: '700',
    height: 44,
    textAlign: 'center',
    width: 72,
  },
  soundRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 4,
  },
  editorError: {
    fontSize: 13,
    fontWeight: '600',
    lineHeight: 19,
    textAlign: 'center',
  },
  editorSaveButton: {
    alignItems: 'center',
    borderRadius: 14,
    minHeight: 52,
    justifyContent: 'center',
    marginTop: 8,
  },
});

export default App;
