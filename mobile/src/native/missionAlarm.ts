import NativeMissionAlarm, {
  type ContractInfo,
} from './specs/NativeMissionAlarm';

export const MISSION_ALARM_CONTRACT_VERSION = 1;

export async function getContractInfo(): Promise<ContractInfo> {
  const info = await NativeMissionAlarm.getContractInfo();

  if (
    info.contractVersion < MISSION_ALARM_CONTRACT_VERSION ||
    info.minimumClientContractVersion > MISSION_ALARM_CONTRACT_VERSION
  ) {
    throw new Error('UNSUPPORTED_CONTRACT_VERSION');
  }

  return info;
}

export type {ContractInfo};
