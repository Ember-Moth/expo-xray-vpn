import { NativeModule, requireNativeModule } from 'expo-modules-core';

import type {
  ExpoXrayVpnConfig,
  ExpoXrayVpnModuleEvents,
  ExpoXrayVpnPermissionResult,
  ExpoXrayVpnState,
} from './ExpoXrayVpn.types';

declare class ExpoXrayVpnModule extends NativeModule<ExpoXrayVpnModuleEvents> {
  connect(config: ExpoXrayVpnConfig): Promise<ExpoXrayVpnState>;
  convertShareLinksToXrayJson(text: string): Promise<string>;
  convertXrayJsonToShareLinks(xrayConfigJson: string): Promise<string>;
  disconnect(): Promise<ExpoXrayVpnState>;
  getFreePorts(count: number): Promise<number[]>;
  getState(): Promise<ExpoXrayVpnState>;
  protectSocket(fd: number): Promise<boolean>;
  requestPermission(): Promise<ExpoXrayVpnPermissionResult>;
}

export default requireNativeModule<ExpoXrayVpnModule>('ExpoXrayVpn');
