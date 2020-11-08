package bj.fasegiar.openconnectvpnlib

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.*
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.bundleOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.infradead.libopenconnect.LibOpenConnect
import kotlin.coroutines.CoroutineContext
import kotlin.math.max

private const val CHANNEL_ID = "bj.fasegiar.openconnectvpnlib.VPNService"
internal const val ACTION_CONNECT = "bj.fasegiar.openconnectvpnlib.VPNService.ACTION_CONNECT"
internal const val ACTION_DISCONNECT = "bj.fasegiar.openconnectvpnlib.VPNService.ACTION_DISCONNECT"
internal const val CREDENTIAL_FOR_VPN_CONNECTION = 0xA1
internal const val VPN_USERNAME = "bj.fasegiar.openconnectvpnlib.VPNService.VPN_USERNAME"
internal const val VPN_PASSWORD = "bj.fasegiar.openconnectvpnlib.VPNService.VPN_PASSWORD"

@Suppress("BlockingMethodInNonBlockingContext")
internal class VPNService : VpnService(), CoroutineScope {
    private val job = Job()
    private val mutex = Mutex()
    private var vpnCurrentState = VPNConnectionState.UNKNOWN
    private var androidOpenConnect: AndroidOpenConnect? = null
    private lateinit var currentVpnGateway: String
    private val incomingMessageHandler = IncomingMessageHandler()
    private val messenger = Messenger(incomingMessageHandler)
    private var vpnConnectionHandlerMessenger: Messenger? = null

    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.IO

    override fun onBind(intent: Intent?): IBinder? {
        return messenger.binder
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return intent?.run {
            if (action.orEmpty().contentEquals(ACTION_CONNECT) &&
                hasExtra(vpnGatewayKey) &&
                hasExtra(vpnBundleExtraConnectionKey)
            ) {
                if (vpnCurrentState in listOf(
                        VPNConnectionState.UNKNOWN,
                        VPNConnectionState.DISCONNECTED
                    )
                ) {
                    val extraBundle = getBundleExtra(vpnBundleExtraConnectionKey)
                    if (extraBundle?.containsKey(vpnConnectionHandlerMessengerIBinderKey) == true) {
                        vpnConnectionHandlerMessenger =
                            Messenger(extraBundle.getBinder(vpnConnectionHandlerMessengerIBinderKey))
                    }
                    currentVpnGateway = getStringExtra(vpnGatewayKey)!!
                    launchVpnConnection()
                }
                START_STICKY
            } else {
                if (action.orEmpty().contentEquals(ACTION_DISCONNECT)) {
                    disconnectVpn()
                }
                START_NOT_STICKY
            }
        } ?: START_NOT_STICKY
    }

    private fun disconnectVpn() {
        androidOpenConnect?.cancel()
        stopForeground(true)
    }

    private fun launchVpnConnection() {
        updateForegroundNotification()
        launch {
            try {
                connectToVPNHost(currentVpnGateway).collect {
                    val message = Message.obtain(null, VPN_STATE).apply {
                        data = bundleOf(
                            vpnStateKey to it.state,
                            vpnInternalMessageLevelKey to it.messageLevel,
                            vpnInternalMessageKey to it.message
                        )
                    }
                    vpnConnectionHandlerMessenger?.send(message)
                }
            } catch (exception: Exception) {
                finalizeVpnConnection()
            }
        }
    }

    private suspend fun VPNConnectionState.updateCurrentState(): VPNConnectionState {
        mutex.withLock {
            vpnCurrentState = this
        }
        return this
    }

    private fun updateForegroundNotification(notificationMessage: String = "Openconnect VPN") {
        if (Build.VERSION.SDK_INT >= 26) {
            createNotificationChannel()
        }
        startForeground(
            0xA, NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentText(notificationMessage)
                .build()
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() = with(NotificationManagerCompat.from(this)) {
        val notificationChannel = NotificationChannel(
            CHANNEL_ID,
            this@VPNService::class.java.canonicalName,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            lightColor = Color.RED
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        createNotificationChannel(notificationChannel)
    }

    private fun connectToVPNHost(host: String) = flow {
        androidOpenConnect = AndroidOpenConnect(
            service = this@VPNService,
            inheritedScope = this@VPNService,
            requestAuthenticationInfo = { isAuthFailed ->
                incomingMessageHandler.latestUserCredentialEntered = CompletableDeferred()
                val message = Message.obtain(null, REQUEST_VPN_CREDENTIAL).apply {
                    replyTo = messenger
                    data = bundleOf(
                        vpnAuthFailedKey to isAuthFailed
                    )
                }
                vpnConnectionHandlerMessenger?.send(message)
                incomingMessageHandler.latestUserCredentialEntered
            }
        ).apply {
            internalState.onEach {
                val updatedVPNConnectionStateMsg = it.copy(
                    state = if (it.state == VPNConnectionState.UNKNOWN) vpnCurrentState
                    else it.state.updateCurrentState()
                )
                val message = Message.obtain(null, VPN_STATE).apply {
                    data = bundleOf(
                        vpnStateKey to updatedVPNConnectionStateMsg.state,
                        vpnInternalMessageLevelKey to updatedVPNConnectionStateMsg.messageLevel,
                        vpnInternalMessageKey to updatedVPNConnectionStateMsg.message
                    )
                }
                vpnConnectionHandlerMessenger?.send(message)
            }.launchIn(this@VPNService)
        }

        suspend fun VpnService.Builder.addSubnetRoutes(subNets: List<String>) =
            subNets.forEach {
                it.trim().let { subNet ->
                    try {
                        if (subNet.contains(':')) {
                            subNet.split('/').let { subNetParts ->
                                if (subNetParts.size == 1) {
                                    addRoute(subNetParts[0], 128)
                                } else {
                                    addRoute(subNetParts[0], subNetParts[1].toInt())
                                }
                            }
                        } else {
                            val cidr = CIDR(subNet.takeIf { conditional ->
                                conditional.contains('/')
                            } ?: "$subNet/32")
                            addRoute(cidr.hostIp, cidr.maskLength)
                        }
                    } catch (exception: Exception) {
                        emit(
                            VPNConnectionStateMsg(
                                state = vpnCurrentState,
                                messageLevel = VPNMessageLevel.ERR,
                                message = "ROUTE: skipping invalid route $subNet"
                            )
                        )
                    }
                }
                ensureActive()
            }

        fun VpnService.Builder.addDefaultRoutes(ip: LibOpenConnect.IPInfo, subNets: List<String>) {
            var ip4def = true
            var ip6def = true

            subNets.forEach { subNet ->
                if (subNet.contains(':')) {
                    ip6def = false
                } else {
                    ip4def = false
                }
            }

            if (ip4def && ip.addr != null) {
                addRoute("0.0.0.0", 0)
            }

            ensureActive()

            if (ip6def && ip.netmask6 != null) {
                addRoute("::", 0)
            }

            ensureActive()
        }

        suspend fun VpnService.Builder.setupIpInfo() {
            val ip = androidOpenConnect?.ipInfo
            lateinit var cidr: CIDR

            if (ip?.addr != null && ip.netmask != null) {
                cidr = CIDR(ip.addr, ip.netmask)
                addAddress(cidr.hostIp, cidr.maskLength)
            }

            ensureActive()

            if (ip?.netmask6 != null) {
                val addressPart = ip.netmask6.split('/')
                if (addressPart.size == 2) {
                    val netMask = addressPart[1].toInt()
                    addAddress(addressPart[0], netMask)
                }
            }

            ensureActive()

            setMtu(max(ip!!.MTU, 1280))

            val dns = ip.DNS
            val domain = ip.domain
            val subNets = ip.splitIncludes

            addDefaultRoutes(ip, subNets)
            addSubnetRoutes(subNets)

            ensureActive()

            dns.forEach {
                it.trim().let { dnsServer ->
                    try {
                        addDnsServer(dnsServer)
                        addRoute(dnsServer, if (dnsServer.contains(':')) 128 else 32)
                    } catch (exception: Exception) {
                        emit(
                            VPNConnectionStateMsg(
                                state = vpnCurrentState,
                                messageLevel = VPNMessageLevel.ERR,
                                message = "DNS: skipping invalid server $dnsServer"
                            )
                        )
                    }
                }
                ensureActive()
            }
            if (domain != null) addSearchDomain(domain)

            ensureActive()
        }

        suspend fun connectToVPN(): Boolean {
            emit(
                VPNConnectionStateMsg(
                    state = VPNConnectionState.CONNECTING.updateCurrentState(),
                    messageLevel = VPNMessageLevel.INFO,
                    message = "Begin VPN connection"
                )
            )

            androidOpenConnect!!.parseURL(host)

            ensureActive()

            androidOpenConnect!!.obtainCookie().also {
                if (it != 0) {
                    emit(
                        VPNConnectionStateMsg(
                            state = VPNConnectionState.DISCONNECTED.updateCurrentState(),
                            messageLevel = if (it < 0) VPNMessageLevel.ERR
                            else VPNMessageLevel.INFO,
                            message = if (it < 0) "Unknown error while obtaining cookie from VPN"
                            else "User aborts Auth"
                        )
                    )
                    return false
                }
            }

            emit(
                VPNConnectionStateMsg(
                    state = VPNConnectionState.AUTHENTICATED.updateCurrentState()
                )
            )

            if (androidOpenConnect?.makeCSTPConnection() != 0) {
                emit(
                    VPNConnectionStateMsg(
                        state = VPNConnectionState.DISCONNECTED.updateCurrentState(),
                        messageLevel = VPNMessageLevel.ERR,
                        message = "Error when establishing CSTP Connection"
                    )
                )
                return false
            }

            ensureActive()

            val parcelFileDescriptor = with(Builder()) {
                setupIpInfo()

                ensureActive()

                try {
                    establish()
                } catch (exception: Exception) {
                    emit(
                        VPNConnectionStateMsg(
                            state = VPNConnectionState.DISCONNECTED.updateCurrentState(),
                            messageLevel = VPNMessageLevel.ERR,
                            message = "Exception during establish(): ${exception.localizedMessage}"
                        )
                    )
                    return false
                }
            }

            ensureActive()

            if (parcelFileDescriptor == null || androidOpenConnect?.setupTunFD(parcelFileDescriptor.fd) != 0) {
                emit(
                    VPNConnectionStateMsg(
                        state = VPNConnectionState.DISCONNECTED.updateCurrentState(),
                        messageLevel = VPNMessageLevel.ERR,
                        message = "Error setting up tunnel fd"
                    )
                )
                return false
            }

            emit(
                VPNConnectionStateMsg(
                    state = VPNConnectionState.CONNECTED.updateCurrentState()
                )
            )

            androidOpenConnect?.setupDTLS(60)

            ensureActive()

            while (true) {
                if (androidOpenConnect!!.mainloop(300, LibOpenConnect.RECONNECT_INTERVAL_MIN) < 0) {
                    break
                }

                ensureActive()
            }

            try {
                parcelFileDescriptor.close()
            } catch (exception: Exception) {
                emit(
                    VPNConnectionStateMsg(
                        state = VPNConnectionState.DISCONNECTED.updateCurrentState(),
                        messageLevel = VPNMessageLevel.ERR,
                        message = "Error closing parcelFileDescriptor"
                    )
                )
            }
            return true
        }

        if (!connectToVPN()) {
            emit(
                VPNConnectionStateMsg(
                    state = VPNConnectionState.DISCONNECTED.updateCurrentState(),
                    messageLevel = VPNMessageLevel.ERR,
                    message = "VPN terminated with errors"
                )
            )
        } else {
            emit(
                VPNConnectionStateMsg(
                    state = VPNConnectionState.DISCONNECTED.updateCurrentState(),
                    messageLevel = VPNMessageLevel.INFO,
                    message = "VPN terminated"
                )
            )
        }
    }.conflate().onCompletion {
        finalizeVpnConnection()
    }.cancellable()

    private fun finalizeVpnConnection() {
        androidOpenConnect?.destroy()
        androidOpenConnect = null
        vpnCurrentState = VPNConnectionState.DISCONNECTED
        val message = Message.obtain(null, VPN_STATE).apply {
            data = bundleOf(
                vpnStateKey to vpnCurrentState,
                vpnInternalMessageLevelKey to VPNMessageLevel.NONE,
                vpnInternalMessageKey to null
            )
        }
        vpnConnectionHandlerMessenger?.send(message)
        vpnConnectionHandlerMessenger = null
        stopForeground(true)
    }

    override fun onRevoke() {
        finalizeVpnConnection()
    }

    class IncomingMessageHandler : Handler() {
        lateinit var latestUserCredentialEntered: CompletableDeferred<UserCredential?>
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                CREDENTIAL_FOR_VPN_CONNECTION -> {
                    if (::latestUserCredentialEntered.isInitialized) {
                        with(msg.data) {
                            if (containsKey(VPN_USERNAME) && containsKey(VPN_PASSWORD)) {
                                latestUserCredentialEntered.complete(
                                    UserCredential(
                                        username = getString(VPN_USERNAME, ""),
                                        password = getString(VPN_PASSWORD, "")
                                    )
                                )
                            } else latestUserCredentialEntered.complete(null)
                        }
                    }
                }
            }
        }
    }
}