package bj.fasegiar.vpnlibSample

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import bj.fasegiar.openconnectvpnlib.VPNConnectionStateMsg
import bj.fasegiar.openconnectvpnlib.VPN_CREDENTIAL_REQUEST
import bj.fasegiar.openconnectvpnlib.VpnConnectionHandler
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.dialog_view_for_failed_auth_layout.view.*
import kotlinx.android.synthetic.main.dialog_view_layout.view.*

class MainActivity : AppCompatActivity() {

    private var vpnConnectionHandler: VpnConnectionHandler? = null
    private val vpnStateObserver = Observer<VPNConnectionStateMsg> {
        Toast.makeText(this, "$it", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vpnConnectionHandler = VpnConnectionHandler(
            "gatewayAddress"
        ) { vpnCredentialRequest, onEntryValidate ->
            when (vpnCredentialRequest) {
                VPN_CREDENTIAL_REQUEST.NEW_REQUEST -> {
                    AlertDialog.Builder(this).apply {
                        val dialogView = layoutInflater.inflate(R.layout.dialog_view_layout, null)
                        setView(dialogView)
                        val dialog = create()
                        dialogView.apply {
                            validateButton.setOnClickListener {
                                onEntryValidate(
                                    this.pseudo.text.toString(),
                                    this.key.text.toString()
                                )
                                dialog.dismiss()
                            }

                            abortButton.setOnClickListener {
                                onEntryValidate(
                                    null,
                                    null
                                )
                                dialog.dismiss()
                            }
                        }
                        dialog.show()
                    }
                }
                VPN_CREDENTIAL_REQUEST.REQUEST_AFTER_WRONG_ENTRY -> {
                    AlertDialog.Builder(this).apply {
                        val dialogView = layoutInflater.inflate(
                            R.layout.dialog_view_for_failed_auth_layout,
                            null
                        )
                        setView(dialogView)
                        val dialog = create()
                        dialogView.apply {
                            failed_validateButton.setOnClickListener {
                                onEntryValidate(
                                    this.failed_pseudo.text.toString(),
                                    this.failed_key.text.toString()
                                )
                                dialog.dismiss()
                            }

                            failed_abortButton.setOnClickListener {
                                onEntryValidate(
                                    null,
                                    null
                                )
                                dialog.dismiss()
                            }
                        }
                        dialog.show()
                    }
                }
            }
        }.apply {
            vpnStateListener.observe(this@MainActivity, vpnStateObserver)
        }

        launchVpn.setOnClickListener {
            vpnConnectionHandler?.startVPN(this)
        }

        stopVpn.setOnClickListener {
            vpnConnectionHandler?.stopVPN(this)
        }
    }

    override fun onDestroy() {
        vpnConnectionHandler?.stopVPN(this)
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        vpnConnectionHandler?.handleActivityResult(this, resultCode)
    }
}