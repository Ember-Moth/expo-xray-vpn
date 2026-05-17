package com.starlink.vpn

data class StarlinkVpnRouteConfig(
  val address: String,
  val prefix: Int
)

data class StarlinkVpnRuntimeConfig(
  val allowedApplications: List<String>,
  val disallowedApplications: List<String>,
  val dnsServer: String,
  val mtu: Int,
  val profileId: String?,
  val profileName: String?,
  val routes: List<StarlinkVpnRouteConfig>,
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

    fun defaultRoutes(): List<StarlinkVpnRouteConfig> {
      return listOf(StarlinkVpnRouteConfig(DEFAULT_ROUTE_ADDRESS, DEFAULT_ROUTE_PREFIX))
    }

    fun fromOptions(options: StarlinkVpnConnectOptions): StarlinkVpnRuntimeConfig {
      val routes = if (options.routes.isEmpty()) {
        defaultRoutes()
      } else {
        options.routes.map { route ->
          StarlinkVpnRouteConfig(route.address, route.prefix)
        }
      }

      return StarlinkVpnRuntimeConfig(
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
