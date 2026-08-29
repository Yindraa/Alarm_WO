/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 */

import React, {useEffect, useState} from 'react';
import {
  Alert,
  Button,
  PermissionsAndroid,
  Platform,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {SafeAreaProvider, SafeAreaView} from 'react-native-safe-area-context';
import NativeAlarmFeasibility from './specs/NativeAlarmFeasibility';

type Capability = {
  androidApi: number;
  canScheduleExactAlarms: boolean;
  canUseFullScreenIntent: boolean;
};

function App() {
  const [capability, setCapability] = useState<Capability | null>(null);
  const [message, setMessage] = useState('Ready');

  const refreshCapability = async () => {
    try {
      setCapability(await NativeAlarmFeasibility.getCapabilities());
    } catch (error) {
      setMessage(String(error));
    }
  };

  useEffect(() => {
    refreshCapability();
  }, []);

  const requestNotificationPermission = async () => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS,
      );
    }
  };

  const scheduleAlarm = async () => {
    try {
      await requestNotificationPermission();
      const triggerAt = Date.now() + 15_000;
      await NativeAlarmFeasibility.scheduleTestAlarm(triggerAt);
      setMessage(`Test alarm dijadwalkan: ${new Date(triggerAt).toLocaleTimeString()}`);
      await refreshCapability();
    } catch (error) {
      Alert.alert('Alarm spike gagal', String(error));
    }
  };

  const openPoseSpike = async () => {
    const result = await PermissionsAndroid.request(
      PermissionsAndroid.PERMISSIONS.CAMERA,
    );
    if (result !== PermissionsAndroid.RESULTS.GRANTED) {
      setMessage('Camera permission belum diberikan');
      return;
    }
    NativeAlarmFeasibility.openPoseSpike();
  };

  return (
    <SafeAreaProvider>
      <StatusBar barStyle="light-content" />
      <SafeAreaView style={styles.safeArea}>
        <ScrollView contentContainerStyle={styles.container}>
          <Text style={styles.eyebrow}>MISSION ALARM</Text>
          <Text style={styles.title}>Technical Feasibility</Text>
          <Text style={styles.description}>
            Spike ini menguji API native Android. Alarm test memiliki tombol stop
            demi keselamatan tester; tombol tersebut bukan flow produk.
          </Text>

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Native alarm</Text>
            <Text>Android API: {capability?.androidApi ?? 'checking...'}</Text>
            <Text>
              Exact alarm: {String(capability?.canScheduleExactAlarms ?? false)}
            </Text>
            <Text>
              Full-screen intent: {String(capability?.canUseFullScreenIntent ?? false)}
            </Text>
            <View style={styles.buttonGap} />
            <Button title="Schedule in 15 seconds" onPress={scheduleAlarm} />
            <View style={styles.buttonGap} />
            <Button
              title="Open exact alarm settings"
              onPress={() => NativeAlarmFeasibility.openExactAlarmSettings()}
            />
            <View style={styles.buttonGap} />
            <Button
              title="Stop test alarm"
              color="#b42318"
              onPress={() => NativeAlarmFeasibility.stopTestAlarm()}
            />
          </View>

          <View style={styles.card}>
            <Text style={styles.cardTitle}>Pose detection</Text>
            <Text>
              Membuka CameraX dan MediaPipe Pose Landmarker pada native activity.
            </Text>
            <View style={styles.buttonGap} />
            <Button title="Open pose spike" onPress={openPoseSpike} />
          </View>

          <Text style={styles.status}>Status: {message}</Text>
        </ScrollView>
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safeArea: {flex: 1, backgroundColor: '#101828'},
  container: {padding: 24, gap: 18},
  eyebrow: {color: '#84caff', fontSize: 12, fontWeight: '700', letterSpacing: 2},
  title: {color: '#ffffff', fontSize: 30, fontWeight: '800'},
  description: {color: '#d0d5dd', fontSize: 16, lineHeight: 24},
  card: {backgroundColor: '#ffffff', borderRadius: 16, padding: 20},
  cardTitle: {fontSize: 20, fontWeight: '700', marginBottom: 10},
  buttonGap: {height: 12},
  status: {color: '#d0d5dd', fontSize: 14},
});

export default App;
