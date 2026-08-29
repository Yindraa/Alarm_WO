import type {CodegenTypes, TurboModule} from 'react-native';
import {TurboModuleRegistry} from 'react-native';

export type ContractInfo = Readonly<{
  contractVersion: CodegenTypes.Int32;
  minimumClientContractVersion: CodegenTypes.Int32;
  moduleName: string;
  nativeBuildVersion: string;
}>;

export interface Spec extends TurboModule {
  getContractInfo(): Promise<ContractInfo>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('NativeMissionAlarm');
