package bj.fasegiar.openconnectvpnlib

enum class VPNConnectionState {
    AUTHENTICATING,
    AUTHENTICATED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    UNKNOWN
}

enum class VPNMessageLevel {
    TRACE,
    DEBUG,
    INFO,
    ERR,
    NONE
}

data class VPNConnectionStateMsg(
    val state: VPNConnectionState = VPNConnectionState.UNKNOWN,
    val messageLevel: VPNMessageLevel = VPNMessageLevel.NONE,
    val message: String? = null
)