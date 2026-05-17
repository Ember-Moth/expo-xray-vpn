package com.starlink.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import libXray.DialerController
import libXray.LibXray

class StarlinkVpnTunnelException(
  val errorCode: String,
  cause: Throwable
) : RuntimeException(cause.localizedMessage ?: cause.message, cause)

class StarlinkVpnTunnelRuntime(
  private val service: StarlinkVpnService
) {
  private var tunInterface: ParcelFileDescriptor? = null
  private var xrayStarted = false

  fun start(config: StarlinkVpnRuntimeConfig) {
    val runtimeDir = try {
      prepareRuntimeDir()
    } catch (error: Throwable) {
      throw StarlinkVpnTunnelException(ERR_VPN_RUNTIME_FAILED, error)
    }

    try {
      establishTun(config)
    } catch (error: Throwable) {
      throw StarlinkVpnTunnelException(ERR_TUN_ESTABLISH_FAILED, error)
    }

    try {
      startXray(config, runtimeDir)
    } catch (error: Throwable) {
      throw StarlinkVpnTunnelException(ERR_XRAY_START_FAILED, error)
    }
  }

  fun release(): List<String> {
    val errors = mutableListOf<String>()
    val shouldReleaseXray = xrayStarted || tunInterface != null

    if (shouldReleaseXray) {
      runCatching {
        LibXrayResponse.requireSuccess(LibXray.stopXray())
      }.onFailure { error ->
        errors += "stopXray: ${errorMessage(error)}"
      }

      runCatching {
        LibXray.resetDns()
      }.onFailure { error ->
        errors += "resetDns: ${errorMessage(error)}"
      }
    }

    tunInterface?.let { descriptor ->
      runCatching {
        descriptor.close()
      }.onFailure { error ->
        errors += "closeTun: ${errorMessage(error)}"
      }
    }
    tunInterface = null
    xrayStarted = false

    return errors
  }

  private fun prepareRuntimeDir(): File {
    val runtimeDir = File(service.filesDir, "xray").also { it.mkdirs() }
    val missingGeoAssets = extractGeoAssets(runtimeDir)
    if (missingGeoAssets.isNotEmpty()) {
      Log.w(TAG, "Missing Xray geo assets: ${missingGeoAssets.joinToString(", ")}")
    }

    return runtimeDir
  }

  private fun establishTun(config: StarlinkVpnRuntimeConfig) {
    val builder = service.createVpnBuilder()
      .setSession(config.profileName ?: DEFAULT_SESSION_NAME)
      .setMtu(config.mtu)
      .addAddress(config.tunAddress, config.tunPrefix)
      .addDnsServer(config.dnsServer)

    for (route in config.routes) {
      builder.addRoute(route.address, route.prefix)
    }
    applyApplicationRules(builder, config)

    tunInterface = builder.establish()
    if (tunInterface == null) {
      throw IllegalStateException("Unable to establish VPN interface.")
    }
  }

  private fun startXray(config: StarlinkVpnRuntimeConfig, runtimeDir: File) {
    // mphCachePath is optional. XTLS's router uses MphDomainMatcher which
    // requires a pre-built cache file. Pass empty string so it falls back
    // to the regular domain matcher.
    val mphCachePath = ""
    val controller = StarlinkDialerController(service)
    LibXray.registerDialerController(controller)
    LibXray.registerListenerController(controller)
    LibXray.initDns(controller, config.dnsServer)
    LibXray.setTunFd(requireNotNull(tunInterface).fd)

    val runRequest = LibXray.newXrayRunFromJSONRequest(
      runtimeDir.absolutePath,
      mphCachePath,
      config.xrayConfigJson
    )
    LibXrayResponse.requireSuccess(LibXray.runXrayFromJSON(runRequest))
    xrayStarted = true
  }

  private fun applyApplicationRules(
    builder: android.net.VpnService.Builder,
    config: StarlinkVpnRuntimeConfig
  ) {
    for (packageName in config.allowedApplications) {
      builder.addAllowedApplication(packageName)
    }
    for (packageName in config.disallowedApplications) {
      builder.addDisallowedApplication(packageName)
    }
  }

  private fun extractGeoAssets(runtimeDir: File): List<String> {
    val geoFiles = listOf("geoip.dat", "geosite.dat")
    val missingFiles = mutableListOf<String>()
    val assetDir = "xray"

    for (fileName in geoFiles) {
      val targetFile = File(runtimeDir, fileName)
      if (targetFile.exists()) {
        continue
      }

      try {
        service.assets.open("$assetDir/$fileName").use { input ->
          FileOutputStream(targetFile).use { output ->
            input.copyTo(output)
          }
        }
      } catch (_: IOException) {
        missingFiles += fileName
      }
    }

    return missingFiles
  }

  private fun errorMessage(error: Throwable): String {
    return error.localizedMessage ?: error.message ?: error::class.java.simpleName
  }

  private class StarlinkDialerController(
    private val service: StarlinkVpnService
  ) : DialerController {
    override fun protectFd(fd: Long): Boolean {
      return service.protect(fd.toInt())
    }
  }

  companion object {
    const val ERR_TUN_ESTABLISH_FAILED = "ERR_TUN_ESTABLISH_FAILED"
    const val ERR_VPN_RUNTIME_FAILED = "ERR_VPN_RUNTIME_FAILED"
    const val ERR_XRAY_START_FAILED = "ERR_XRAY_START_FAILED"

    private const val DEFAULT_SESSION_NAME = "StarLink VPN"
    private const val TAG = "StarlinkVpnTunnelRuntime"
  }
}
