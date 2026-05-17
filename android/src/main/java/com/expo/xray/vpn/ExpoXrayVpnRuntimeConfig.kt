package com.expo.xray.vpn

data class ExpoXrayVpnRouteConfig(
  val address: String,
  val prefix: Int
)

data class ExpoXrayVpnRuntimeConfig(
  val allowedApplications: List<String>,
  val disallowedApplications: List<String>,
  val dnsServer: String,
  val mtu: Int,
  val profileId: String?,
  val profileName: String?,
  val routes: List<ExpoXrayVpnRouteConfig>,
  val tunAddress: String,
  val tunPrefix: Int,
  val xrayConfigJson: String
) {
  companion object {
    const val DEFAULT_DNS_SERVER = "1.1.1.1"
    const val DEFAULT_MTU = 1500
    const val DEFAULT_TUN_ADDRESS = "10.8.0.2"
    const val DEFAULT_TUN_PREFIX = 32

    private const val DEFAULT_ROUTE_ADDRESS = "0.0.0.0"
    private const val DEFAULT_ROUTE_PREFIX = 0

    fun defaultRoutes(): List<ExpoXrayVpnRouteConfig> {
      return listOf(ExpoXrayVpnRouteConfig(DEFAULT_ROUTE_ADDRESS, DEFAULT_ROUTE_PREFIX))
    }

    fun fromOptions(options: ExpoXrayVpnConnectOptions): ExpoXrayVpnRuntimeConfig {
      val routes = if (options.routes.isEmpty()) {
        defaultRoutes()
      } else {
        options.routes.map { route ->
          ExpoXrayVpnRouteConfig(route.address, route.prefix)
        }
      }

      return ExpoXrayVpnRuntimeConfig(
        allowedApplications = options.allowedApplications,
        disallowedApplications = options.disallowedApplications,
        dnsServer = options.dnsServer ?: DEFAULT_DNS_SERVER,
        mtu = options.mtu,
        profileId = options.profileId,
        profileName = options.profileName,
        routes = routes,
        tunAddress = options.tunAddress ?: DEFAULT_TUN_ADDRESS,
        tunPrefix = options.tunPrefix,
        xrayConfigJson = options.xrayConfigJson
      )
    }
  }
}
