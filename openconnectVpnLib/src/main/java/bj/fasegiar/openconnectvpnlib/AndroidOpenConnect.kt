package bj.fasegiar.openconnectvpnlib

import android.net.VpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.infradead.libopenconnect.LibOpenConnect

internal class AndroidOpenConnect(
    private val service: VpnService,
    private val inheritedScope: CoroutineScope,
    private val requestAuthenticationInfo: CoroutineScope.(isAuthFailed: Boolean) -> CompletableDeferred<UserCredential?>
) : LibOpenConnect(), CoroutineScope by inheritedScope {
    private val mutableStateFlow = MutableStateFlow(
        VPNConnectionStateMsg(
            state = VPNConnectionState.UNKNOWN
        )
    )

    val internalState: StateFlow<VPNConnectionStateMsg> get() = mutableStateFlow

    override fun onProcessAuthForm(authForm: AuthForm): Int =
        runBlocking(context = coroutineContext) {
            mutableStateFlow.value = VPNConnectionStateMsg(
                state = VPNConnectionState.AUTHENTICATING
            )
            if (authForm.error != null) {
                mutableStateFlow.value = VPNConnectionStateMsg(
                    state = VPNConnectionState.AUTHENTICATING,
                    messageLevel = VPNMessageLevel.INFO,
                    message = "AUTH: error ${authForm.error}"
                )
            }
            if (authForm.message != null) {
                mutableStateFlow.value = VPNConnectionStateMsg(
                    state = VPNConnectionState.AUTHENTICATING,
                    messageLevel = VPNMessageLevel.INFO,
                    message = "AUTH: message ${authForm.message}"
                )
            }
            requestAuthenticationInfo(authForm.error != null).await()?.run {
                if (isActive) {
                    for (opt in authForm.opts) {
                        if ((opt.flags and OC_FORM_OPT_IGNORE.toLong()) != 0L)
                            continue

                        when (opt.type) {
                            OC_FORM_OPT_TEXT -> opt.value = username
                            OC_FORM_OPT_PASSWORD -> opt.value = password
                        }
                    }
                    OC_FORM_RESULT_OK
                } else {
                    OC_FORM_RESULT_CANCELLED
                }
            } ?: OC_FORM_RESULT_CANCELLED
        }

    override fun onProgress(level: Int, message: String?) {
        mutableStateFlow.value = VPNConnectionStateMsg(
            messageLevel = when (level) {
                PRG_DEBUG -> VPNMessageLevel.DEBUG
                PRG_INFO -> VPNMessageLevel.INFO
                PRG_ERR -> VPNMessageLevel.ERR
                else -> VPNMessageLevel.TRACE
            },
            message = message
        )
    }

    override fun onProtectSocket(socket: Int) {
        launch {
            ensureActive()
            if (!service.protect(socket)) {
                mutableStateFlow.value = VPNConnectionStateMsg(
                    state = VPNConnectionState.DISCONNECTED,
                    messageLevel = VPNMessageLevel.ERR,
                    message = "Cannot protect the tunnel"
                )
            }
        }
    }
}