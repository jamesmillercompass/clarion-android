/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.widgets

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebMessage
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.args
import com.airbnb.mvrx.withState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import im.vector.app.R
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.openUrlInExternalBrowser
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.databinding.FragmentRoomWidgetBinding
import im.vector.app.features.webview.WebEventListener
import im.vector.app.features.widgets.webview.WebviewPermissionUtils
import im.vector.app.features.widgets.webview.clearAfterWidget
import im.vector.app.features.widgets.webview.setupForWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.parcelize.Parcelize
import org.matrix.android.sdk.api.session.terms.TermsService
import timber.log.Timber
import java.io.IOException
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject

@Parcelize
data class WidgetArgs(
        val baseUrl: String,
        val kind: WidgetKind,
        val roomId: String,
        val widgetId: String? = null,
        val urlParams: Map<String, String> = emptyMap()
) : Parcelable

class WidgetFragment @Inject constructor(
        private val permissionUtils: WebviewPermissionUtils
) :
        VectorBaseFragment<FragmentRoomWidgetBinding>(),
        WebEventListener,
        OnBackPressed {

    private val fragmentArgs: WidgetArgs by args()
    private val viewModel: WidgetViewModel by activityViewModel()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentRoomWidgetBinding {
        return FragmentRoomWidgetBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setHasOptionsMenu(true)
        WebView.setWebContentsDebuggingEnabled(true);
        views.widgetWebView.setupForWidget(this)
        if (fragmentArgs.kind.isAdmin()) {
            viewModel.getPostAPIMediator().setWebView(views.widgetWebView)
        }
        viewModel.observeViewEvents {
            Timber.v("Observed view events: $it")
            when (it) {
                is WidgetViewEvents.DisplayTerms              -> displayTerms(it)
                is WidgetViewEvents.OnURLFormatted            -> loadFormattedUrl(it)
                is WidgetViewEvents.DisplayIntegrationManager -> displayIntegrationManager(it)
                is WidgetViewEvents.Failure                   -> displayErrorDialog(it.throwable)
                is WidgetViewEvents.Close                     -> Unit
            }
        }
        viewModel.handle(WidgetAction.LoadFormattedUrl)
    }

    private val termsActivityResultLauncher = registerStartForActivityResult {
        Timber.v("On terms results")
        if (it.resultCode == Activity.RESULT_OK) {
            viewModel.handle(WidgetAction.OnTermsReviewed)
        } else {
            vectorBaseActivity.finish()
        }
    }

    private val integrationManagerActivityResultLauncher = registerStartForActivityResult {
        if (it.resultCode == Activity.RESULT_OK) {
            viewModel.handle(WidgetAction.LoadFormattedUrl)
        }
    }

    override fun onDestroyView() {
        if (fragmentArgs.kind.isAdmin()) {
            viewModel.getPostAPIMediator().clearWebView()
        }
        views.widgetWebView.clearAfterWidget()
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        views.widgetWebView.let {
            it.resumeTimers()
            it.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        views.widgetWebView.let {
            it.pauseTimers()
            it.onPause()
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) = withState(viewModel) { state ->
        val widget = state.asyncWidget()
        menu.findItem(R.id.action_edit)?.isVisible = state.widgetKind != WidgetKind.INTEGRATION_MANAGER
        if (widget == null) {
            menu.findItem(R.id.action_refresh)?.isVisible = false
            menu.findItem(R.id.action_widget_open_ext)?.isVisible = false
            menu.findItem(R.id.action_delete)?.isVisible = false
            menu.findItem(R.id.action_revoke)?.isVisible = false
        } else {
            menu.findItem(R.id.action_refresh)?.isVisible = true
            menu.findItem(R.id.action_widget_open_ext)?.isVisible = true
            menu.findItem(R.id.action_delete)?.isVisible = state.canManageWidgets && widget.isAddedByMe
            menu.findItem(R.id.action_revoke)?.isVisible = state.status == WidgetStatus.WIDGET_ALLOWED && !widget.isAddedByMe
        }
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = withState(viewModel) { state ->
        when (item.itemId) {
            R.id.action_edit            -> {
                navigator.openIntegrationManager(
                        requireContext(),
                        integrationManagerActivityResultLauncher,
                        state.roomId,
                        state.widgetId,
                        state.widgetKind.screenId
                )
                return@withState true
            }
            R.id.action_delete          -> {
                deleteWidget()
                return@withState true
            }
            R.id.action_refresh         -> if (state.formattedURL.complete) {
                views.widgetWebView.reload()
                return@withState true
            }
            R.id.action_widget_open_ext -> if (state.formattedURL.complete) {
                openUrlInExternalBrowser(requireContext(), state.formattedURL.invoke())
                return@withState true
            }
            R.id.action_revoke          -> if (state.status == WidgetStatus.WIDGET_ALLOWED) {
                revokeWidget()
                return@withState true
            }
        }
        return@withState super.onOptionsItemSelected(item)
    }

    override fun onBackPressed(toolbarButton: Boolean): Boolean = withState(viewModel) { state ->
        if (state.formattedURL.complete) {
            if (views.widgetWebView.canGoBack()) {
                views.widgetWebView.goBack()
                return@withState true
            }
        }
        return@withState false
    }

    override fun invalidate() = withState(viewModel) { state ->
        Timber.v("Invalidate state: $state")
        when (state.formattedURL) {
            Uninitialized,
            is Loading -> {
                setStateError(null)
                views.widgetWebView.isInvisible = true
                views.widgetProgressBar.isIndeterminate = true
                views.widgetProgressBar.isVisible = true
            }
            is Success -> {
                setStateError(null)
                when (state.webviewLoadedUrl) {
                    Uninitialized -> {
                        views.widgetWebView.isInvisible = true
                    }
                    is Loading    -> {
                        setStateError(null)
                        views.widgetWebView.isInvisible = false
                        views.widgetProgressBar.isIndeterminate = true
                        views.widgetProgressBar.isVisible = true
                    }
                    is Success    -> {
                        views.widgetWebView.isInvisible = false
                        views.widgetProgressBar.isVisible = false
                        setStateError(null)
                    }
                    is Fail       -> {
                        views.widgetProgressBar.isInvisible = true
                        setStateError(state.webviewLoadedUrl.error.message)
                    }
                }
            }
            is Fail    -> {
                // we need to show Error
                views.widgetWebView.isInvisible = true
                views.widgetProgressBar.isVisible = false
                setStateError(state.formattedURL.error.message)
            }
        }
    }

    override fun shouldOverrideUrlLoading(url: String): Boolean {
        if (url.startsWith("intent://")) {
            try {
                val context = requireContext()
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    val packageManager: PackageManager = context.packageManager
                    val info = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    if (info != null) {
                        context.startActivity(intent)
                    } else {
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        openUrlInExternalBrowser(context, fallbackUrl)
                    }
                    return true
                }
            } catch (e: URISyntaxException) {
                Timber.d("Can't resolve intent://")
            }
        }
        return false
    }

    override fun onPageStarted(url: String) {
        viewModel.handle(WidgetAction.OnWebViewStartedToLoad(url))
    }

    override fun onPageFinished(url: String) {
        viewModel.handle(WidgetAction.OnWebViewLoadingSuccess(url))
        connectBluetoothDevice()
    }

    override fun onPageError(url: String, errorCode: Int, description: String) {
        viewModel.handle(WidgetAction.OnWebViewLoadingError(url, false, errorCode, description))
    }

    override fun onHttpError(url: String, errorCode: Int, description: String) {
        viewModel.handle(WidgetAction.OnWebViewLoadingError(url, true, errorCode, description))
    }

    private val permissionResultLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionUtils.onPermissionResult(result)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        permissionUtils.promptForPermissions(
                title = R.string.room_widget_resource_permission_title,
                request = request,
                context = requireContext(),
                activity = requireActivity(),
                activityResultLauncher = permissionResultLauncher
        )
    }

    private fun displayTerms(displayTerms: WidgetViewEvents.DisplayTerms) {
        navigator.openTerms(
                context = requireContext(),
                activityResultLauncher = termsActivityResultLauncher,
                serviceType = TermsService.ServiceType.IntegrationManager,
                baseUrl = displayTerms.url,
                token = displayTerms.token
        )
    }

    private fun loadFormattedUrl(event: WidgetViewEvents.OnURLFormatted) {
        views.widgetWebView.clearHistory()
        views.widgetWebView.loadUrl(event.formattedURL)
    }

    private fun setStateError(message: String?) {
        if (message == null) {
            views.widgetErrorLayout.isVisible = false
            views.widgetErrorText.text = null
        } else {
            views.widgetProgressBar.isVisible = false
            views.widgetErrorLayout.isVisible = true
            views.widgetWebView.isInvisible = true
            views.widgetErrorText.text = getString(R.string.room_widget_failed_to_load, message)
        }
    }

    private fun displayIntegrationManager(event: WidgetViewEvents.DisplayIntegrationManager) {
        navigator.openIntegrationManager(
                context = requireContext(),
                activityResultLauncher = integrationManagerActivityResultLauncher,
                roomId = fragmentArgs.roomId,
                integId = event.integId,
                screen = event.integType
        )
    }

    private fun deleteWidget() {
        MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.widget_delete_message_confirmation)
                .setPositiveButton(R.string.action_remove) { _, _ ->
                    viewModel.handle(WidgetAction.DeleteWidget)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
    }

    private fun revokeWidget() {
        viewModel.handle(WidgetAction.RevokeWidget)
    }

    // Bluetooth hacks

    private fun getRequiredBluetoothPermissions(): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return listOf(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return listOf(Manifest.permission.BLUETOOTH)
    }

    private val bluetoothPermissionLauncher = registerForPermissionsResult { allGranted, _ ->
        if (allGranted) {
            onBluetoothPermissionGranted()
        } else {
            informInWebView("Could not acquire Bluetooth permissions")
        }
    }

    private var startedBluetoothConnection = false

    private fun connectBluetoothDevice() {
        if (startedBluetoothConnection) {
            return
        }
        startedBluetoothConnection = true
        if (checkPermissions(getRequiredBluetoothPermissions(), requireActivity(), bluetoothPermissionLauncher)) {
            onBluetoothPermissionGranted()
        }
    }

    private var bluetoothSocket: BluetoothSocket? = null

    @RequiresApi(Build.VERSION_CODES.M)
    private fun onBluetoothPermissionGranted() {
        val manager = requireContext().getSystemService<BluetoothManager>()
        val device = manager?.adapter?.bondedDevices?.firstOrNull {
            //it.bluetoothClass.hasService(0x6666)
            it.name.contains("PTT") || it.name.contains("B01")
        }

        if (device == null) {
            val devices = manager?.adapter?.bondedDevices?.joinToString { "${it.name} (${it.address})" }
            informInWebView("Could not locate PTT device among bonded devices $devices")
            return
        }

        //informInWebView("Connected to PTT device ${device.name} (${device.address})")
        Timber.i("Connected to PTT device ${device.name} (${device.address})")

        bluetoothSocket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"));
        // Alternatively: device.createInsecureRfcommSocketToServiceRecord(...)

        try {
            bluetoothSocket?.connect()
        } catch (e: IOException) {
            informInWebView("Failed to open RFCOMM socket: $e")
            return;
        }
        //informInWebView("Opened RFCOMM socket")

        //informInWebView("Created socket")



        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                var inputStream = bluetoothSocket?.inputStream
                val inputBuffer = ByteArray(1024)

                /*try {
                    bluetoothSocket?.connect()
                    inputStream = bluetoothSocket?.inputStream
                }  catch (e: IOException){
                    informInWebView("Failed to open RFCOMM socket: $e")
                    bluetoothSocket = null
                    return@async;
                }*/

                //informInWebView("Opened RFCOMM socket")

                while (isActive) {
                    /*if (bluetoothSocket?.isConnected != true) {
                        continue
                    }*/

                    try {
                        val numbytes = inputStream?.read(inputBuffer)
                        val strData = StandardCharsets.UTF_8.decode(numbytes?.let { ByteBuffer.wrap(inputBuffer, 0, it) });
                        //informInWebView("read $numbytes bytes: $strData, strdata is " + strData.length + " bytes, byte 6 is " + strData[6].code)

                        val widgetUri = Uri.parse(fragmentArgs.baseUrl)
                        //val widgetUri = Uri.EMPTY
                        if (strData.startsWith("+PTT=P")) {
                            //informInWebView("ptt down")

                            //val msg = JSONObject()
                            //msg.put("pttbutton", true)
                            yield()
                            requireActivity().runOnUiThread {
                                //views.widgetWebView.postWebMessage(WebMessage(msg.toString()), widgetUri)
                                views.widgetWebView.postWebMessage(WebMessage("pttp"), widgetUri)
                            }
                        } else if (strData.startsWith("+PTT=R")) {
                            //informInWebView("ptt up")
                            //val msg = JSONObject()
                            //msg.put("pttbutton", false)
                            yield()
                            requireActivity().runOnUiThread {
                                //views.widgetWebView.postWebMessage(WebMessage(msg.toString()), widgetUri)
                                views.widgetWebView.postWebMessage(WebMessage("pttr"), widgetUri)
                            }
                        }
                    } catch (e: IOException) {
                        yield()
//                        informInWebView("Failed to read from socket: $e")
                        break
                    }

                    //informInWebView("data: " + inputBuffer.to)
                    //val strData = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(inputBuffer, 0, num));
                    //informInWebView("<$strData>")


                    //informInWebView("got data " + StandardCharsets.UTF_8.decode(ByteBuffer.wrap(inputBuffer)))

                    /*if (inputBuffer[5].toInt() == 80) {
                        informInWebView("Start talking inferred from ${inputBuffer.slice(0..32)}...")
                    } else {
                        informInWebView("Stop talking inferred from ${inputBuffer.slice(0..32)}...")
                    }*/
                }
            }
        }
    }

    fun informInWebView(message: String) {
        requireActivity().runOnUiThread {
            views.widgetWebView.evaluateJavascript("alert('${message}');", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothSocket?.close()
    }
}
