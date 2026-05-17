# Expo Xray VPN Usage

This guide explains how to use `expo-xray-vpn` as a generic Android VPN native module in an Expo or React Native application.

The module is Android-only. It cannot run in Expo Go because it depends on Android `VpnService`, a foreground service, and a native `libXray.aar` runtime.

## What The Module Does

`expo-xray-vpn` provides the bridge between JavaScript and Android native VPN functionality:

- Requests Android VPN permission.
- Starts and stops the foreground VPN service.
- Creates the Android TUN interface.
- Passes the TUN file descriptor to libXray.
- Runs Xray from a JSON configuration string.
- Protects sockets created by libXray so they bypass the VPN tunnel.
- Emits connection state changes to JavaScript.
- Exposes libXray helpers for share-link conversion and free port lookup.

The module does not generate business-specific Xray configuration, manage subscriptions, implement account logic, or provide iOS VPN support.

## Native Build Requirements

Use a development build or a fully native Android build:

```sh
npx expo run:android
```

If this module is used from another app, rebuild the Android project after changing native code or installing the package.

## libXray Setup

For local development, place a compatible AAR at:

```text
android/libs/libXray.aar
```

For CI or Maven-based builds, publish or provide the artifact as:

```text
com.expo.xray.vpn:libxray:<version>@aar
```

The Android Gradle module uses Maven when `CI=true` or when Gradle is invoked with `-PexpoXrayMaven`. Set `LIBXRAY_VERSION` to choose the artifact version.

## Importing The Module

Package import:

```ts
import ExpoXrayVpn, {
  type ExpoXrayVpnConfig,
  type ExpoXrayVpnState,
} from 'expo-xray-vpn';
```

Local workspace import examples may use a relative path instead:

```ts
import ExpoXrayVpn from '../../modules/expo-xray-vpn';
```

Adjust the relative path for your project layout.

## Connect And Disconnect

The recommended flow is:

1. Call `requestPermission()`.
2. Prepare an Xray JSON configuration string.
3. Call `connect(config)`.
4. Listen for `onStateChange` events.
5. Call `disconnect()` when the user stops the VPN.

```ts
import ExpoXrayVpn from 'expo-xray-vpn';

export async function connectVpn(xrayConfigJson: string) {
  const permission = await ExpoXrayVpn.requestPermission();

  if (!permission.granted) {
    throw new Error('VPN permission was not granted.');
  }

  return ExpoXrayVpn.connect({
    dnsServer: '1.1.1.1',
    mtu: 1500,
    profileId: 'node-01',
    profileName: 'Node 01',
    xrayConfigJson,
  });
}

export function disconnectVpn() {
  return ExpoXrayVpn.disconnect();
}
```

## State Listener

```ts
import { useEffect, useState } from 'react';
import ExpoXrayVpn, { type ExpoXrayVpnState } from 'expo-xray-vpn';

export function useVpnState() {
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

State values:

- `disconnected`
- `preparing`
- `connecting`
- `connected`
- `disconnecting`
- `error`

The `error` state includes `error` and `errorCode` when available. State snapshots include `lastChangedAt`; connected snapshots include `connectedAt`.

## Share-Link Conversion

Convert share-link text to Xray JSON:

```ts
const xrayConfigJson =
  await ExpoXrayVpn.convertShareLinksToXrayJson(subscriptionText);

await ExpoXrayVpn.connect({
  profileName: 'Imported profile',
  xrayConfigJson,
});
```

Convert Xray JSON back to share-link text:

```ts
const links = await ExpoXrayVpn.convertXrayJsonToShareLinks(xrayConfigJson);
```

Supported input formats depend on the bundled libXray implementation.

## Free Ports

`getFreePorts(count)` returns available local ports from libXray:

```ts
const [socksPort, httpPort] = await ExpoXrayVpn.getFreePorts(2);
```

`count` must be greater than `0`.

## API Reference

### `requestPermission()`

Requests Android VPN permission.

```ts
type ExpoXrayVpnPermissionResult = {
  granted: boolean;
};
```

### `connect(config)`

Starts the Android VPN service and passes the Xray JSON configuration to libXray.

```ts
type ExpoXrayVpnConfig = {
  allowedApplications?: string[];
  disallowedApplications?: string[];
  dnsServer?: string;
  mtu?: number;
  profileId?: string;
  profileName?: string;
  routes?: Array<{ address: string; prefix: number }>;
  tunAddress?: string;
  tunPrefix?: number;
  xrayConfigJson: string;
};
```

Notes:

- `xrayConfigJson` is required.
- `dnsServer` defaults to `1.1.1.1`.
- `mtu` defaults to `1500`.
- `tunAddress` defaults to `10.8.0.2`.
- `tunPrefix` defaults to `32`.
- `routes` defaults to `0.0.0.0/0`.
- `allowedApplications` and `disallowedApplications` are mutually exclusive.
- `profileId` and `profileName` are mirrored back in state snapshots.

### `disconnect()`

Stops libXray, closes the TUN interface, and stops the foreground VPN service.

### `getState()`

Returns the current in-memory connection state snapshot.

### `protectSocket(fd)`

Calls Android `VpnService.protect(fd)`. Application code usually does not need to call this directly because the native libXray dialer controller handles socket protection.

### `getFreePorts(count)`

Returns an array of available local ports.

### `convertShareLinksToXrayJson(text)`

Converts share-link text into an Xray JSON string using libXray.

### `convertXrayJsonToShareLinks(xrayConfigJson)`

Converts an Xray JSON string into share-link text using libXray.

## Error Codes

Common error codes include:

- `ERR_VPN_PERMISSION_REQUIRED`: VPN permission is required before connecting.
- `ERR_VPN_PERMISSION_DENIED`: The user denied VPN permission.
- `ERR_INVALID_XRAY_CONFIG`: `xrayConfigJson` is empty or invalid for the called operation.
- `ERR_INVALID_SHARE_LINKS`: Share-link text is empty.
- `ERR_INVALID_PORT_COUNT`: Requested port count is less than or equal to `0`.
- `ERR_LIBXRAY_CALL_FAILED`: A libXray helper call failed.
- `ERR_TUN_ESTABLISH_FAILED`: Android failed to create the TUN interface.
- `ERR_XRAY_START_FAILED`: libXray failed to start Xray.
- `ERR_VPN_RUNTIME_FAILED`: A runtime error occurred while starting the VPN.

## Verification

Run TypeScript checks after changing the JavaScript API:

```sh
npm run build
```

Build an Android development app to verify native module registration and Kotlin compilation:

```sh
npx expo run:android
```

## Limitations

- Android only.
- Not supported in Expo Go.
- `onTrafficUpdate` is reserved but not currently emitted.
- Actual traffic forwarding depends on a valid Xray configuration and compatible libXray runtime behavior.
