import {useEffect, useRef, useState} from 'react';
import {
  ActivityIndicator,
  AppState,
  Pressable,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  useColorScheme,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {
  getActiveRuntimeSnapshot,
  getAlarmEditorSnapshot,
  getContractInfo,
  getHomeSnapshot,
  launchActiveInstance,
  type AlarmEditorSnapshot,
  type ContractInfo,
  type HomeSnapshot,
} from './src/native/missionAlarm';

type BootstrapState =
  | Readonly<{status: 'loading'; slow: boolean}>
  | Readonly<{status: 'routing'}>
  | Readonly<{status: 'ready'; info: ContractInfo; home: HomeSnapshot}>
  | Readonly<{status: 'error'}>;

type Screen = Readonly<{name: 'home'}> | Readonly<{name: 'editor'; alarmId: string | null}>;

function App() {
  const isDarkMode = useColorScheme() === 'dark';
  const lastAppState = useRef(AppState.currentState);
  const [attempt, setAttempt] = useState(0);
  const [screen, setScreen] = useState<Screen>({name: 'home'});
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
      setState({status: 'loading', slow: false});
    }
    const slowTimer = setTimeout(() => {
      if (active) {
        setState(current =>
          current.status === 'loading'
            ? {status: 'loading', slow: true}
            : current,
        );
      }
    }, 2000);

    async function routeActive(instanceId: string, revision: number) {
      setState({status: 'routing'});
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
        setState({status: 'ready', info, home});
      } catch {
        if (active) {
          setState({status: 'error'});
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
        onBack={() => setScreen({name: 'home'})}
      />
    );
  } else {
    content = (
      <HomeShell
        colors={colors}
        home={state.home}
        info={state.info}
        onOpenEditor={alarmId => setScreen({name: 'editor', alarmId})}
      />
    );
  }

  return (
    <SafeAreaProvider>
      <SafeAreaView
        edges={['top', 'right', 'bottom', 'left']}
        style={[styles.safeArea, {backgroundColor: colors.background}]}>
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
  state: Exclude<BootstrapState, {status: 'ready'}>;
  onRetry: () => void;
}>) {
  return (
    <View style={styles.gateContainer}>
      <Text accessibilityRole="header" style={[styles.title, {color: colors.text}]}>
        Mission Alarm
      </Text>
      {state.status !== 'error' ? (
        <View accessibilityLiveRegion="polite" style={styles.statusGroup}>
          <ActivityIndicator color={colors.primary} size="large" />
          <Text style={[styles.heading, {color: colors.text}]}>
            {state.status === 'routing'
              ? 'Membuka alarm aktif…'
              : 'Memeriksa alarm aktif…'}
          </Text>
          {state.status === 'loading' && state.slow && (
            <Text style={[styles.body, {color: colors.secondary}]}>
              Menyiapkan status alarm dengan aman.
            </Text>
          )}
        </View>
      ) : (
        <View accessibilityLiveRegion="assertive" style={styles.statusGroup}>
          <Text style={[styles.heading, {color: colors.danger}]}>
            Data aplikasi perlu dipulihkan
          </Text>
          <Text style={[styles.body, {color: colors.secondary}]}>
            Beranda dikunci sementara agar status alarm tidak ditampilkan keliru.
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
}: Readonly<{
  colors: Colors;
  home: HomeSnapshot;
  info: ContractInfo;
  onOpenEditor: (alarmId: string | null) => void;
}>) {
  const nextAlarm = home.alarms
    .filter(alarm => alarm.enabled && alarm.nextOccurrenceAtUtcMs !== null)
    .sort((left, right) =>
      Number(left.nextOccurrenceAtUtcMs) - Number(right.nextOccurrenceAtUtcMs),
    )[0];

  return (
    <View style={styles.homeContainer}>
      <View style={styles.topBar}>
        <Text accessibilityRole="header" style={[styles.homeTitle, {color: colors.text}]}>
          Mission Alarm
        </Text>
        <Text style={[styles.offlineLabel, {color: colors.secondary}]}>Offline</Text>
      </View>
      <ScrollView contentContainerStyle={styles.homeContent}>
        {nextAlarm && (
          <View style={[styles.nextCard, {backgroundColor: colors.primary}]}>
            <Text style={styles.nextEyebrow}>ALARM BERIKUTNYA</Text>
            <Text style={styles.nextTime}>{formatTime(nextAlarm.localTimeMinutes)}</Text>
            <Text style={styles.nextLabel}>
              {nextAlarm.label} · {missionLabel(nextAlarm.missionType, nextAlarm.target)}
            </Text>
          </View>
        )}

        <View style={styles.sectionHeader}>
          <Text style={[styles.sectionTitle, {color: colors.text}]}>Alarm</Text>
          <Text style={[styles.countLabel, {color: colors.secondary}]}>
            {home.alarms.length}
          </Text>
        </View>

        {home.alarms.length === 0 ? (
          <View style={[styles.emptyCard, {backgroundColor: colors.surface}]}>
            <Text style={[styles.heading, {color: colors.text}]}>Belum ada alarm</Text>
            <Text style={[styles.body, {color: colors.secondary}]}>
              Buat alarm pertamamu dan pilih misi yang harus diselesaikan saat alarm
              berbunyi.
            </Text>
          </View>
        ) : (
          <View style={styles.alarmList}>
            {home.alarms.map(alarm => (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={`Edit alarm ${alarm.label}`}
                key={alarm.id}
                onPress={() => onOpenEditor(alarm.id)}
                style={[styles.alarmRow, {backgroundColor: colors.surface}]}>
                <View style={styles.alarmTimeColumn}>
                  <Text style={[styles.alarmTime, {color: colors.text}]}>
                    {formatTime(alarm.localTimeMinutes)}
                  </Text>
                  <Text style={[styles.alarmSchedule, {color: colors.secondary}]}>
                    {repeatLabel(alarm.repeatDaysMask)}
                  </Text>
                </View>
                <View style={styles.alarmDetail}>
                  <Text numberOfLines={1} style={[styles.alarmLabel, {color: colors.text}]}>
                    {alarm.label}
                  </Text>
                  <Text style={[styles.alarmMission, {color: colors.secondary}]}>
                    {missionLabel(alarm.missionType, alarm.target)}
                  </Text>
                </View>
                <Text
                  style={[
                    styles.alarmState,
                    {color: alarm.enabled ? colors.success : colors.secondary},
                  ]}>
                  {alarm.enabled ? 'ON' : 'OFF'}
                </Text>
              </Pressable>
            ))}
          </View>
        )}

        {home.recentHistory.length > 0 && (
          <View style={styles.historySection}>
            <Text style={[styles.sectionTitle, {color: colors.text}]}>Riwayat terbaru</Text>
            {home.recentHistory.map(item => (
              <Text key={item.instanceId} style={[styles.historyItem, {color: colors.secondary}]}>
                {item.result === 'SUCCESS' ? '✓' : '•'} {missionLabel(item.missionType, item.target)}
              </Text>
            ))}
          </View>
        )}
      </ScrollView>
      <PrimaryButton colors={colors} label="Tambah alarm" onPress={() => onOpenEditor(null)} />
      <Text style={[styles.buildInfo, {color: colors.secondary}]}>
        Kontrak native v{info.contractVersion} · build {info.nativeBuildVersion}
      </Text>
    </View>
  );
}

function EditorShell({
  alarmId,
  colors,
  onBack,
}: Readonly<{alarmId: string | null; colors: Colors; onBack: () => void}>) {
  const [state, setState] = useState<
    | Readonly<{status: 'loading'}>
    | Readonly<{status: 'ready'; snapshot: AlarmEditorSnapshot}>
    | Readonly<{status: 'error'}>
  >({status: 'loading'});

  useEffect(() => {
    let active = true;
    getAlarmEditorSnapshot(alarmId)
      .then(snapshot => {
        if (active) {
          setState({status: 'ready', snapshot});
        }
      })
      .catch(() => {
        if (active) {
          setState({status: 'error'});
        }
      });
    return () => {
      active = false;
    };
  }, [alarmId]);

  return (
    <View style={styles.editorContainer}>
      <Pressable accessibilityRole="button" onPress={onBack} style={styles.backButton}>
        <Text style={[styles.backLabel, {color: colors.primary}]}>‹ Kembali</Text>
      </Pressable>
      <Text accessibilityRole="header" style={[styles.homeTitle, {color: colors.text}]}>
        {alarmId === null ? 'Buat alarm' : 'Edit alarm'}
      </Text>
      {state.status === 'loading' && (
        <View style={styles.editorStatus}>
          <ActivityIndicator color={colors.primary} />
          <Text style={[styles.body, {color: colors.secondary}]}>Memuat konfigurasi…</Text>
        </View>
      )}
      {state.status === 'error' && (
        <View style={styles.editorStatus}>
          <Text style={[styles.heading, {color: colors.danger}]}>Konfigurasi belum tersedia</Text>
          <Text style={[styles.body, {color: colors.secondary}]}>Kembali dan coba buka editor lagi.</Text>
        </View>
      )}
      {state.status === 'ready' && (
        <View style={[styles.editorCard, {backgroundColor: colors.surface}]}>
          <Text style={[styles.editorTime, {color: colors.text}]}>
            {formatTime(state.snapshot.alarm?.localTimeMinutes ?? 420)}
          </Text>
          <EditorValue colors={colors} label="Jadwal" value={repeatLabel(state.snapshot.alarm?.repeatDaysMask ?? 31)} />
          <EditorValue colors={colors} label="Label" value={state.snapshot.alarm?.label ?? 'Belum diisi'} />
          <EditorValue
            colors={colors}
            label="Misi"
            value={missionLabel(
              state.snapshot.alarm?.mission.missionType ?? 'MATH',
              state.snapshot.alarm?.mission.target ?? 3,
            )}
          />
          <EditorValue colors={colors} label="Suara" value="Classic" />
          <Text style={[styles.editorNote, {color: colors.secondary}]}>
            Input dan penyimpanan aman akan diaktifkan pada increment editor berikutnya.
          </Text>
          <Pressable
            accessibilityRole="button"
            disabled
            style={[styles.disabledButton, {backgroundColor: colors.disabled}]}>
            <Text style={styles.primaryLabel}>Simpan alarm</Text>
          </Pressable>
        </View>
      )}
    </View>
  );
}

function EditorValue({colors, label, value}: Readonly<{colors: Colors; label: string; value: string}>) {
  return (
    <View style={styles.editorValue}>
      <Text style={[styles.editorValueLabel, {color: colors.secondary}]}>{label}</Text>
      <Text style={[styles.editorValueText, {color: colors.text}]}>{value}</Text>
    </View>
  );
}

function PrimaryButton({colors, label, onPress}: Readonly<{colors: Colors; label: string; onPress: () => void}>) {
  return (
    <Pressable
      accessibilityRole="button"
      onPress={onPress}
      style={[styles.primaryButton, {backgroundColor: colors.primary}]}>
      <Text style={styles.primaryLabel}>{label}</Text>
    </Pressable>
  );
}

function formatTime(minutes: number): string {
  const hour = Math.floor(minutes / 60).toString().padStart(2, '0');
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
  return days.filter((_day, index) => Math.floor(mask / dayBits[index]) % 2 === 1).join(', ');
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
  disabled: '#94A3B8',
  danger: '#B91C1C',
  success: '#166534',
};

const darkColors: Colors = {
  background: '#07111F',
  surface: '#111E2E',
  text: '#F8FAFC',
  secondary: '#CBD5E1',
  primary: '#0369A1',
  disabled: '#475569',
  danger: '#F87171',
  success: '#4ADE80',
};

const styles = StyleSheet.create({
  safeArea: {flex: 1},
  gateContainer: {alignItems: 'center', flex: 1, justifyContent: 'center', padding: 24},
  homeContainer: {flex: 1, paddingHorizontal: 24, paddingTop: 20},
  homeContent: {paddingBottom: 24},
  topBar: {alignItems: 'center', flexDirection: 'row', justifyContent: 'space-between', marginBottom: 24},
  title: {fontSize: 32, fontWeight: '700', marginBottom: 40},
  homeTitle: {fontSize: 28, fontWeight: '700'},
  offlineLabel: {fontSize: 14, fontWeight: '600'},
  statusGroup: {alignItems: 'center', gap: 16, maxWidth: 480},
  nextCard: {borderRadius: 20, marginBottom: 28, padding: 24},
  nextEyebrow: {color: '#E0F2FE', fontSize: 12, fontWeight: '700', letterSpacing: 1},
  nextTime: {color: '#FFFFFF', fontSize: 38, fontWeight: '800', marginTop: 8},
  nextLabel: {color: '#E0F2FE', fontSize: 15, marginTop: 4},
  sectionHeader: {alignItems: 'center', flexDirection: 'row', gap: 8, marginBottom: 12},
  sectionTitle: {fontSize: 20, fontWeight: '700'},
  countLabel: {fontSize: 14, fontWeight: '600'},
  emptyCard: {borderRadius: 20, gap: 12, padding: 24},
  alarmList: {gap: 12},
  alarmRow: {alignItems: 'center', borderRadius: 16, flexDirection: 'row', minHeight: 80, padding: 16},
  alarmTimeColumn: {width: 82},
  alarmTime: {fontSize: 22, fontWeight: '700'},
  alarmSchedule: {fontSize: 12, marginTop: 2},
  alarmDetail: {flex: 1, paddingHorizontal: 10},
  alarmLabel: {fontSize: 16, fontWeight: '700'},
  alarmMission: {fontSize: 13, marginTop: 4},
  alarmState: {fontSize: 13, fontWeight: '800'},
  historySection: {gap: 10, marginTop: 28},
  historyItem: {fontSize: 14},
  heading: {fontSize: 20, fontWeight: '600', textAlign: 'center'},
  body: {fontSize: 16, lineHeight: 24, textAlign: 'center'},
  primaryButton: {alignItems: 'center', borderRadius: 14, marginVertical: 12, padding: 16},
  primaryLabel: {color: '#FFFFFF', fontSize: 16, fontWeight: '700'},
  buildInfo: {fontSize: 12, paddingBottom: 8, textAlign: 'center'},
  editorContainer: {flex: 1, padding: 24},
  backButton: {alignSelf: 'flex-start', minHeight: 48, justifyContent: 'center'},
  backLabel: {fontSize: 16, fontWeight: '700'},
  editorStatus: {alignItems: 'center', flex: 1, gap: 16, justifyContent: 'center'},
  editorCard: {borderRadius: 20, gap: 4, marginTop: 24, padding: 24},
  editorTime: {fontSize: 44, fontWeight: '800', marginBottom: 16, textAlign: 'center'},
  editorValue: {alignItems: 'center', flexDirection: 'row', minHeight: 52},
  editorValueLabel: {fontSize: 14, width: 72},
  editorValueText: {flex: 1, fontSize: 16, fontWeight: '600'},
  editorNote: {fontSize: 13, lineHeight: 20, marginTop: 16, textAlign: 'center'},
  disabledButton: {alignItems: 'center', borderRadius: 14, marginTop: 16, padding: 16},
});

export default App;
