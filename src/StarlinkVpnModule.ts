import { NativeModule, requireNativeModule } from 'expo-modules-core';

import type {
  StarlinkVpnConfig,
  StarlinkVpnModuleEvents,
  StarlinkVpnPermissionResult,
  StarlinkVpnState,
} from './StarlinkVpn.types';

declare class StarlinkVpnModule extends NativeModule<StarlinkVpnModuleEvents> {
  connect(config: StarlinkVpnConfig): Promise<StarlinkVpnState>;
  convertShareLinksToXrayJson(text: string): Promise<string>;
  convertXrayJsonToShareLinks(xrayConfigJson: string): Promise<string>;
  disconnect(): Promise<StarlinkVpnState>;
  getFreePorts(count: number): Promise<number[]>;
  getState(): Promise<StarlinkVpnState>;
  protectSocket(fd: number): Promise<boolean>;
  requestPermission(): Promise<StarlinkVpnPermissionResult>;
}

export default requireNativeModule<StarlinkVpnModule>('StarlinkVpn');
