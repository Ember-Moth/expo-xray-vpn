export type StarlinkVpnConnectionState =
  | 'disconnected'
  | 'preparing'
  | 'connecting'
  | 'connected'
  | 'disconnecting'
  | 'error';

export type StarlinkVpnRoute = {
  address: string;
  prefix: number;
};

export type StarlinkVpnConfig = {
  allowedApplications?: string[];
  disallowedApplications?: string[];
  dnsServer?: string;
  mtu?: number;
  profileId?: string;
  profileName?: string;
  routes?: StarlinkVpnRoute[];
  tunAddress?: string;
  tunPrefix?: number;
  xrayConfigJson: string;
};

export type StarlinkVpnPermissionResult = {
  granted: boolean;
};

export type StarlinkVpnState = {
  connectedAt?: number;
  error?: string;
  errorCode?: string;
  lastChangedAt?: number;
  profileId?: string;
  profileName?: string;
  state: StarlinkVpnConnectionState;
};

export type StarlinkVpnStateChangeEvent = StarlinkVpnState;

export type StarlinkVpnTrafficEvent = {
  downloadBytes: number;
  timestamp: number;
  uploadBytes: number;
};

export type StarlinkVpnModuleEvents = {
  onStateChange: (event: StarlinkVpnStateChangeEvent) => void;
  onTrafficUpdate: (event: StarlinkVpnTrafficEvent) => void;
};
