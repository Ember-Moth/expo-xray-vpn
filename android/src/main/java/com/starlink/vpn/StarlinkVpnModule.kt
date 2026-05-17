package com.starlink.vpn

import android.app.Activity
import android.content.Context
import android.net.VpnService
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import libXray.LibXray
import org.json.JSONArray

class StarlinkVpnModule : Module() {
  private var pendingPermissionPromise: Promise? = null

  override fun definition() = ModuleDefinition {
    Name("StarlinkVpn")
    Events("onStateChange", "onTrafficUpdate")

    OnCreate {
      StarlinkVpnStateStore.stateListener = { payload ->
        sendEvent("onStateChange", payload)
      }
    }

    OnDestroy {
      StarlinkVpnStateStore.stateListener = null
      pendingPermissionPromise?.reject(
        "ERR_VPN_PERMISSION_CANCELLED",
        "The VPN permission request was interrupted.",
        null
      )
      pendingPermissionPromise = null
    }

    OnActivityResult { _, payload ->
      if (payload.requestCode != VPN_PERMISSION_REQUEST_CODE) {
        return@OnActivityResult
      }

      val promise = pendingPermissionPromise ?: return@OnActivityResult
      pendingPermissionPromise = null
      val context = requireContext()
      val granted = payload.resultCode == Activity.RESULT_OK || VpnService.prepare(context) == null
      if (granted) {
        StarlinkVpnStateStore.update("disconnected")
      } else {
        StarlinkVpnStateStore.update(
          state = "error",
          error = "VPN permission was denied.",
          errorCode = ERR_VPN_PERMISSION_DENIED
        )
      }
      promise.resolve(mapOf("granted" to granted))
    }

    AsyncFunction("requestPermission") { promise: Promise ->
      val context = requireContext()
      val permissionIntent = VpnService.prepare(context)

      if (permissionIntent == null) {
        promise.resolve(mapOf("granted" to true))
        return@AsyncFunction
      }

      val activity = appContext.currentActivity
        ?: throw CodedException("ERR_ACTIVITY_UNAVAILABLE", "Current Activity is unavailable.", null)

      if (pendingPermissionPromise != null) {
        throw CodedException(
          "ERR_VPN_PERMISSION_IN_PROGRESS",
          "A VPN permission request is already in progress.",
          null
        )
      }

      pendingPermissionPromise = promise
      StarlinkVpnStateStore.update("preparing")
      activity.startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST_CODE)
    }

    AsyncFunction("connect") { config: StarlinkVpnConnectOptions ->
      val context = requireContext()

      if (VpnService.prepare(context) != null) {
        StarlinkVpnStateStore.update(
          state = "error",
          error = "VPN permission is required before connecting.",
          errorCode = ERR_VPN_PERMISSION_REQUIRED
        )
        throw CodedException(
          ERR_VPN_PERMISSION_REQUIRED,
          "VPN permission is required before connecting.",
          null
        )
      }

      if (config.xrayConfigJson.isBlank()) {
        throw CodedException("ERR_INVALID_XRAY_CONFIG", "xrayConfigJson is required.", null)
      }
      validateConnectOptions(config)
      val runtimeConfig = StarlinkVpnRuntimeConfig.fromOptions(config)

      StarlinkVpnStateStore.update(
        state = "connecting",
        profileId = runtimeConfig.profileId,
        profileName = runtimeConfig.profileName
      )

      startVpnService(
        errorCode = ERR_SERVICE_START_FAILED,
        profileId = runtimeConfig.profileId,
        profileName = runtimeConfig.profileName
      ) {
        StarlinkVpnService.startConnect(context, runtimeConfig)
      }

      StarlinkVpnStateStore.snapshot()
    }

    AsyncFunction("disconnect") {
      val context = requireContext()
      StarlinkVpnStateStore.update("disconnecting")
      startVpnService(
        errorCode = ERR_SERVICE_START_FAILED,
        profileId = null,
        profileName = null
      ) {
        StarlinkVpnService.startDisconnect(context)
      }
      StarlinkVpnStateStore.snapshot()
    }

    AsyncFunction("getState") {
      StarlinkVpnStateStore.snapshot()
    }

    AsyncFunction("protectSocket") { fd: Int ->
      StarlinkVpnService.protectActiveSocket(fd)
    }

    AsyncFunction("getFreePorts") { count: Int ->
      if (count <= 0) {
        throw CodedException("ERR_INVALID_PORT_COUNT", "count must be greater than 0.", null)
      }

      runLibXrayCall {
        val response = LibXrayResponse.requireSuccess(LibXray.getFreePorts(count.toLong()))
        val ports = response.optJSONObject("data")?.optJSONArray("ports") ?: JSONArray()
        (0 until ports.length()).map { index -> ports.getInt(index) }
      }
    }

    AsyncFunction("convertShareLinksToXrayJson") { text: String ->
      if (text.isBlank()) {
        throw CodedException("ERR_INVALID_SHARE_LINKS", "Share link text is required.", null)
      }

      runLibXrayCall {
        val response = LibXrayResponse.requireSuccess(
          LibXray.convertShareLinksToXrayJson(LibXrayResponse.encodeText(text))
        )
        LibXrayResponse.requireDataJsonString(response)
      }
    }

    AsyncFunction("convertXrayJsonToShareLinks") { xrayConfigJson: String ->
      if (xrayConfigJson.isBlank()) {
        throw CodedException("ERR_INVALID_XRAY_CONFIG", "xrayConfigJson is required.", null)
      }

      runLibXrayCall {
        val response = LibXrayResponse.requireSuccess(
          LibXray.convertXrayJsonToShareLinks(LibXrayResponse.encodeText(xrayConfigJson))
        )
        response.optString("data")
      }
    }
  }

  private fun requireContext(): Context {
    return appContext.reactContext
      ?: throw CodedException("ERR_CONTEXT_UNAVAILABLE", "React context is unavailable.", null)
  }

  private fun <T> runLibXrayCall(block: () -> T): T {
    return try {
      block()
    } catch (error: CodedException) {
      throw error
    } catch (error: Throwable) {
      throw CodedException(
        "ERR_LIBXRAY_CALL_FAILED",
        error.localizedMessage ?: "libXray call failed.",
        error
      )
    }
  }

  private fun validateConnectOptions(config: StarlinkVpnConnectOptions) {
    validateOptionalString(config.dnsServer, "dnsServer")
    validateOptionalString(config.tunAddress, "tunAddress")

    if (config.mtu <= 0) {
      throw CodedException(ERR_INVALID_VPN_CONFIG, "mtu must be greater than 0.", null)
    }

    if (config.tunPrefix !in 0..32) {
      throw CodedException(ERR_INVALID_VPN_CONFIG, "tunPrefix must be between 0 and 32.", null)
    }

    if (config.allowedApplications.isNotEmpty() && config.disallowedApplications.isNotEmpty()) {
      throw CodedException(
        ERR_INVALID_VPN_CONFIG,
        "allowedApplications and disallowedApplications cannot be used together.",
        null
      )
    }

    validatePackageList("allowedApplications", config.allowedApplications)
    validatePackageList("disallowedApplications", config.disallowedApplications)

    config.routes.forEachIndexed { index, route ->
      if (route.address.isBlank()) {
        throw CodedException(
          ERR_INVALID_VPN_CONFIG,
          "routes[$index].address cannot be blank.",
          null
        )
      }
      if (route.prefix !in 0..32) {
        throw CodedException(
          ERR_INVALID_VPN_CONFIG,
          "routes[$index].prefix must be between 0 and 32.",
          null
        )
      }
    }
  }

  private fun validateOptionalString(value: String?, fieldName: String) {
    if (value != null && value.isBlank()) {
      throw CodedException(ERR_INVALID_VPN_CONFIG, "$fieldName cannot be blank.", null)
    }
  }

  private fun validatePackageList(fieldName: String, packageNames: List<String>) {
    packageNames.forEachIndexed { index, packageName ->
      if (packageName.isBlank()) {
        throw CodedException(
          ERR_INVALID_VPN_CONFIG,
          "$fieldName[$index] cannot be blank.",
          null
        )
      }
    }
  }

  private fun startVpnService(
    errorCode: String,
    profileId: String?,
    profileName: String?,
    block: () -> Unit
  ) {
    try {
      block()
    } catch (error: Throwable) {
      val message = error.localizedMessage ?: error.message ?: "Unable to start VPN service."
      StarlinkVpnStateStore.update(
        state = "error",
        error = message,
        errorCode = errorCode,
        profileId = profileId,
        profileName = profileName
      )
      throw CodedException(errorCode, message, error)
    }
  }

  companion object {
    private const val ERR_INVALID_VPN_CONFIG = "ERR_INVALID_VPN_CONFIG"
    private const val ERR_SERVICE_START_FAILED = "ERR_SERVICE_START_FAILED"
    private const val ERR_VPN_PERMISSION_DENIED = "ERR_VPN_PERMISSION_DENIED"
    private const val ERR_VPN_PERMISSION_REQUIRED = "ERR_VPN_PERMISSION_REQUIRED"
    private const val VPN_PERMISSION_REQUEST_CODE = 42081
  }
}

class StarlinkVpnRoute : Record {
  @Field
  var address: String = ""

  @Field
  var prefix: Int = 0
}

class StarlinkVpnConnectOptions : Record {
  @Field
  var allowedApplications: List<String> = emptyList()

  @Field
  var disallowedApplications: List<String> = emptyList()

  @Field
  var dnsServer: String? = null

  @Field
  var mtu: Int = 1500

  @Field
  var profileId: String? = null

  @Field
  var profileName: String? = null

  @Field
  var routes: List<StarlinkVpnRoute> = emptyList()

  @Field
  var tunAddress: String? = null

  @Field
  var tunPrefix: Int = 32

  @Field
  var xrayConfigJson: String = ""
}
