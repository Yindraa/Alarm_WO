import { useEffect, useRef, useState, type ReactNode } from 'react';
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
import { darkColors, lightColors, type AppColors } from './src/ui/theme';
import {
  disableAlarm,
  enableAlarm,
  getActiveRuntimeSnapshot,
  getAlarmEditorSnapshot,
  getContractInfo,
  getHomeSnapshot,
  launchActiveInstance,
  launchQrRegistration,
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

type Colors = AppColors;

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
        <View style={styles.brandGroup}>
          <View
            accessible={false}
            style={[styles.brandMark, { backgroundColor: colors.hero }]}
          >
            <View
              style={[styles.brandSun, { backgroundColor: colors.amber }]}
            />
          </View>
          <View>
            <Text
              accessibilityRole="header"
              style={[styles.homeTitle, { color: colors.text }]}
            >
              Mission Alarm
            </Text>
            <Text style={[styles.headerSubtitle, { color: colors.secondary }]}>
              Bangun dengan tujuan
            </Text>
          </View>
        </View>
        <View
          accessibilityLabel="Aplikasi dapat digunakan offline"
          style={[
            styles.offlineBadge,
            { backgroundColor: colors.successSurface },
          ]}
        >
          <View
            style={[styles.offlineDot, { backgroundColor: colors.success }]}
          />
          <Text style={[styles.offlineLabel, { color: colors.success }]}>
            Offline
          </Text>
        </View>
      </View>
      <ScrollView
        contentContainerStyle={styles.homeContent}
        showsVerticalScrollIndicator={false}
      >
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
        {nextAlarm ? (
          <View style={[styles.nextCard, { backgroundColor: colors.hero }]}>
            <View
              accessible={false}
              style={[
                styles.heroGlowLarge,
                { backgroundColor: colors.primary },
              ]}
            />
            <View
              accessible={false}
              style={[styles.heroGlowSmall, { backgroundColor: colors.amber }]}
            />
            <Text style={styles.nextEyebrow}>ALARM BERIKUTNYA</Text>
            <Text style={styles.nextTime}>
              {formatTime(nextAlarm.localTimeMinutes)}
            </Text>
            <Text style={styles.nextTitle}>{nextAlarm.label}</Text>
            <View style={styles.nextMetaRow}>
              <View style={styles.nextMetaPill}>
                <Text style={styles.nextMetaText}>
                  Jadwal {repeatLabel(nextAlarm.repeatDaysMask)}
                </Text>
              </View>
              <View style={styles.nextMetaPill}>
                <Text style={styles.nextMetaText}>
                  {missionSymbol(nextAlarm.missionType)}{' '}
                  {missionLabel(nextAlarm.missionType, nextAlarm.target)}
                </Text>
              </View>
            </View>
            <View style={styles.readyRow}>
              <View style={styles.readyDot} />
              <Text style={styles.readyLabel}>Siap membangunkanmu</Text>
            </View>
          </View>
        ) : (
          <View
            style={[
              styles.nextCard,
              styles.quietHero,
              { backgroundColor: colors.primarySurface },
            ]}
          >
            <Text style={[styles.quietHeroEyebrow, { color: colors.primary }]}>
              MULAI RUTINITAS
            </Text>
            <Text style={[styles.quietHeroTitle, { color: colors.text }]}>
              Pagi yang fokus dimulai dari satu alarm.
            </Text>
            <Text style={[styles.quietHeroBody, { color: colors.secondary }]}>
              Pilih waktu, tentukan misi, lalu biarkan Mission Alarm menjaga
              komitmenmu.
            </Text>
          </View>
        )}

        <View style={styles.sectionHeader}>
          <Text style={[styles.sectionTitle, { color: colors.text }]}>
            Alarm saya
          </Text>
          <View
            style={[
              styles.countBadge,
              { backgroundColor: colors.primarySurface },
            ]}
          >
            <Text style={[styles.countLabel, { color: colors.primary }]}>
              {home.alarms.length}
            </Text>
          </View>
        </View>

        {home.alarms.length === 0 ? (
          <View style={[styles.emptyCard, { backgroundColor: colors.surface }]}>
            <View
              style={[
                styles.emptyIcon,
                { backgroundColor: colors.primarySurface },
              ]}
            >
              <Text style={[styles.emptyIconText, { color: colors.primary }]}>
                +
              </Text>
            </View>
            <Text style={[styles.emptyHeading, { color: colors.text }]}>
              Belum ada alarm
            </Text>
            <Text style={[styles.emptyBody, { color: colors.secondary }]}>
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
                  style={[
                    styles.alarmRow,
                    {
                      backgroundColor: colors.surface,
                      borderColor: colors.border,
                      shadowColor: colors.shadow,
                    },
                  ]}
                >
                  <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={`Edit alarm ${alarm.label}`}
                    disabled={pending}
                    onPress={() => onOpenEditor(alarm.id)}
                    style={styles.alarmEditAction}
                  >
                    <View
                      style={[
                        styles.missionIcon,
                        {
                          backgroundColor: missionSurface(
                            alarm.missionType,
                            colors,
                          ),
                        },
                      ]}
                    >
                      <Text
                        style={[
                          styles.missionIconText,
                          {
                            color: missionColor(alarm.missionType, colors),
                          },
                        ]}
                      >
                        {missionSymbol(alarm.missionType)}
                      </Text>
                    </View>
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
                      <View
                        style={[
                          styles.toggleThumb,
                          alarm.enabled && styles.toggleThumbEnabled,
                        ]}
                      />
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
              <View
                key={item.instanceId}
                style={[
                  styles.historyItem,
                  { backgroundColor: colors.surface },
                ]}
              >
                <View
                  style={[
                    styles.historyResult,
                    { backgroundColor: colors.successSurface },
                  ]}
                >
                  <Text style={{ color: colors.success }}>✓</Text>
                </View>
                <Text style={[styles.historyText, { color: colors.secondary }]}>
                  {missionLabel(item.missionType, item.target)} diselesaikan
                </Text>
              </View>
            ))}
          </View>
        )}
      </ScrollView>
      <PrimaryButton
        colors={colors}
        label="Tambah alarm"
        onPress={() => onOpenEditor(null)}
        leading="＋"
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
  const lastEditorAppState = useRef(AppState.currentState);

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

  useEffect(() => {
    let active = true;
    const subscription = AppState.addEventListener('change', nextState => {
      const returned =
        lastEditorAppState.current !== 'active' && nextState === 'active';
      lastEditorAppState.current = nextState;
      if (!returned || alarmId === null) {
        return;
      }
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
          if (active) setState({ status: 'error' });
        });
    });
    return () => {
      active = false;
      subscription?.remove();
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

  const registerQr = async () => {
    if (
      state.status !== 'ready' ||
      state.saving ||
      state.snapshot.alarm === null
    ) {
      return;
    }
    const alarm = state.snapshot.alarm;
    setState({ ...state, saving: true, error: null });
    try {
      await launchQrRegistration({
        requestId: createUuidV4(),
        aggregateId: alarm.id,
        expectedRevision: alarm.revision,
      });
      setState(current =>
        current.status === 'ready' ? { ...current, saving: false } : current,
      );
    } catch (error) {
      setState(current =>
        current.status === 'ready'
          ? {
              ...current,
              saving: false,
              error: qrRegistrationErrorCopy(error),
            }
          : current,
      );
    }
  };

  const currentValidation =
    state.status === 'ready' ? validateEditorForm(state.form) : null;

  return (
    <View style={styles.editorContainer}>
      <View style={styles.editorHeader}>
        <Pressable
          accessibilityLabel="Kembali ke Beranda"
          accessibilityRole="button"
          onPress={onBack}
          style={[
            styles.backButton,
            { backgroundColor: colors.surface, borderColor: colors.border },
          ]}
        >
          <Text style={[styles.backLabel, { color: colors.text }]}>‹</Text>
        </Pressable>
        <View style={styles.editorHeaderCopy}>
          <Text style={[styles.editorEyebrow, { color: colors.primary }]}>
            RUTINITAS PAGI
          </Text>
          <Text
            accessibilityRole="header"
            style={[styles.editorTitle, { color: colors.text }]}
          >
            {alarmId === null ? 'Buat alarm' : 'Edit alarm'}
          </Text>
        </View>
      </View>
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
          showsVerticalScrollIndicator={false}
        >
          <View
            style={[
              styles.timeCard,
              { backgroundColor: colors.hero, shadowColor: colors.shadow },
            ]}
          >
            <View style={styles.timeCardHeading}>
              <View>
                <Text style={styles.timeCardEyebrow}>PILIH WAKTU</Text>
                <Text style={styles.timeCardTitle}>
                  Kapan kamu ingin bangun?
                </Text>
              </View>
              <View style={[styles.sunriseMark, { borderColor: colors.amber }]}>
                <View
                  style={[
                    styles.sunriseCore,
                    { backgroundColor: colors.amber },
                  ]}
                />
              </View>
            </View>
            <View style={styles.timePickerRow}>
              <TimeUnit
                accessibilityLabel="Jam alarm"
                label="JAM"
                max={23}
                onChange={hour => updateForm({ hour })}
                step={1}
                value={state.form.hour}
              />
              <Text style={styles.timeSeparator}>:</Text>
              <TimeUnit
                accessibilityLabel="Menit alarm"
                label="MENIT"
                max={59}
                onChange={minute => updateForm({ minute })}
                step={5}
                value={state.form.minute}
              />
            </View>
            <Text style={styles.timeCardHelp}>
              Format 24 jam · sentuh angka untuk mengetik langsung
            </Text>
          </View>

          <EditorSection colors={colors} eyebrow="KAPAN" title="Jadwal alarm">
            <View
              style={[
                styles.scheduleToggle,
                { backgroundColor: colors.surfaceMuted },
              ]}
            >
              <ScheduleOption
                colors={colors}
                label="Mingguan"
                symbol="↻"
                selected={state.form.scheduleKind === 'WEEKLY'}
                onPress={() => updateForm({ scheduleKind: 'WEEKLY' })}
              />
              <ScheduleOption
                colors={colors}
                label="Sekali"
                symbol="1×"
                selected={state.form.scheduleKind === 'ONE_TIME'}
                onPress={() => updateForm({ scheduleKind: 'ONE_TIME' })}
              />
            </View>
            {state.form.scheduleKind === 'WEEKLY' ? (
              <View style={styles.weeklyPicker}>
                <Text
                  style={[styles.dayPickerLabel, { color: colors.secondary }]}
                >
                  Pilih hari aktif
                </Text>
                <View style={styles.dayRow}>
                  {WEEKDAYS.map(day => (
                    <DayOption
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
              </View>
            ) : (
              <Text style={[styles.fieldHelp, { color: colors.secondary }]}>
                Alarm hanya berbunyi satu kali pada kesempatan berikutnya.
              </Text>
            )}
            <View
              style={[
                styles.scheduleSummary,
                { backgroundColor: colors.primarySurface },
              ]}
            >
              <View
                style={[
                  styles.scheduleSummaryIcon,
                  { backgroundColor: colors.primary },
                ]}
              >
                <Text style={styles.scheduleSummaryIconText}>✓</Text>
              </View>
              <View style={styles.scheduleSummaryCopy}>
                <Text
                  style={[
                    styles.scheduleSummaryEyebrow,
                    { color: colors.primary },
                  ]}
                >
                  RINGKASAN
                </Text>
                <Text
                  style={[styles.scheduleSummaryText, { color: colors.text }]}
                >
                  {scheduleSummary(state.form)}
                </Text>
              </View>
            </View>
          </EditorSection>

          <EditorSection colors={colors} eyebrow="IDENTITAS" title="Nama alarm">
            <TextInput
              accessibilityLabel="Label alarm"
              maxLength={80}
              onChangeText={label => updateForm({ label })}
              placeholder="Alarm"
              placeholderTextColor={colors.secondary}
              style={[
                styles.textInput,
                {
                  backgroundColor: colors.background,
                  borderColor: colors.border,
                  color: colors.text,
                },
              ]}
              value={state.form.label}
            />
          </EditorSection>

          <EditorSection
            colors={colors}
            eyebrow="TANTANGAN"
            title="Pilih misi bangun"
          >
            <View style={styles.missionChoices}>
              <MissionCard
                colors={colors}
                label="Math"
                description="Selesaikan soal"
                symbol="∑"
                tone="blue"
                selected={state.form.missionType === 'MATH'}
                onPress={() => updateForm({ missionType: 'MATH', target: '3' })}
              />
              <MissionCard
                colors={colors}
                label="Push-up"
                description="Aktifkan tubuh"
                symbol="P"
                tone="amber"
                selected={state.form.missionType === 'PUSH_UP'}
                onPress={() =>
                  updateForm({ missionType: 'PUSH_UP', target: '10' })
                }
              />
              <MissionCard
                colors={colors}
                label="QR"
                description="Pindai kode"
                symbol="▦"
                tone="violet"
                selected={state.form.missionType === 'QR'}
                onPress={() => updateForm({ missionType: 'QR', target: '1' })}
              />
            </View>
            {state.form.missionType !== 'QR' ? (
              <View style={styles.targetRow}>
                <Text style={[styles.fieldHelp, { color: colors.secondary }]}>
                  Target
                </Text>
                <View
                  style={[
                    styles.stepper,
                    {
                      backgroundColor: colors.background,
                      borderColor: colors.border,
                    },
                  ]}
                >
                  <StepperButton
                    colors={colors}
                    label="Kurangi target"
                    symbol="−"
                    onPress={() =>
                      updateForm({
                        target: stepTarget(state.form.target, -1).toString(),
                      })
                    }
                  />
                  <TextInput
                    accessibilityLabel="Target misi"
                    keyboardType="number-pad"
                    maxLength={2}
                    onChangeText={target =>
                      updateForm({ target: digitsOnly(target) })
                    }
                    style={[styles.targetInput, { color: colors.text }]}
                    value={state.form.target}
                  />
                  <StepperButton
                    colors={colors}
                    label="Tambah target"
                    symbol="+"
                    onPress={() =>
                      updateForm({
                        target: stepTarget(state.form.target, 1).toString(),
                      })
                    }
                  />
                </View>
              </View>
            ) : (
              <View style={styles.qrRegistrationBox}>
                <View style={styles.qrRegistrationCopy}>
                  <Text style={[styles.fieldTitle, { color: colors.text }]}>
                    {state.snapshot.alarm?.mission.missionType === 'QR' &&
                    state.snapshot.alarm.mission.qrRegistered
                      ? 'QR sudah terdaftar'
                      : 'Daftarkan QR referensi'}
                  </Text>
                  <Text style={[styles.fieldHelp, { color: colors.secondary }]}>
                    {state.snapshot.alarm === null ||
                    state.snapshot.alarm.mission.missionType !== 'QR'
                      ? 'Simpan alarm sebagai draft QR terlebih dahulu, lalu buka kembali editor ini.'
                      : state.snapshot.alarm.mission.qrRegistered
                      ? 'Hanya digest aman yang tersimpan. Isi QR tidak disimpan.'
                      : 'Pindai satu QR yang nantinya wajib ditemukan untuk mematikan alarm.'}
                  </Text>
                </View>
                {state.snapshot.alarm?.mission.missionType === 'QR' &&
                  !state.snapshot.alarm.mission.qrRegistered && (
                    <Pressable
                      accessibilityRole="button"
                      disabled={
                        state.saving ||
                        state.snapshot.capabilities.camera.status === 'UNAVAILABLE'
                      }
                      onPress={registerQr}
                      style={[
                        styles.qrRegistrationButton,
                        {
                          backgroundColor:
                            state.saving ||
                            state.snapshot.capabilities.camera.status ===
                              'UNAVAILABLE'
                              ? colors.disabled
                              : colors.primary,
                        },
                      ]}
                    >
                      <Text style={styles.primaryLabel}>
                        {state.saving ? 'Membuka kamera…' : 'Pindai QR'}
                      </Text>
                    </Pressable>
                  )}
              </View>
            )}
          </EditorSection>

          <EditorSection colors={colors} eyebrow="ALARM" title="Suara & status">
            <View style={styles.soundRow}>
              <View
                style={[
                  styles.soundIcon,
                  { backgroundColor: colors.primarySurface },
                ]}
              >
                <Text style={[styles.soundIconText, { color: colors.primary }]}>
                  ♪
                </Text>
              </View>
              <View style={styles.soundCopy}>
                <Text style={[styles.fieldTitle, { color: colors.text }]}>
                  Suara
                </Text>
                <Text style={[styles.fieldHelp, { color: colors.secondary }]}>
                  Classic
                </Text>
              </View>
              <View
                style={[
                  styles.soonBadge,
                  { backgroundColor: colors.surfaceMuted },
                ]}
              >
                <Text style={[styles.soonText, { color: colors.secondary }]}>
                  Segera
                </Text>
              </View>
            </View>
            <View
              style={[
                styles.infoBanner,
                { backgroundColor: colors.primarySurface },
              ]}
            >
              <Text style={[styles.infoMark, { color: colors.primary }]}>
                i
              </Text>
              <Text style={[styles.infoText, { color: colors.secondary }]}>
                {state.snapshot.alarm === null
                  ? 'Alarm baru disimpan nonaktif. Aktifkan dari Beranda setelah konfigurasi ditinjau.'
                  : state.snapshot.alarm.enabled
                  ? 'Alarm ini tetap aktif dan jadwal native diperbarui setelah Simpan berhasil.'
                  : 'Perubahan disimpan sebagai draft nonaktif.'}
              </Text>
            </View>
          </EditorSection>

          {(state.error ?? currentValidation) !== null && (
            <View
              accessibilityLiveRegion="assertive"
              style={[
                styles.editorErrorBanner,
                { backgroundColor: colors.dangerSurface },
              ]}
            >
              <Text style={[styles.editorError, { color: colors.danger }]}>
                {state.error ?? currentValidation}
              </Text>
            </View>
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
        </ScrollView>
      )}
    </View>
  );
}

function EditorSection({
  colors,
  eyebrow,
  title,
  children,
}: Readonly<{
  colors: Colors;
  eyebrow: string;
  title: string;
  children: ReactNode;
}>) {
  return (
    <View
      style={[
        styles.editorCard,
        {
          backgroundColor: colors.surface,
          borderColor: colors.border,
          shadowColor: colors.shadow,
        },
      ]}
    >
      <Text style={[styles.editorSectionEyebrow, { color: colors.primary }]}>
        {eyebrow}
      </Text>
      <Text style={[styles.editorSectionTitle, { color: colors.text }]}>
        {title}
      </Text>
      {children}
    </View>
  );
}

function MissionCard({
  colors,
  label,
  description,
  symbol,
  tone,
  selected,
  onPress,
}: Readonly<{
  colors: Colors;
  label: string;
  description: string;
  symbol: string;
  tone: 'blue' | 'amber' | 'violet';
  selected: boolean;
  onPress: () => void;
}>) {
  const accent =
    tone === 'amber'
      ? colors.amber
      : tone === 'violet'
      ? '#8B78E6'
      : colors.primary;
  const surface =
    tone === 'amber'
      ? '#FFF1D7'
      : tone === 'violet'
      ? '#EEEAFE'
      : colors.primarySurface;
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="radio"
      accessibilityState={{ checked: selected }}
      onPress={onPress}
      style={[
        styles.missionCard,
        {
          backgroundColor: selected ? surface : colors.background,
          borderColor: selected ? accent : colors.border,
        },
      ]}
    >
      <View style={[styles.missionCardIcon, { backgroundColor: accent }]}>
        <Text style={styles.missionCardSymbol}>{symbol}</Text>
      </View>
      <Text style={[styles.missionCardTitle, { color: colors.text }]}>
        {label}
      </Text>
      <Text
        style={[styles.missionCardDescription, { color: colors.secondary }]}
      >
        {description}
      </Text>
      {selected && (
        <View style={[styles.selectedMark, { backgroundColor: accent }]}>
          <Text style={styles.selectedMarkText}>✓</Text>
        </View>
      )}
    </Pressable>
  );
}

function StepperButton({
  colors,
  label,
  symbol,
  onPress,
}: Readonly<{
  colors: Colors;
  label: string;
  symbol: string;
  onPress: () => void;
}>) {
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="button"
      onPress={onPress}
      style={styles.stepperButton}
    >
      <Text style={[styles.stepperSymbol, { color: colors.primary }]}>
        {symbol}
      </Text>
    </Pressable>
  );
}

function TimeUnit({
  accessibilityLabel,
  label,
  max,
  step,
  value,
  onChange,
}: Readonly<{
  accessibilityLabel: string;
  label: string;
  max: number;
  step: number;
  value: string;
  onChange: (value: string) => void;
}>) {
  return (
    <View style={styles.timeUnit}>
      <Text style={styles.timeUnitLabel}>{label}</Text>
      <View style={styles.timeValueBox}>
        <TextInput
          accessibilityLabel={accessibilityLabel}
          keyboardType="number-pad"
          maxLength={2}
          onChangeText={next => onChange(digitsOnly(next))}
          selectTextOnFocus
          style={styles.timeInput}
          value={value}
        />
      </View>
      <View style={styles.timeAdjustRow}>
        <Pressable
          accessibilityLabel={`Kurangi ${label.toLowerCase()}`}
          accessibilityRole="button"
          onPress={() => onChange(stepClockValue(value, -step, max))}
          style={styles.timeAdjustButton}
        >
          <Text style={styles.timeAdjustSymbol}>−</Text>
        </Pressable>
        <Pressable
          accessibilityLabel={`Tambah ${label.toLowerCase()}`}
          accessibilityRole="button"
          onPress={() => onChange(stepClockValue(value, step, max))}
          style={styles.timeAdjustButton}
        >
          <Text style={styles.timeAdjustSymbol}>+</Text>
        </Pressable>
      </View>
    </View>
  );
}

function ScheduleOption({
  colors,
  label,
  symbol,
  selected,
  onPress,
}: Readonly<{
  colors: Colors;
  label: string;
  symbol: string;
  selected: boolean;
  onPress: () => void;
}>) {
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="radio"
      accessibilityState={{ checked: selected }}
      onPress={onPress}
      style={[
        styles.scheduleOption,
        styles.scheduleOptionTransparent,
        selected && { backgroundColor: colors.surface },
        {
          shadowColor: colors.shadow,
        },
      ]}
    >
      <Text style={[styles.scheduleOptionSymbol, { color: colors.primary }]}>
        {symbol}
      </Text>
      <Text style={[styles.scheduleOptionLabel, { color: colors.text }]}>
        {label}
      </Text>
    </Pressable>
  );
}

function DayOption({
  colors,
  label,
  selected,
  onPress,
}: Readonly<{
  colors: Colors;
  label: string;
  selected: boolean;
  onPress: () => void;
}>) {
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="radio"
      accessibilityState={{ checked: selected }}
      onPress={onPress}
      style={[
        styles.dayOption,
        {
          backgroundColor: selected ? colors.primary : colors.background,
          borderColor: selected ? colors.primary : colors.border,
        },
      ]}
    >
      <Text
        style={[
          styles.dayOptionLabel,
          { color: colors.secondary },
          selected && styles.dayOptionLabelSelected,
        ]}
      >
        {label.charAt(0)}
      </Text>
      <Text
        accessible={false}
        style={[
          styles.dayOptionCaption,
          { color: colors.secondary },
          selected && styles.dayOptionCaptionSelected,
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

function stepClockValue(value: string, delta: number, max: number): string {
  const parsed = Number(value);
  const current = Number.isInteger(parsed) ? parsed : 0;
  const range = max + 1;
  const next = (((current + delta) % range) + range) % range;
  return next.toString().padStart(2, '0');
}

function scheduleSummary(form: EditorForm): string {
  const hour = Number(form.hour);
  const minute = Number(form.minute);
  const validTime =
    form.hour !== '' &&
    form.minute !== '' &&
    Number.isInteger(hour) &&
    hour >= 0 &&
    hour <= 23 &&
    Number.isInteger(minute) &&
    minute >= 0 &&
    minute <= 59;
  if (!validTime) {
    return 'Lengkapi waktu untuk melihat jadwal';
  }
  const time = `${hour.toString().padStart(2, '0')}:${minute
    .toString()
    .padStart(2, '0')}`;
  if (form.scheduleKind === 'ONE_TIME') {
    const now = new Date();
    const todayMinutes = now.getHours() * 60 + now.getMinutes();
    const day = hour * 60 + minute > todayMinutes ? 'Hari ini' : 'Besok';
    return `Sekali • ${day}, pukul ${time}`;
  }
  if (form.repeatDaysMask === 0) {
    return 'Pilih setidaknya satu hari aktif';
  }
  return `${repeatLabel(form.repeatDaysMask)} • Pukul ${time}`;
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

function qrRegistrationErrorCopy(error: unknown): string {
  const code = nativeErrorCode(error);
  if (code.includes('CONFLICT_REVISION')) {
    return 'Alarm berubah. Tutup editor, buka kembali, lalu ulangi pendaftaran QR.';
  }
  if (code.includes('NOT_FOUND')) {
    return 'Draft alarm QR tidak ditemukan.';
  }
  return 'Pemindai QR belum dapat dibuka. Pastikan alarm berupa draft QR nonaktif.';
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
      'NOT_FOUND',
      'INVALID_STATE',
    ].find(code => combined.includes(code)) ?? 'UNKNOWN'
  );
}

function PrimaryButton({
  colors,
  label,
  onPress,
  leading,
}: Readonly<{
  colors: Colors;
  label: string;
  onPress: () => void;
  leading?: string;
}>) {
  return (
    <Pressable
      accessibilityLabel={label}
      accessibilityRole="button"
      onPress={onPress}
      style={[
        styles.primaryButton,
        { backgroundColor: colors.primary, shadowColor: colors.shadow },
      ]}
    >
      {leading !== undefined && (
        <Text style={styles.primaryLeading}>{leading}</Text>
      )}
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

function missionSymbol(type: string): string {
  return type === 'PUSH_UP' ? 'P' : type === 'QR' ? '▦' : '∑';
}

function missionColor(type: string, colors: Colors): string {
  return type === 'PUSH_UP'
    ? '#B96D00'
    : type === 'QR'
    ? '#7055CA'
    : colors.primary;
}

function missionSurface(type: string, colors: Colors): string {
  return type === 'PUSH_UP'
    ? '#FFF1D7'
    : type === 'QR'
    ? '#EEEAFE'
    : colors.primarySurface;
}

function stepTarget(value: string, delta: number): number {
  const current = Number(value);
  return Math.max(1, (Number.isFinite(current) ? current : 1) + delta);
}

const styles = StyleSheet.create({
  safeArea: { flex: 1 },
  gateContainer: {
    alignItems: 'center',
    flex: 1,
    justifyContent: 'center',
    padding: 24,
  },
  homeContainer: { flex: 1, paddingHorizontal: 20, paddingTop: 16 },
  homeContent: { paddingBottom: 20 },
  topBar: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 22,
  },
  brandGroup: { alignItems: 'center', flexDirection: 'row', gap: 12 },
  brandMark: {
    borderRadius: 15,
    height: 46,
    justifyContent: 'center',
    overflow: 'hidden',
    width: 46,
  },
  brandSun: {
    alignSelf: 'center',
    borderRadius: 999,
    height: 16,
    marginTop: 10,
    width: 16,
  },
  title: { fontSize: 32, fontWeight: '700', marginBottom: 40 },
  homeTitle: { fontSize: 21, fontWeight: '800', letterSpacing: -0.4 },
  headerSubtitle: { fontSize: 12, marginTop: 2 },
  offlineBadge: {
    alignItems: 'center',
    borderRadius: 999,
    flexDirection: 'row',
    gap: 6,
    minHeight: 32,
    paddingHorizontal: 10,
  },
  offlineDot: { borderRadius: 999, height: 7, width: 7 },
  offlineLabel: { fontSize: 12, fontWeight: '700' },
  statusGroup: { alignItems: 'center', gap: 16, maxWidth: 480 },
  nextCard: {
    borderRadius: 26,
    marginBottom: 28,
    overflow: 'hidden',
    padding: 24,
  },
  heroGlowLarge: {
    borderRadius: 999,
    height: 190,
    opacity: 0.28,
    position: 'absolute',
    right: -62,
    top: -70,
    width: 190,
  },
  heroGlowSmall: {
    borderRadius: 999,
    height: 54,
    opacity: 0.8,
    position: 'absolute',
    right: 32,
    top: 31,
    width: 54,
  },
  nextEyebrow: {
    color: '#BFDDF8',
    fontSize: 12,
    fontWeight: '800',
    letterSpacing: 1.2,
  },
  nextTime: {
    color: '#FFFFFF',
    fontSize: 52,
    fontWeight: '800',
    letterSpacing: -2,
    marginTop: 10,
  },
  nextTitle: { color: '#FFFFFF', fontSize: 18, fontWeight: '700' },
  nextMetaRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginTop: 18,
  },
  nextMetaPill: {
    backgroundColor: 'rgba(255,255,255,0.13)',
    borderRadius: 999,
    paddingHorizontal: 11,
    paddingVertical: 7,
  },
  nextMetaText: { color: '#EAF4FE', fontSize: 12, fontWeight: '700' },
  readyRow: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 7,
    marginTop: 18,
  },
  readyDot: {
    backgroundColor: '#68DDB4',
    borderRadius: 999,
    height: 8,
    width: 8,
  },
  readyLabel: { color: '#DDF4EB', fontSize: 12, fontWeight: '700' },
  quietHero: { minHeight: 204 },
  quietHeroEyebrow: { fontSize: 11, fontWeight: '800', letterSpacing: 1.2 },
  quietHeroTitle: {
    fontSize: 26,
    fontWeight: '800',
    letterSpacing: -0.6,
    lineHeight: 32,
    marginTop: 14,
    maxWidth: 290,
  },
  quietHeroBody: { fontSize: 14, lineHeight: 21, marginTop: 10, maxWidth: 300 },
  sectionHeader: {
    alignItems: 'center',
    flexDirection: 'row',
    gap: 8,
    marginBottom: 14,
  },
  sectionTitle: { fontSize: 20, fontWeight: '800', letterSpacing: -0.3 },
  countBadge: {
    alignItems: 'center',
    borderRadius: 999,
    justifyContent: 'center',
    minHeight: 25,
    minWidth: 25,
  },
  countLabel: { fontSize: 12, fontWeight: '800' },
  emptyCard: {
    alignItems: 'center',
    borderRadius: 22,
    gap: 10,
    padding: 28,
  },
  emptyIcon: {
    alignItems: 'center',
    borderRadius: 18,
    height: 56,
    justifyContent: 'center',
    marginBottom: 4,
    width: 56,
  },
  emptyIconText: { fontSize: 28, fontWeight: '400' },
  emptyHeading: { fontSize: 18, fontWeight: '800', textAlign: 'center' },
  emptyBody: {
    fontSize: 14,
    lineHeight: 21,
    maxWidth: 300,
    textAlign: 'center',
  },
  homeErrorCard: { borderRadius: 18, gap: 8, marginBottom: 20, padding: 16 },
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
    borderRadius: 20,
    borderWidth: StyleSheet.hairlineWidth,
    elevation: 2,
    flexDirection: 'row',
    minHeight: 94,
    padding: 14,
    shadowOffset: { height: 4, width: 0 },
    shadowOpacity: 0.07,
    shadowRadius: 10,
  },
  alarmEditAction: { alignItems: 'center', flex: 1, flexDirection: 'row' },
  missionIcon: {
    alignItems: 'center',
    borderRadius: 14,
    height: 46,
    justifyContent: 'center',
    marginRight: 12,
    width: 46,
  },
  missionIconText: { fontSize: 18, fontWeight: '900' },
  alarmTimeColumn: { width: 68 },
  alarmTime: { fontSize: 21, fontWeight: '800', letterSpacing: -0.4 },
  alarmSchedule: { fontSize: 12, marginTop: 2 },
  alarmDetail: { flex: 1, paddingHorizontal: 6 },
  alarmLabel: { fontSize: 15, fontWeight: '800' },
  alarmMission: { fontSize: 13, marginTop: 4 },
  alarmToggle: {
    alignItems: 'center',
    borderRadius: 999,
    height: 32,
    justifyContent: 'center',
    minWidth: 52,
    paddingHorizontal: 3,
  },
  toggleThumb: {
    alignSelf: 'flex-start',
    backgroundColor: '#FFFFFF',
    borderRadius: 999,
    height: 26,
    width: 26,
  },
  toggleThumbEnabled: { alignSelf: 'flex-end' },
  historySection: { gap: 10, marginTop: 28 },
  historyItem: {
    alignItems: 'center',
    borderRadius: 16,
    flexDirection: 'row',
    gap: 10,
    minHeight: 52,
    paddingHorizontal: 14,
  },
  historyResult: {
    alignItems: 'center',
    borderRadius: 999,
    height: 28,
    justifyContent: 'center',
    width: 28,
  },
  historyText: { flex: 1, fontSize: 13, fontWeight: '600' },
  heading: { fontSize: 20, fontWeight: '600', textAlign: 'center' },
  body: { fontSize: 16, lineHeight: 24, textAlign: 'center' },
  primaryButton: {
    alignItems: 'center',
    borderRadius: 18,
    elevation: 3,
    flexDirection: 'row',
    gap: 8,
    justifyContent: 'center',
    marginVertical: 12,
    minHeight: 56,
    shadowOffset: { height: 5, width: 0 },
    shadowOpacity: 0.18,
    shadowRadius: 9,
  },
  primaryLeading: { color: '#FFFFFF', fontSize: 23, fontWeight: '400' },
  primaryLabel: { color: '#FFFFFF', fontSize: 16, fontWeight: '700' },
  buildInfo: { fontSize: 12, paddingBottom: 8, textAlign: 'center' },
  editorContainer: { flex: 1, paddingHorizontal: 20, paddingTop: 12 },
  editorHeader: { alignItems: 'center', flexDirection: 'row', gap: 14 },
  editorHeaderCopy: { flex: 1 },
  editorEyebrow: { fontSize: 10, fontWeight: '800', letterSpacing: 1.2 },
  editorTitle: {
    fontSize: 25,
    fontWeight: '800',
    letterSpacing: -0.5,
    marginTop: 2,
  },
  backButton: {
    alignItems: 'center',
    borderRadius: 15,
    borderWidth: StyleSheet.hairlineWidth,
    height: 48,
    justifyContent: 'center',
    width: 48,
  },
  backLabel: { fontSize: 31, fontWeight: '300', lineHeight: 34, marginTop: -3 },
  editorStatus: {
    alignItems: 'center',
    flex: 1,
    gap: 16,
    justifyContent: 'center',
  },
  editorContent: { gap: 14, paddingBottom: 32, paddingTop: 22 },
  timeCard: {
    borderRadius: 26,
    elevation: 3,
    padding: 22,
    shadowOffset: { height: 5, width: 0 },
    shadowOpacity: 0.13,
    shadowRadius: 10,
  },
  timeCardEyebrow: {
    color: '#BFDDF8',
    fontSize: 10,
    fontWeight: '800',
    letterSpacing: 1.2,
  },
  timeCardHeading: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  timeCardTitle: {
    color: '#FFFFFF',
    fontSize: 19,
    fontWeight: '800',
    letterSpacing: -0.3,
    marginTop: 3,
  },
  sunriseMark: {
    alignItems: 'center',
    borderBottomWidth: 2,
    height: 36,
    justifyContent: 'flex-end',
    overflow: 'hidden',
    width: 44,
  },
  sunriseCore: {
    borderRadius: 999,
    height: 25,
    marginBottom: -12,
    width: 25,
  },
  timeCardHelp: {
    color: '#BFDDF8',
    fontSize: 11,
    marginTop: 14,
    textAlign: 'center',
  },
  editorCard: {
    borderRadius: 22,
    borderWidth: StyleSheet.hairlineWidth,
    elevation: 1,
    gap: 12,
    padding: 20,
    shadowOffset: { height: 3, width: 0 },
    shadowOpacity: 0.05,
    shadowRadius: 8,
  },
  editorSectionEyebrow: { fontSize: 10, fontWeight: '800', letterSpacing: 1.2 },
  editorSectionTitle: {
    fontSize: 19,
    fontWeight: '800',
    letterSpacing: -0.2,
    marginBottom: 2,
  },
  fieldTitle: { fontSize: 15, fontWeight: '700' },
  fieldHelp: { fontSize: 13, lineHeight: 19 },
  timePickerRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'center',
    marginTop: 22,
  },
  timeUnit: { alignItems: 'center' },
  timeUnitLabel: {
    color: '#BFDDF8',
    fontSize: 9,
    fontWeight: '800',
    letterSpacing: 1.2,
    marginBottom: 7,
  },
  timeValueBox: {
    backgroundColor: 'rgba(255,255,255,0.11)',
    borderColor: 'rgba(255,255,255,0.22)',
    borderRadius: 18,
    borderWidth: 1,
    overflow: 'hidden',
  },
  timeInput: {
    color: '#FFFFFF',
    fontSize: 46,
    fontWeight: '800',
    height: 76,
    letterSpacing: -1,
    padding: 0,
    textAlign: 'center',
    width: 104,
  },
  timeSeparator: {
    color: '#FFFFFF',
    fontSize: 38,
    fontWeight: '800',
    marginHorizontal: 10,
    marginTop: 12,
  },
  timeAdjustRow: { flexDirection: 'row', gap: 7, marginTop: 8 },
  timeAdjustButton: {
    alignItems: 'center',
    backgroundColor: 'rgba(255,255,255,0.11)',
    borderRadius: 12,
    height: 44,
    justifyContent: 'center',
    width: 48,
  },
  timeAdjustSymbol: { color: '#FFFFFF', fontSize: 21, fontWeight: '600' },
  scheduleToggle: {
    borderRadius: 15,
    flexDirection: 'row',
    gap: 4,
    padding: 4,
  },
  scheduleOption: {
    alignItems: 'center',
    borderRadius: 12,
    elevation: 1,
    flex: 1,
    flexDirection: 'row',
    gap: 8,
    justifyContent: 'center',
    minHeight: 52,
    shadowOffset: { height: 2, width: 0 },
    shadowOpacity: 0.06,
    shadowRadius: 4,
  },
  scheduleOptionTransparent: { backgroundColor: 'transparent' },
  scheduleOptionSymbol: { fontSize: 16, fontWeight: '900' },
  scheduleOptionLabel: { fontSize: 14, fontWeight: '800' },
  weeklyPicker: { gap: 9, marginTop: 4 },
  dayPickerLabel: { fontSize: 12, fontWeight: '600' },
  dayRow: { flexDirection: 'row', justifyContent: 'space-between' },
  dayOption: {
    alignItems: 'center',
    borderRadius: 14,
    borderWidth: 1,
    height: 58,
    justifyContent: 'center',
    width: 38,
  },
  dayOptionLabel: { fontSize: 15, fontWeight: '900' },
  dayOptionLabelSelected: { color: '#FFFFFF' },
  dayOptionCaption: { fontSize: 8, fontWeight: '700', marginTop: 2 },
  dayOptionCaptionSelected: { color: '#DDEEFF' },
  scheduleSummary: {
    alignItems: 'center',
    borderRadius: 15,
    flexDirection: 'row',
    gap: 11,
    padding: 13,
  },
  scheduleSummaryIcon: {
    alignItems: 'center',
    borderRadius: 11,
    height: 36,
    justifyContent: 'center',
    width: 36,
  },
  scheduleSummaryIconText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '900',
  },
  scheduleSummaryCopy: { flex: 1 },
  scheduleSummaryEyebrow: { fontSize: 9, fontWeight: '800', letterSpacing: 1 },
  scheduleSummaryText: { fontSize: 13, fontWeight: '700', marginTop: 2 },
  textInput: {
    borderRadius: 14,
    borderWidth: 1,
    fontSize: 16,
    minHeight: 54,
    paddingHorizontal: 16,
  },
  missionChoices: { flexDirection: 'row', gap: 8 },
  missionCard: {
    borderRadius: 16,
    borderWidth: 1.5,
    flex: 1,
    minHeight: 132,
    padding: 12,
  },
  missionCardIcon: {
    alignItems: 'center',
    borderRadius: 11,
    height: 34,
    justifyContent: 'center',
    width: 34,
  },
  missionCardSymbol: { color: '#FFFFFF', fontSize: 16, fontWeight: '900' },
  missionCardTitle: { fontSize: 14, fontWeight: '800', marginTop: 12 },
  missionCardDescription: { fontSize: 10, lineHeight: 14, marginTop: 2 },
  selectedMark: {
    alignItems: 'center',
    borderRadius: 999,
    height: 20,
    justifyContent: 'center',
    position: 'absolute',
    right: 8,
    top: 8,
    width: 20,
  },
  selectedMarkText: { color: '#FFFFFF', fontSize: 11, fontWeight: '900' },
  targetRow: {
    alignItems: 'center',
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: 4,
  },
  stepper: {
    alignItems: 'center',
    borderRadius: 14,
    borderWidth: 1,
    flexDirection: 'row',
    minHeight: 48,
  },
  stepperButton: {
    alignItems: 'center',
    height: 48,
    justifyContent: 'center',
    width: 48,
  },
  stepperSymbol: { fontSize: 23, fontWeight: '700' },
  targetInput: {
    fontSize: 17,
    fontWeight: '800',
    height: 48,
    padding: 0,
    textAlign: 'center',
    width: 42,
  },
  soundRow: {
    alignItems: 'center',
    flexDirection: 'row',
  },
  soundIcon: {
    alignItems: 'center',
    borderRadius: 13,
    height: 44,
    justifyContent: 'center',
    width: 44,
  },
  soundIconText: { fontSize: 20, fontWeight: '800' },
  soundCopy: { flex: 1, marginLeft: 12 },
  soonBadge: { borderRadius: 999, paddingHorizontal: 10, paddingVertical: 6 },
  soonText: { fontSize: 11, fontWeight: '700' },
  infoBanner: {
    alignItems: 'flex-start',
    borderRadius: 14,
    flexDirection: 'row',
    gap: 10,
    padding: 13,
  },
  infoMark: {
    fontSize: 14,
    fontWeight: '900',
    textAlign: 'center',
    width: 16,
  },
  infoText: { flex: 1, fontSize: 12, lineHeight: 18 },
  qrRegistrationBox: { gap: 12 },
  qrRegistrationCopy: { gap: 4 },
  qrRegistrationButton: {
    alignItems: 'center',
    borderRadius: 16,
    justifyContent: 'center',
    minHeight: 52,
  },
  editorErrorBanner: {
    borderRadius: 14,
    padding: 13,
  },
  editorError: {
    fontSize: 13,
    fontWeight: '600',
    lineHeight: 19,
    textAlign: 'center',
  },
  editorSaveButton: {
    alignItems: 'center',
    borderRadius: 18,
    minHeight: 56,
    justifyContent: 'center',
    marginTop: 2,
  },
});

export default App;
