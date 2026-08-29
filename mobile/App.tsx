import {useEffect, useState} from 'react';
import {
  ActivityIndicator,
  StatusBar,
  StyleSheet,
  Text,
  useColorScheme,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import {
  getContractInfo,
  type ContractInfo,
} from './src/native/missionAlarm';

type BootstrapState =
  | Readonly<{status: 'loading'}>
  | Readonly<{status: 'ready'; info: ContractInfo}>
  | Readonly<{status: 'error'}>;

function App() {
  const isDarkMode = useColorScheme() === 'dark';
  const [state, setState] = useState<BootstrapState>({status: 'loading'});

  useEffect(() => {
    let active = true;

    getContractInfo()
      .then(info => {
        if (active) {
          setState({status: 'ready', info});
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
  }, []);

  const colors = isDarkMode ? darkColors : lightColors;

  return (
    <SafeAreaProvider>
      <SafeAreaView
        edges={['top', 'right', 'bottom', 'left']}
        style={[styles.safeArea, {backgroundColor: colors.background}]}>
        <StatusBar barStyle={isDarkMode ? 'light-content' : 'dark-content'} />
        <View style={styles.container}>
        <Text accessibilityRole="header" style={[styles.title, {color: colors.text}]}>
          Mission Alarm
        </Text>

        {state.status === 'loading' && (
          <View accessibilityLiveRegion="polite" style={styles.statusGroup}>
            <ActivityIndicator color={colors.primary} size="large" />
            <Text style={[styles.body, {color: colors.secondary}]}>
              Memeriksa kesiapan aplikasi…
            </Text>
          </View>
        )}

        {state.status === 'ready' && (
          <View accessibilityLiveRegion="polite" style={styles.statusGroup}>
            <View
              accessibilityLabel="Fondasi aplikasi siap"
              style={[styles.readyMark, {backgroundColor: colors.success}]}
            />
            <Text style={[styles.heading, {color: colors.text}]}>
              Fondasi aplikasi siap
            </Text>
            <Text style={[styles.body, {color: colors.secondary}]}>
              Kontrak native v{state.info.contractVersion} · build{' '}
              {state.info.nativeBuildVersion}
            </Text>
          </View>
        )}

        {state.status === 'error' && (
          <View accessibilityLiveRegion="assertive" style={styles.statusGroup}>
            <Text style={[styles.heading, {color: colors.danger}]}>
              Fondasi native belum tersedia
            </Text>
            <Text style={[styles.body, {color: colors.secondary}]}>
              Aplikasi tidak akan membuka alur alarm sebelum kontrak native dapat
              diverifikasi.
            </Text>
          </View>
        )}
        </View>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const lightColors = {
  background: '#F8FAFC',
  text: '#0F172A',
  secondary: '#475569',
  primary: '#0369A1',
  success: '#166534',
  danger: '#B91C1C',
};

const darkColors = {
  background: '#07111F',
  text: '#F8FAFC',
  secondary: '#CBD5E1',
  primary: '#38BDF8',
  success: '#4ADE80',
  danger: '#F87171',
};

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
  },
  container: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  title: {
    fontSize: 32,
    fontWeight: '700',
    marginBottom: 40,
  },
  statusGroup: {
    alignItems: 'center',
    gap: 16,
    maxWidth: 480,
  },
  readyMark: {
    borderRadius: 24,
    height: 48,
    width: 48,
  },
  heading: {
    fontSize: 20,
    fontWeight: '600',
    textAlign: 'center',
  },
  body: {
    fontSize: 16,
    lineHeight: 24,
    textAlign: 'center',
  },
});

export default App;
