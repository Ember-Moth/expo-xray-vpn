# expo-xray-vpn

Android-only Expo native module for running an Xray-backed VPN connection from a React Native app.

This package bridges Expo Modules, Android `VpnService`, and a `libXray.aar` runtime. It is intended for custom development builds, not Expo Go.

## Features

- Requests Android VPN permission from JavaScript.
- Starts and stops a foreground `VpnService`.
- Creates a TUN interface and passes its file descriptor to libXray.
- Runs Xray from a JSON configuration string.
- Supports allowed and disallowed Android application package rules.
- Exposes state change events for connection lifecycle updates.
- Provides helpers for share-link conversion and free local port discovery through libXray.

## Requirements

- Android app built with Expo prebuild, EAS build, or a local development build.
- `expo-modules-core` available in the host project.
- A compatible `libXray.aar` resolved either from Maven or from `android/libs/libXray.aar`.
- Xray geo assets available under `android/src/main/assets/xray` or downloaded by the Gradle task.

## Installation

Install the package in an Expo or React Native project that supports native modules:

```sh
npm install expo-xray-vpn
```

Rebuild the native app after installation:

```sh
npx expo prebuild
npx expo run:android
```

## Basic Usage

```ts
import ExpoXrayVpn, { type ExpoXrayVpnConfig } from 'expo-xray-vpn';

export async function connectWithXrayConfig(xrayConfigJson: string) {
  const permission = await ExpoXrayVpn.requestPermission();

  if (!permission.granted) {
    throw new Error('VPN permission was not granted.');
  }

  const config: ExpoXrayVpnConfig = {
    dnsServer: '1.1.1.1',
    mtu: 1500,
    profileId: 'default',
    profileName: 'Default VPN',
    xrayConfigJson,
  };

  return ExpoXrayVpn.connect(config);
}

export function disconnectVpn() {
  return ExpoXrayVpn.disconnect();
}
```

## State Events

```ts
import { useEffect, useState } from 'react';
import ExpoXrayVpn, { type ExpoXrayVpnState } from 'expo-xray-vpn';

export function useExpoXrayVpnState() {
  const [state, setState] = useState<ExpoXrayVpnState>({
    state: 'disconnected',
  });

  useEffect(() => {
    ExpoXrayVpn.getState().then(setState);

    const subscription = ExpoXrayVpn.addListener('onStateChange', setState);

    return () => {
      subscription.remove();
    };
  }, []);

  return state;
}
```

Connection states are `disconnected`, `preparing`, `connecting`, `connected`, `disconnecting`, and `error`.

## Android Configuration

The Android package namespace is `com.expo.xray.vpn`.

The native Expo module is registered as:

```json
{
  "android": {
    "modules": ["com.expo.xray.vpn.ExpoXrayVpnModule"]
  }
}
```

The foreground notification uses the channel ID `expo_xray_vpn` and displays `Xray VPN` as the notification/session name.

## libXray Resolution

The Android Gradle module resolves libXray in this order:

1. Maven artifact `com.expo.xray.vpn:libxray:VERSION@aar` when running in CI or with `-PexpoXrayMaven`.
2. Local AAR from `android/libs/libXray.aar` for development.
3. Maven fallback if the local AAR is missing.

Set `LIBXRAY_VERSION` to override the default libXray artifact version.

## Documentation

See `USAGE.md` for a fuller API guide, examples, and troubleshooting notes.

## License

MIT
