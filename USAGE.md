# StarLink VPN 模块使用手册

本文档说明 `modules/starlink-vpn` 的用途、构建方式、TypeScript 调用方式和当前限制。

## 模块定位

`starlink-vpn` 是一个 Android-only Expo Local Module，用来把 React Native 页面层和 Android `VpnService`、`libXray.aar` 连接起来。

当前模块负责：

- 申请 Android VPN 权限
- 启动和停止前台 VPN Service
- 创建 TUN 接口并把 TUN fd 传给 libXray
- 为 libXray 提供 socket protect 回调
- 启动和停止 Xray JSON 配置
- 导出 libXray 的分享链接转换和空闲端口工具函数

当前模块不负责：

- VPN 内核配置生成策略
- 节点订阅、套餐、账号等业务 API
- 流量统计轮询
- iOS VPN 能力

## 运行环境

该模块依赖 Android 原生代码，不能在 Expo Go 中运行。开发时需要使用 dev build：

```powershell
pnpm android
```

或者打开 `android/` 原生工程后，在 Android Studio 中运行 `app`。

如果本机环境没有自动识别 JDK 和 Android SDK，可以在当前 PowerShell 会话中设置：

```powershell
$env:JAVA_HOME='C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:ANDROID_HOME='D:\Tools\AndroidSDK'
$env:ANDROID_SDK_ROOT='D:\Tools\AndroidSDK'
$env:PATH="C:\Program Files\Android\openjdk\jdk-21.0.8\bin;D:\Tools\AndroidSDK\platform-tools;$env:PATH"
```

## libXray 准备

`libXray` 作为 git 子模块放在：

```text
modules/starlink-vpn/libXray
```

克隆仓库后初始化子模块：

```powershell
git submodule update --init --recursive
```

进入 `modules/starlink-vpn/libXray` 后构建 Android AAR：

```powershell
python build/main.py android
```

构建产物需要放到：

```text
modules/starlink-vpn/android/libs/libXray.aar
```

Gradle 会自动加载 `modules/starlink-vpn/android/libs/*.aar`。`libXray.aar` 是本地构建产物，文件较大，不进入 Git 跟踪。

## TypeScript 导入

模块入口：

```ts
import StarlinkVpn, {
  type StarlinkVpnConfig,
  type StarlinkVpnState,
} from '../../modules/starlink-vpn';
```

实际页面中的相对路径按文件位置调整。例如从 `src/screens/HomeScreen.tsx` 或 `src/hooks/useVpnState.ts` 导入，通常是：

```ts
import StarlinkVpn from '../../modules/starlink-vpn';
```

## 基本连接流程

推荐流程：

1. 调用 `requestPermission()` 申请 Android VPN 权限。
2. 准备 `xrayConfigJson`。
3. 调用 `connect(config)` 启动 VPN。
4. 通过 `onStateChange` 监听连接状态。
5. 调用 `disconnect()` 断开 VPN。

示例：

```ts
import StarlinkVpn from '../../modules/starlink-vpn';

export async function connectVpn(xrayConfigJson: string) {
  const permission = await StarlinkVpn.requestPermission();

  if (!permission.granted) {
    throw new Error('VPN 权限未授权');
  }

  return StarlinkVpn.connect({
    dnsServer: '1.1.1.1',
    mtu: 1500,
    profileId: 'node-hk-01',
    profileName: 'Hong Kong 01',
    xrayConfigJson,
  });
}

export async function disconnectVpn() {
  return StarlinkVpn.disconnect();
}
```

## 状态监听

```ts
import { useEffect, useState } from 'react';
import StarlinkVpn, { type StarlinkVpnState } from '../../modules/starlink-vpn';

export function useVpnState() {
  const [state, setState] = useState<StarlinkVpnState>({
    state: 'disconnected',
  });

  useEffect(() => {
    StarlinkVpn.getState().then(setState);

    const subscription = StarlinkVpn.addListener('onStateChange', setState);

    return () => {
      subscription.remove();
    };
  }, []);

  return state;
}
```

状态值：

- `disconnected`
- `preparing`
- `connecting`
- `connected`
- `disconnecting`
- `error`

`error` 状态会带上 `error` 和 `errorCode` 字段。所有状态快照都会带上 `lastChangedAt`，连接成功后会带上 `connectedAt`。

## 分享链接转换

`convertShareLinksToXrayJson(text)` 用于把分享链接文本转换为 Xray JSON。

支持范围由 libXray 决定，包括：

- Xray JSON
- v2rayN plain text
- v2rayN base64 text
- Clash.Meta yaml

示例：

```ts
const xrayConfigJson =
  await StarlinkVpn.convertShareLinksToXrayJson(subscriptionText);

await StarlinkVpn.connect({
  profileName: 'Imported node',
  xrayConfigJson,
});
```

`convertXrayJsonToShareLinks(xrayConfigJson)` 用于把 Xray JSON 转回分享链接文本：

```ts
const links = await StarlinkVpn.convertXrayJsonToShareLinks(xrayConfigJson);
```

## 空闲端口

`getFreePorts(count)` 返回当前设备上可用的本地端口列表：

```ts
const [socksPort, httpPort] = await StarlinkVpn.getFreePorts(2);
```

`count` 必须大于 `0`。

## API 参考

### `requestPermission()`

申请 Android VPN 权限。

返回：

```ts
type StarlinkVpnPermissionResult = {
  granted: boolean;
};
```

### `connect(config)`

启动 Android VPN Service，并把 Xray JSON 配置交给 libXray。

参数：

```ts
type StarlinkVpnConfig = {
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

说明：

- `xrayConfigJson` 必填。
- `dnsServer` 默认由原生层使用 `1.1.1.1`。
- `mtu` 默认由原生层使用 `1500`。
- `tunAddress` 默认 `10.8.0.2`，`tunPrefix` 默认 `32`。
- `routes` 默认全局 IPv4 路由 `0.0.0.0/0`。
- `allowedApplications` 和 `disallowedApplications` 用于按包名配置 VPN 应用规则，二者不能同时传入。
- `profileId` 和 `profileName` 会回传到连接状态中，方便 UI 展示当前节点。

### `disconnect()`

停止 libXray，关闭 TUN 接口，并停止前台 VPN Service。

### `getState()`

读取当前模块内存中的连接状态快照。

### `protectSocket(fd)`

手动调用 Android `VpnService.protect(fd)`。通常业务层不需要直接调用，libXray 的 dialer/listener controller 会自动走 protect 回调。

### `getFreePorts(count)`

调用 libXray 的 `GetFreePorts`，返回空闲端口数组。

### `convertShareLinksToXrayJson(text)`

调用 libXray 的 `ConvertShareLinksToXrayJson`，返回 JSON 字符串。

### `convertXrayJsonToShareLinks(xrayConfigJson)`

调用 libXray 的 `ConvertXrayJsonToShareLinks`，返回分享链接文本。

## 错误处理

原生层会把 libXray 返回的 base64 `CallResponse` 解码。如果 `success` 为 `false`，会抛出 `ERR_LIBXRAY_CALL_FAILED`。

常见错误：

- `ERR_VPN_PERMISSION_REQUIRED`：连接前没有 VPN 权限。
- `ERR_INVALID_XRAY_CONFIG`：`xrayConfigJson` 为空。
- `ERR_INVALID_SHARE_LINKS`：分享链接文本为空。
- `ERR_INVALID_PORT_COUNT`：空闲端口数量小于等于 `0`。
- `ERR_LIBXRAY_CALL_FAILED`：libXray 调用失败。

## 验证命令

前端类型和格式检查：

```powershell
pnpm exec biome check .
pnpm exec tsc --noEmit
```

Android 模块编译检查：

```powershell
cd android
.\gradlew.bat :starlink-vpn:compileDebugKotlin --no-daemon --console=plain
```

## 当前限制

- 仅支持 Android。
- Expo Go 不支持该原生模块。
- `onTrafficUpdate` 事件已预留，但当前还没有流量统计轮询实现。
- VPN 是否能实际转发流量取决于传入的 `xrayConfigJson` 是否符合 libXray/Xray 的运行要求。
- `libXray.aar` 是本地二进制产物，更新 libXray 子模块后需要重新构建并替换 AAR。
