package com.expo.xray.vpn

import android.content.Context
import android.content.Intent

object ExpoXrayVpnIntentCodec {
  private const val ACTION_CONNECT = "com.expo.xray.vpn.CONNECT"
  private const val ACTION_DISCONNECT = "com.expo.xray.vpn.DISCONNECT"
  private const val EXTRA_ALLOWED_APPLICATIONS = "allowedApplications"
  private const val EXTRA_DISALLOWED_APPLICATIONS = "disallowedApplications"
  private const val EXTRA_DNS_SERVER = "dnsServer"
  private const val EXTRA_MTU = "mtu"
  private const val EXTRA_PROFILE_ID = "profileId"
  private const val EXTRA_PROFILE_NAME = "profileName"
  private const val EXTRA_ROUTE_ADDRESSES = "routeAddresses"
  private const val EXTRA_ROUTE_PREFIXES = "routePrefixes"
  private const val EXTRA_TUN_ADDRESS = "tunAddress"
  private const val EXTRA_TUN_PREFIX = "tunPrefix"
  private const val EXTRA_XRAY_CONFIG_JSON = "xrayConfigJson"

  fun isConnect(intent: Intent): Boolean {
    return intent.action == ACTION_CONNECT
  }

  fun isDisconnect(intent: Intent): Boolean {
    return intent.action == ACTION_DISCONNECT
  }

  fun createConnectIntent(context: Context, config: ExpoXrayVpnRuntimeConfig): Intent {
    val routeAddresses = ArrayList(config.routes.map { route -> route.address })
    val routePrefixes = ArrayList(config.routes.map { route -> route.prefix })

    return Intent(context, ExpoXrayVpnService::class.java)
      .setAction(ACTION_CONNECT)
      .putStringArrayListExtra(EXTRA_ALLOWED_APPLICATIONS, ArrayList(config.allowedApplications))
      .putStringArrayListExtra(EXTRA_DISALLOWED_APPLICATIONS, ArrayList(config.disallowedApplications))
      .putExtra(EXTRA_DNS_SERVER, config.dnsServer)
      .putExtra(EXTRA_MTU, config.mtu)
      .putExtra(EXTRA_PROFILE_ID, config.profileId)
      .putExtra(EXTRA_PROFILE_NAME, config.profileName)
      .putStringArrayListExtra(EXTRA_ROUTE_ADDRESSES, routeAddresses)
      .putIntegerArrayListExtra(EXTRA_ROUTE_PREFIXES, routePrefixes)
      .putExtra(EXTRA_TUN_ADDRESS, config.tunAddress)
      .putExtra(EXTRA_TUN_PREFIX, config.tunPrefix)
      .putExtra(EXTRA_XRAY_CONFIG_JSON, config.xrayConfigJson)
  }

  fun createDisconnectIntent(context: Context): Intent {
    return Intent(context, ExpoXrayVpnService::class.java).setAction(ACTION_DISCONNECT)
  }

  fun readConnectConfig(intent: Intent): ExpoXrayVpnRuntimeConfig {
    val routeAddresses = intent.getStringArrayListExtra(EXTRA_ROUTE_ADDRESSES).orEmpty()
    val routePrefixes = intent.getIntegerArrayListExtra(EXTRA_ROUTE_PREFIXES).orEmpty()
    val routes = if (routeAddresses.isEmpty()) {
      ExpoXrayVpnRuntimeConfig.defaultRoutes()
    } else {
      routeAddresses.mapIndexed { index, address ->
        ExpoXrayVpnRouteConfig(
          address = address,
          prefix = routePrefixes.getOrElse(index) { 0 }
        )
      }
    }

    return ExpoXrayVpnRuntimeConfig(
      allowedApplications = intent.getStringArrayListExtra(EXTRA_ALLOWED_APPLICATIONS).orEmpty(),
      disallowedApplications = intent.getStringArrayListExtra(EXTRA_DISALLOWED_APPLICATIONS).orEmpty(),
      dnsServer = intent.getStringExtra(EXTRA_DNS_SERVER)
        ?: ExpoXrayVpnRuntimeConfig.DEFAULT_DNS_SERVER,
      mtu = intent.getIntExtra(EXTRA_MTU, ExpoXrayVpnRuntimeConfig.DEFAULT_MTU),
      profileId = readProfileId(intent),
      profileName = readProfileName(intent),
      routes = routes,
      tunAddress = intent.getStringExtra(EXTRA_TUN_ADDRESS)
        ?: ExpoXrayVpnRuntimeConfig.DEFAULT_TUN_ADDRESS,
      tunPrefix = intent.getIntExtra(EXTRA_TUN_PREFIX, ExpoXrayVpnRuntimeConfig.DEFAULT_TUN_PREFIX),
      xrayConfigJson = intent.getStringExtra(EXTRA_XRAY_CONFIG_JSON)
        ?: throw IllegalArgumentException("xrayConfigJson is required.")
    )
  }

  fun readProfileId(intent: Intent): String? {
    return intent.getStringExtra(EXTRA_PROFILE_ID)
  }

  fun readProfileName(intent: Intent): String? {
    return intent.getStringExtra(EXTRA_PROFILE_NAME)
  }
}
