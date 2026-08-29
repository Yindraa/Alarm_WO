import type {TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export type AlarmCapabilities = {
  androidApi: number;
  canScheduleExactAlarms: boolean;
  canUseFullScreenIntent: boolean;
};

export interface Spec extends TurboModule {
  getCapabilities(): Promise<AlarmCapabilities>;
  openExactAlarmSettings(): void;
  scheduleTestAlarm(triggerAtMillis: number): Promise<boolean>;
  stopTestAlarm(): void;
  openPoseSpike(): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>(
  'NativeAlarmFeasibility',
);
