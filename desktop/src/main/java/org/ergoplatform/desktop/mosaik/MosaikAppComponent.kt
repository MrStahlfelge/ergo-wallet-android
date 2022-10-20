package org.ergoplatform.desktop.mosaik

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.loadImageBitmap
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.push
import com.arkivanov.essenty.lifecycle.doOnResume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ergoplatform.ApiServiceManager
import org.ergoplatform.Application
import org.ergoplatform.compose.tokens.getAppMosaikTokenLabelBuilder
import org.ergoplatform.desktop.appVersionString
import org.ergoplatform.desktop.ui.copyToClipboard
import org.ergoplatform.desktop.ui.getQrCodeImageBitmap
import org.ergoplatform.desktop.ui.navigation.NavClientScreenComponent
import org.ergoplatform.desktop.ui.navigation.NavHostComponent
import org.ergoplatform.desktop.ui.navigation.ScreenConfig
import org.ergoplatform.desktop.wallet.ChooseWalletListDialog
import org.ergoplatform.desktop.wallet.addresses.ChooseAddressesListDialog
import org.ergoplatform.ergoauth.isErgoAuthRequest
import org.ergoplatform.mosaik.*
import org.ergoplatform.mosaik.model.MosaikContext
import org.ergoplatform.mosaik.model.MosaikManifest
import org.ergoplatform.mosaik.model.actions.ErgoAuthAction
import org.ergoplatform.mosaik.model.actions.ErgoPayAction
import org.ergoplatform.persistance.Wallet
import org.ergoplatform.transactions.isErgoPaySigningRequest
import org.ergoplatform.uilogic.STRING_LABEL_COPIED

class MosaikAppComponent(
    private val appTitle: String?,
    private val appUrl: String,
    private val componentContext: ComponentContext,
    navHost: NavHostComponent,
) : NavClientScreenComponent(navHost), ComponentContext by componentContext {

    override val appBarLabel: String
        get() = manifestState.value?.appName ?: appTitle ?: appUrl

    private val isFavoriteState = mutableStateOf(false)

    private var runOnResume: (() -> Unit)? = null

    override val actions: @Composable RowScope.() -> Unit
        get() = {
            IconButton(
                mosaikRuntime::switchFavorite,
                enabled = mosaikRuntime.appUrl != null,
            ) {
                Icon(
                    if (isFavoriteState.value) Icons.Default.Star
                    else Icons.Default.StarBorder,
                    null
                )
            }
        }

    private val mosaikRuntime = object : AppMosaikRuntime(
        "Ergo Wallet App (Desktop)",
        appVersionString,
        { MosaikContext.Platform.DESKTOP },
        MosaikGuidManager().apply {
            appDatabase = Application.database
        },
    ) {
        override val coroutineScope: CoroutineScope
            get() = componentScope()

        override fun openBrowser(url: String) {
            org.ergoplatform.desktop.ui.openBrowser(url)
        }

        override fun pasteToClipboard(text: String) {
            text.copyToClipboard()
            navHost.showSnackbar(Application.texts.getString(STRING_LABEL_COPIED))
        }

        override fun onAddressLongPress(address: String) {
            pasteToClipboard(address)
        }

        override fun runErgoAuthAction(action: ErgoAuthAction) {
            if (isErgoAuthRequest(action.url)) {
                val runOnEaComplete: (() -> Unit)? = action.onFinished?.let { onFinishedAction ->
                    { runOnResume = { runAction(onFinishedAction) } }
                }
                router.push(
                    ScreenConfig.ErgoAuth(action.url, null, onCompleted = runOnEaComplete ?: {})
                )
            } else {
                raiseError(IllegalArgumentException("ErgoAuthAction without actual authentication request: ${action.url}"))
            }
        }

        override fun runErgoPayAction(action: ErgoPayAction) {
            if (isErgoPaySigningRequest(action.url)) {
                val runOnEpComplete: (() -> Unit)? = action.onFinished?.let { onFinishedAction ->
                    { runOnResume = { runAction(onFinishedAction) } }
                }

                router.push(
                    ScreenConfig.ErgoPay(
                        action.url,
                        null,
                        null,
                        onCompleted = runOnEpComplete ?: {})
                )
            } else {
                raiseError(IllegalArgumentException("ErgoPayAction without actual signing request: ${action.url}"))
            }
        }

        override fun runTokenInformationAction(tokenId: String) {
            router.push(ScreenConfig.TokenInformation(tokenId))
        }

        override fun scanQrCode(actionId: String) {
            router.push(ScreenConfig.QrCodeScanner { qrCode ->
                qrCodeScanned(actionId, qrCode)
            })
        }

        override fun showDialog(dialog: MosaikDialog) {
            navHost.dialogHandler.showDialog(dialog)
        }

        override fun onAppNavigated(manifest: MosaikManifest) {
            manifestState.value = manifest
            isFavoriteState.value = isFavoriteApp
        }

        override fun appNotLoaded(cause: Throwable) {
            noAppLoadedErrorMessage.value = getUserErrorMessage(cause)
        }

        override fun startWalletChooser() {
            componentScope().launch {
                chooseWalletDialog.value =
                    Application.database.walletDbProvider.getWalletsWithStates()
            }
        }

        override fun startAddressChooser() {
            chooseAddressDialog.value = walletForAddressChooser
        }
    }

    init {
        lifecycle.doOnResume {
            runOnResume?.invoke()
            runOnResume = null
            mosaikRuntime.checkViewTreeValidity()
        }

        mosaikRuntime.appDatabase = Application.database
        mosaikRuntime.guidManager.appDatabase = Application.database
        mosaikRuntime.cacheFileManager = Application.filesCache
        mosaikRuntime.stringProvider = Application.texts
        mosaikRuntime.preferencesProvider = Application.prefs

        MosaikComposeConfig.apply {
            convertByteArrayToImageBitmap = DesktopImageLoader::loadAndScaleImage
            convertQrCodeContentToImageBitmap = ::getQrCodeImageBitmap
            interceptReturnForImeAction = true
            DropDownMenu = { expanded,
                             dismiss,
                             modifier,
                             content ->
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = dismiss,
                    modifier = modifier,
                    content = content,
                )
            }

            DropDownItem = { onClick, content ->
                DropdownMenuItem(onClick = onClick, content = content)
            }

            TokenLabel = getAppMosaikTokenLabelBuilder(
                tokenDb = { Application.database.tokenDbProvider },
                apiService = { ApiServiceManager.getOrInit(Application.prefs) },
                stringResolver = { Application.texts }
            )
        }
        mosaikRuntime.loadUrlEnteredByUser(appUrl)
    }

    private val noAppLoadedErrorMessage = mutableStateOf<String?>(null)
    private val manifestState = mutableStateOf<MosaikManifest?>(null)
    private val chooseWalletDialog = mutableStateOf<List<Wallet>?>(null)
    private val chooseAddressDialog = mutableStateOf<Wallet?>(null)

    @Composable
    override fun renderScreenContents(scaffoldState: ScaffoldState?) {
        MosaikAppScreen(
            mosaikRuntime.viewTree,
            noAppLoadedErrorMessage,
            retryClicked = {
                noAppLoadedErrorMessage.value = null
                mosaikRuntime.retryLoadingLastAppNotLoaded()
            }
        )

        if (chooseWalletDialog.value != null) {
            val walletList = chooseWalletDialog.value!!
            ChooseWalletListDialog(
                walletList, true,
                onWalletChosen = { walletConfig ->
                    val walletChosen = walletList.first { walletConfig == it.walletConfig }
                    chooseWalletDialog.value = null
                    mosaikRuntime.onWalletChosen(walletChosen)
                },
                onDismiss = { chooseWalletDialog.value = null },
            )
        }

        if (chooseAddressDialog.value != null) {
            ChooseAddressesListDialog(
                chooseAddressDialog.value!!,
                withAllAddresses = false,
                onAddressChosen = { walletAddress ->
                    chooseAddressDialog.value = null
                    mosaikRuntime.onAddressChosen(walletAddress!!.derivationIndex)
                },
                onDismiss = { chooseAddressDialog.value = null },
            )
        }

    }

    override fun onNavigateBack(): Boolean = mosaikRuntime.navigateBack()
}