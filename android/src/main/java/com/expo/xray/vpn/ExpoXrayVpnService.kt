package com.expo.xray.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

class ExpoXrayVpnService : VpnService() {
  private val commandLock = Any()
  private val lifecycleExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "ExpoXrayVpnLifecycle")
  }
  private val tunnelLock = Any()
  private val tunnelRuntime by lazy { ExpoXrayVpnTunnelRuntime(this) }

  @Volatile
  private var latestCommandId = 0

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val commandIntent = intent ?: return START_NOT_STICKY

    when {
      ExpoXrayVpnIntentCodec.isConnect(commandIntent) -> {
        val commandId = nextCommandId()
        activeService = this
        // startForeground intentionally deferred to startTunnel() to
        // avoid a race: if stopSelf() was called by a preceding disconnect
        // task on the executor, calling startForeground here on the main
        // thread may throw IllegalStateException because the service is
        // still in the "stopping" window.  By the time startTunnel
        // actually runs on the executor, the stop has been fully
        // processed (or cancelled by this intent) so startForeground is
        // safe.  We must still call it within 5 s of
        // startForegroundService, which the executor easily meets.
        lifecycleExecutor.execute {
          startTunnel(commandIntent, commandId)
        }
      }
      ExpoXrayVpnIntentCodec.isDisconnect(commandIntent) -> {
        val commandId = nextCommandId()
        activeService = this
        startForeground(NOTIFICATION_ID, createNotification("VPN connection is stopping"))
        lifecycleExecutor.execute {
          stopTunnel(commandId)
        }
      }
      else -> return START_NOT_STICKY
    }

    return START_STICKY
  }

  override fun onDestroy() {
    nextCommandId()
    val releaseErrors = releaseTunnel(stopForeground = true)
    if (releaseErrors.isNotEmpty()) {
      Log.w(TAG, "Tunnel release failed on destroy: ${releaseErrors.joinToString("; ")}")
      ExpoXrayVpnStateStore.update(
        state = "error",
        error = releaseErrors.joinToString("; "),
        errorCode = ERR_VPN_RELEASE_FAILED
      )
    } else if (ExpoXrayVpnStateStore.currentState() != "error") {
      ExpoXrayVpnStateStore.update("disconnected")
    }
    activeService = null
    lifecycleExecutor.shutdownNow()
    super.onDestroy()
  }

  override fun onRevoke() {
    val commandId = nextCommandId()
    ExpoXrayVpnStateStore.update("disconnecting")
    lifecycleExecutor.execute {
      stopTunnel(commandId)
    }
    super.onRevoke()
  }

  private fun startTunnel(intent: Intent, commandId: Int) {
    synchronized(tunnelLock) {
      if (isStaleCommand(commandId)) {
        return
      }

      var profileId = ExpoXrayVpnIntentCodec.readProfileId(intent)
      var profileName = ExpoXrayVpnIntentCodec.readProfileName(intent)
      val previousReleaseErrors = releaseTunnelLocked(stopForeground = false)
      logReleaseErrors("Tunnel release before reconnect", previousReleaseErrors)

      if (isStaleCommand(commandId)) {
        return
      }

      // Allow the Go runtime to fully shut down the old Xray instance
      // before starting a new one.  coreServer.Close() in xray.go is
      // asynchronous — it signals goroutines to exit but does not wait
      // for them.  Without this delay the new RunXrayFromJSON can race
      // with lingering goroutines and trigger SIGABRT.
      if (previousReleaseErrors.isEmpty()) {
        Thread.sleep(300)
      }

      // Bring service to foreground.  Deferred from onStartCommand to
      // avoid the race where the service is still processing a preceding
      // disconnect's stopSelf().  By now the executor has finished any
      // prior stopTunnel, so the service is in a stable state.
      startForeground(NOTIFICATION_ID, createNotification("VPN connection is starting"))

      val errorCode = ERR_VPN_RUNTIME_FAILED
      try {
        val config = ExpoXrayVpnIntentCodec.readConnectConfig(intent)
        profileId = config.profileId
        profileName = config.profileName
        tunnelRuntime.start(config)

        if (isStaleCommand(commandId)) {
          releaseTunnelLocked(stopForeground = false)
          return
        }

        markConnected(config)
      } catch (error: ExpoXrayVpnTunnelException) {
        val releaseErrors = releaseTunnelLocked(stopForeground = false)
        updateRuntimeError(error.errorCode, error, releaseErrors, profileId, profileName)
        activeService = null
        stopForegroundCompat()
        stopSelfIfLatest(commandId)
      } catch (error: Throwable) {
        val releaseErrors = releaseTunnelLocked(stopForeground = false)
        updateRuntimeError(errorCode, error, releaseErrors, profileId, profileName)
        activeService = null
        stopForegroundCompat()
        stopSelfIfLatest(commandId)
      }
    }
  }

  private fun stopTunnel(commandId: Int) {
    synchronized(tunnelLock) {
      if (isStaleCommand(commandId)) {
        return
      }

      val releaseErrors = releaseTunnelLocked(stopForeground = true)
      activeService = null

      if (releaseErrors.isEmpty()) {
        ExpoXrayVpnStateStore.update("disconnected")
      } else {
        val error = releaseErrors.joinToString("; ")
        Log.w(TAG, "Tunnel release failed: $error")
        ExpoXrayVpnStateStore.update(
          state = "error",
          error = error,
          errorCode = ERR_VPN_RELEASE_FAILED
        )
      }

      stopSelfIfLatest(commandId)
    }
  }

  private fun releaseTunnel(stopForeground: Boolean): List<String> {
    return synchronized(tunnelLock) {
      releaseTunnelLocked(stopForeground)
    }
  }

  private fun releaseTunnelLocked(stopForeground: Boolean): List<String> {
    val errors = tunnelRuntime.release()

    if (stopForeground) {
      stopForegroundCompat()
    }

    return errors
  }

  private fun createNotification(
    contentText: String = "VPN connection is active"
  ): Notification {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Xray VPN",
        NotificationManager.IMPORTANCE_LOW
      )
      manager.createNotificationChannel(channel)
    }

    val pendingIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
      PendingIntent.getActivity(
        this,
        0,
        launchIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
    }

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }

    return builder
      .setContentTitle("Xray VPN")
      .setContentText(contentText)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .build()
  }

  private fun stopForegroundCompat() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
  }

  private fun nextCommandId(): Int {
    return synchronized(commandLock) {
      latestCommandId += 1
      latestCommandId
    }
  }

  private fun isStaleCommand(commandId: Int): Boolean {
    return commandId != latestCommandId
  }

  private fun stopSelfIfLatest(commandId: Int) {
    if (!isStaleCommand(commandId)) {
      stopSelf()
    }
  }

  private fun markConnected(config: ExpoXrayVpnRuntimeConfig) {
    startForeground(NOTIFICATION_ID, createNotification())
    ExpoXrayVpnStateStore.update(
      state = "connected",
      profileId = config.profileId,
      profileName = config.profileName
    )
  }

  internal fun createVpnBuilder(): Builder {
    return Builder()
  }

  private fun updateRuntimeError(
    errorCode: String,
    error: Throwable,
    releaseErrors: List<String>,
    profileId: String?,
    profileName: String?
  ) {
    val message = buildRuntimeErrorMessage(error, releaseErrors)
    Log.e(TAG, "VPN runtime error [$errorCode]: $message", error)
    ExpoXrayVpnStateStore.update(
      state = "error",
      error = message,
      errorCode = errorCode,
      profileId = profileId,
      profileName = profileName
    )
  }

  private fun buildRuntimeErrorMessage(error: Throwable, releaseErrors: List<String>): String {
    val primary = errorMessage(error)
    if (releaseErrors.isEmpty()) {
      return primary
    }

    return "$primary; release errors: ${releaseErrors.joinToString("; ")}"
  }

  private fun logReleaseErrors(context: String, errors: List<String>) {
    if (errors.isNotEmpty()) {
      Log.w(TAG, "$context: ${errors.joinToString("; ")}")
    }
  }

  private fun errorMessage(error: Throwable): String {
    return error.localizedMessage ?: error.message ?: error::class.java.simpleName
  }

  companion object {
    private const val CHANNEL_ID = "expo_xray_vpn"
    private const val ERR_VPN_RELEASE_FAILED = "ERR_VPN_RELEASE_FAILED"
    private const val ERR_VPN_RUNTIME_FAILED = "ERR_VPN_RUNTIME_FAILED"
    private const val NOTIFICATION_ID = 1001
    private const val TAG = "ExpoXrayVpnService"

    @Volatile
    private var activeService: ExpoXrayVpnService? = null

    fun startConnect(context: Context, config: ExpoXrayVpnRuntimeConfig) {
      val intent = ExpoXrayVpnIntentCodec.createConnectIntent(context, config)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun startDisconnect(context: Context) {
      val intent = ExpoXrayVpnIntentCodec.createDisconnectIntent(context)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun protectActiveSocket(fd: Int): Boolean {
      return activeService?.protect(fd) ?: false
    }
  }
}
