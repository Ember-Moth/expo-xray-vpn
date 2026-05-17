export type ExpoXrayVpnConnectionState =
  | 'disconnected'
  | 'preparing'
  | 'connecting'
  | 'connected'
  | 'disconnecting'
  | 'error';

export type ExpoXrayVpnRoute = {
  address: string;
  prefix: number;
};

export type ExpoXrayVpnConfig = {
  allowedApplications?: string[];
  disallowedApplications?: string[];
  dnsServer?: string;
  mtu?: number;
  profileId?: string;
  profileName?: string;
  routes?: ExpoXrayVpnRoute[];
  tunAddress?: string;
  tunPrefix?: number;
  xrayConfigJson: string;
};

export type ExpoXrayVpnPermissionResult = {
  granted: boolean;
};

export type ExpoXrayVpnState = {
  connectedAt?: number;
  error?: string;
  errorCode?: string;
  lastChangedAt?: number;
  profileId?: string;
  profileName?: string;
  state: ExpoXrayVpnConnectionState;
};

export type ExpoXrayVpnStateChangeEvent = ExpoXrayVpnState;

export type ExpoXrayVpnTrafficEvent = {
  downloadBytes: number;
  timestamp: number;
  uploadBytes: number;
};

export type ExpoXrayVpnModuleEvents = {
  onStateChange: (event: ExpoXrayVpnStateChangeEvent) => void;
  onTrafficUpdate: (event: ExpoXrayVpnTrafficEvent) => void;
};
