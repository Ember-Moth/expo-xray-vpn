package com.expo.xray.vpn

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

class ExpoXrayVpnModule : Module() {
  private var pendingPermissionPromise: Promise? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoXrayVpn")
    Events("onStateChange", "onTrafficUpdate")

    OnCreate {
      ExpoXrayVpnStateStore.stateListener = { payload ->
        sendEvent("onStateChange", payload)
      }
    }

    OnDestroy {
      ExpoXrayVpnStateStore.stateListener = null
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
        ExpoXrayVpnStateStore.update("disconnected")
      } else {
        ExpoXrayVpnStateStore.update(
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
      ExpoXrayVpnStateStore.update("preparing")
      activity.startActivityForResult(permissionIntent, VPN_PERMISSION_REQUEST_CODE)
    }

    AsyncFunction("connect") { config: ExpoXrayVpnConnectOptions ->
      val context = requireContext()

      if (VpnService.prepare(context) != null) {
        ExpoXrayVpnStateStore.update(
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
      val runtimeConfig = ExpoXrayVpnRuntimeConfig.fromOptions(config)

      ExpoXrayVpnStateStore.update(
        state = "connecting",
        profileId = runtimeConfig.profileId,
        profileName = runtimeConfig.profileName
      )

      startVpnService(
        errorCode = ERR_SERVICE_START_FAILED,
        profileId = runtimeConfig.profileId,
        profileName = runtimeConfig.profileName
      ) {
        ExpoXrayVpnService.startConnect(context, runtimeConfig)
      }

      ExpoXrayVpnStateStore.snapshot()
    }

    AsyncFunction("disconnect") {
      val context = requireContext()
      ExpoXrayVpnStateStore.update("disconnecting")
      startVpnService(
        errorCode = ERR_SERVICE_START_FAILED,
        profileId = null,
        profileName = null
      ) {
        ExpoXrayVpnService.startDisconnect(context)
      }
      ExpoXrayVpnStateStore.snapshot()
    }

    AsyncFunction("getState") {
      ExpoXrayVpnStateStore.snapshot()
    }

    AsyncFunction("protectSocket") { fd: Int ->
      ExpoXrayVpnService.protectActiveSocket(fd)
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

  private fun validateConnectOptions(config: ExpoXrayVpnConnectOptions) {
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
      ExpoXrayVpnStateStore.update(
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

class ExpoXrayVpnRoute : Record {
  @Field
  var address: String = ""

  @Field
  var prefix: Int = 0
}

class ExpoXrayVpnConnectOptions : Record {
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
  var routes: List<ExpoXrayVpnRoute> = emptyList()

  @Field
  var tunAddress: String? = null

  @Field
  var tunPrefix: Int = 32

  @Field
  var xrayConfigJson: String = ""
}
