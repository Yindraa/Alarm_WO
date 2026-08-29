# Mission Alarm Mobile Feasibility Spike

Disposable React Native 0.87 project for validating the two highest-risk assumptions:

1. Android exact alarm, full-screen intent, foreground audio, and lock-screen entry.
2. CameraX + MediaPipe Pose Landmarker live-stream processing and a provisional push-up state machine.

This is not production application code. The `STOP TEST` notification action exists only so a feasibility alarm cannot trap a tester.

## Requirements

- Node.js 22.13 or newer
- JDK 17
- Android SDK Platform 37
- Android Build Tools 37.0.0
- Android platform-tools
- Physical Android device recommended
- Exact alarm, full-screen intent, notification, and camera permissions as required by the device

## Run

```sh
npm test
npm run lint

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
npm run android
```

## Manual alarm matrix

Run the 15-second alarm in each condition and record actual trigger delay and UI behavior:

- App foreground, screen unlocked.
- App background, screen unlocked.
- Screen locked.
- Device in Doze/idle.
- App process removed from recent apps.
- Exact alarm permission denied and then restored.
- Notification permission denied.
- Full-screen intent access denied, where applicable.
- Two alarms scheduled close together after persistence is added.
- Reboot after scheduler persistence is added.

## Manual pose matrix

- Bright and dim room.
- Full body and partial body framing.
- Front and side camera angles.
- Valid top-down-top movement.
- Partial depth.
- Arm-only motion.
- Poor body alignment.
- Slow and fast repetitions.
- Temporary body loss and recovery.

Thresholds are intentionally provisional. Passing compilation does not validate exercise accuracy.

