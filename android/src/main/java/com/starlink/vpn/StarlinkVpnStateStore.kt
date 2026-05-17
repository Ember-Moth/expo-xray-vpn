package com.starlink.vpn

object StarlinkVpnStateStore {
  var stateListener: ((Map<String, Any?>) -> Unit)? = null

  private var connectedAt: Long? = null
  private var error: String? = null
  private var errorCode: String? = null
  private var lastChangedAt: Long = System.currentTimeMillis()
  private var profileId: String? = null
  private var profileName: String? = null
  private var state: String = "disconnected"

  @Synchronized
  fun update(
    state: String,
    error: String? = null,
    errorCode: String? = null,
    profileId: String? = this.profileId,
    profileName: String? = this.profileName
  ) {
    require(state in KNOWN_STATES) { "Unknown VPN state: $state" }

    val now = System.currentTimeMillis()
    this.state = state
    this.error = if (state == STATE_ERROR) error else null
    this.errorCode = if (state == STATE_ERROR) errorCode else null
    this.lastChangedAt = now
    this.profileId = if (state == STATE_DISCONNECTED) null else profileId
    this.profileName = if (state == STATE_DISCONNECTED) null else profileName
    this.connectedAt = if (state == "connected") {
      now
    } else if (state == STATE_DISCONNECTED || state == STATE_ERROR) {
      null
    } else {
      connectedAt
    }
    stateListener?.invoke(snapshot())
  }

  @Synchronized
  fun snapshot(): Map<String, Any?> {
    return mapOf(
      "connectedAt" to connectedAt,
      "error" to error,
      "errorCode" to errorCode,
      "lastChangedAt" to lastChangedAt,
      "profileId" to profileId,
      "profileName" to profileName,
      "state" to state
    )
  }

  @Synchronized
  fun currentState(): String = state

  private const val STATE_DISCONNECTED = "disconnected"
  private const val STATE_ERROR = "error"
  private val KNOWN_STATES = setOf(
    STATE_DISCONNECTED,
    "preparing",
    "connecting",
    "connected",
    "disconnecting",
    STATE_ERROR
  )
}
