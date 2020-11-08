package bj.fasegiar.openconnectvpnlib

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.*
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

internal const val vpnGatewayKey =
    "bj.fasegiar.openconnectvpnlib.VpnConnectionHandler.vpnGatewayKey"
internal const val vpnBundleExtraConnectionKey =
    "bj.fasegiar.openconnectvpnlib.VpnConnectionHandler.vpnBundleExtraConnectionKey"
internal const val vpnConnectionHandlerMessengerIBinderKey =
    "bj.fasegiar.openconnectvpnlib.VpnConnectionHandler.vpnConnectionHandlerMessengerIBinderKey"
internal const val vpnStateKey =
    "bj.fasegiar.openconnectvpnlib.VpnConnectionHandler.vpnStateKey"
internal const val vpnInternalMessageLevelKey =
    "bj.fasegiar.openconnectvpnlib.VpnConnectionHandler.vpnInternalMessageLevelKey"
internal const val vpnInternalMessageKey =
    "bj.fasegiar.openconnectvpnlib.VpnConnectionHandler.vpnInternalMessageKey"

internal const val vpnAuthFailedKey =
    "bj.fasegiar.openconnectvpnlib.VpnConnectionHandler.vpnAuthFailedKey"

internal const val VPN_STATE = 0xA2
internal const val REQUEST_VPN_CREDENTIAL = 0xA3

enum class VPN_CREDENTIAL_REQUEST {
    NEW_REQUEST,
    REQUEST_AFTER_WRONG_ENTRY
}

class VpnConnectionHandler(
    private val gatewayAddress: String,
    onCredentialRequest: (
        VPN_CREDENTIAL_REQUEST,
        onEntryValidate: (
            username: String?,
            password: String?
        ) -> Unit
    ) -> Unit
) {

    private val vpnState: MutableLiveData<VPNConnectionStateMsg> = MutableLiveData()
    val vpnStateListener: LiveData<VPNConnectionStateMsg> = vpnState
    private var isBoundToService = false
    private var serviceMessenger: Messenger? = null
    private val incomingMessageFromVpnService = IncomingMessageFromVpnServiceHandler(
        {
            vpnState.postValue(it)
        },
        onCredentialRequest
    )
    private val vpnConnectionHandlerMessenger = Messenger(incomingMessageFromVpnService)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            serviceMessenger = Messenger(service)
            isBoundToService = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            onServiceDisconnected()
        }
    }

    private fun onServiceDisconnected() {
        serviceMessenger = null
        isBoundToService = false
    }

    init {
        if (gatewayAddress.isBlank())
            throw IllegalArgumentException("Please enter legal VPN gateway address")
    }

    fun stopVPN(context: Context) {
        try {
            context.applicationContext.unbindService(connection)
            onServiceDisconnected()
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, VPNService::class.java).apply {
                    action = ACTION_DISCONNECT
                }
            )
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
    }

    fun startVPN(activity: Activity) =
        commonStarter(activity.applicationContext) { intent, requestCode ->
            activity.startActivityForResult(intent, requestCode)
        }

    fun startVPN(fragment: Fragment) =
        commonStarter(fragment.requireContext().applicationContext) { intent, requestCode ->
            fragment.startActivityForResult(intent, requestCode)
        }

    private fun commonStarter(context: Context, startActivityForResult: (Intent, Int) -> Unit) {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, 0)
        } else {
            handleActivityResult(context, RESULT_OK)
        }
    }

    fun handleActivityResult(context: Context, result: Int) {
        if (result == RESULT_OK) {
            val serviceIntent = Intent(context.applicationContext, VPNService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(vpnGatewayKey, gatewayAddress)
                putExtra(vpnBundleExtraConnectionKey, Bundle().apply {
                    putBinder(
                        vpnConnectionHandlerMessengerIBinderKey,
                        vpnConnectionHandlerMessenger.binder
                    )
                })
            }
            ContextCompat.startForegroundService(
                context.applicationContext,
                serviceIntent
            )
            context.applicationContext.bindService(
                serviceIntent,
                connection,
                Context.BIND_AUTO_CREATE
            )
        }
    }

    class IncomingMessageFromVpnServiceHandler(
        private val handleVpnStateMessage: (VPNConnectionStateMsg) -> Unit,
        private val onCredentialRequest: (
            VPN_CREDENTIAL_REQUEST,
            onEntryValidate: (
                username: String?,
                password: String?
            ) -> Unit
        ) -> Unit
    ) : Handler() {

        private lateinit var responseMessengerForCredentialRequest: Messenger

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                VPN_STATE -> {
                    with(msg.data) {
                        if (containsKey(vpnStateKey) &&
                            containsKey(vpnInternalMessageLevelKey) &&
                            containsKey(vpnInternalMessageKey)
                        ) {
                            handleVpnStateMessage(
                                VPNConnectionStateMsg(
                                    state = getSerializable(vpnStateKey) as VPNConnectionState,
                                    messageLevel = getSerializable(vpnInternalMessageLevelKey) as VPNMessageLevel,
                                    message = getString(vpnInternalMessageKey)
                                )
                            )
                        }
                    }
                }
                REQUEST_VPN_CREDENTIAL -> {
                    responseMessengerForCredentialRequest = msg.replyTo
                    onCredentialRequest(
                        if (
                            with(msg.data) {
                                containsKey(vpnAuthFailedKey) && getBoolean(vpnAuthFailedKey)
                            }
                        ) {
                            VPN_CREDENTIAL_REQUEST.REQUEST_AFTER_WRONG_ENTRY
                        } else {
                            VPN_CREDENTIAL_REQUEST.NEW_REQUEST
                        }
                    ) { username, password ->
                        val response = Message.obtain(null, CREDENTIAL_FOR_VPN_CONNECTION).apply {
                            if (!username.isNullOrBlank() || !password.isNullOrBlank()) {
                                data = bundleOf(
                                    VPN_USERNAME to username,
                                    VPN_PASSWORD to password
                                )
                            }
                        }
                        if (::responseMessengerForCredentialRequest.isInitialized)
                            responseMessengerForCredentialRequest.send(response)
                    }
                }
            }
        }
    }

    companion object {
        init {
            try {
                System.loadLibrary("openconnect")
            } catch (exception: Exception) {
                exception.printStackTrace()
            }
        }
    }
}