/**
 * @format
 */

import React from 'react';
import ReactTestRenderer from 'react-test-renderer';
import App from '../App';

jest.mock('../specs/NativeAlarmFeasibility', () => ({
  __esModule: true,
  default: {
    getCapabilities: jest.fn().mockResolvedValue({
      androidApi: 37,
      canScheduleExactAlarms: true,
      canUseFullScreenIntent: true,
    }),
    openExactAlarmSettings: jest.fn(),
    scheduleTestAlarm: jest.fn().mockResolvedValue(true),
    stopTestAlarm: jest.fn(),
    openPoseSpike: jest.fn(),
  },
}));

test('renders correctly', async () => {
  await ReactTestRenderer.act(async () => {
    ReactTestRenderer.create(<App />);
    await Promise.resolve();
  });
});
