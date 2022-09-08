package org.ergoplatform.ios.mosaik

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.ergoplatform.ios.CrashHandler
import org.ergoplatform.ios.IosCacheManager
import org.ergoplatform.ios.ui.*
import org.ergoplatform.mosaik.AppMosaikRuntime
import org.ergoplatform.mosaik.MosaikDialog
import org.ergoplatform.mosaik.MosaikGuidManager
import org.ergoplatform.mosaik.model.MosaikManifest
import org.ergoplatform.mosaik.model.actions.ErgoAuthAction
import org.ergoplatform.mosaik.model.actions.ErgoPayAction
import org.ergoplatform.uilogic.STRING_BUTTON_BACK
import org.ergoplatform.uilogic.STRING_BUTTON_RETRY
import org.robovm.apple.coregraphics.CGRect
import org.robovm.apple.uikit.*

class MosaikViewController(
    private val appUrl: String,
    private val appName: String?,
) : ViewControllerWithKeyboardLayoutGuide() {

    private lateinit var mosaikView: MosaikView
    private lateinit var waitingView: UIView
    private lateinit var scrollView: UIScrollView
    private lateinit var noAppLoadedView: NoAppLoadedView

    override fun viewDidLoad() {
        super.viewDidLoad()

        title = appName ?: ""
        view.backgroundColor = UIColor.systemBackground()

        val appDelegate = getAppDelegate()
        mosaikRuntime.apply {
            appDatabase = appDelegate.database
            guidManager.appDatabase = appDatabase
            cacheFileManager = IosCacheManager()
            stringProvider = IosStringProvider(appDelegate.texts)
            preferencesProvider = appDelegate.prefs

            loadUrlEnteredByUser(this@MosaikViewController.appUrl)
        }

        mosaikView = MosaikView(mosaikRuntime)
        scrollView = mosaikView.wrapInVerticalScrollView(centerContent = true)

        view.addSubview(scrollView)
        scrollView.topToSuperview(true).widthMatchesSuperview(true).bottomToKeyboard(this)
        waitingView = WaitingView().apply {
            isHidden = true
        }
        view.addSubview(waitingView)
        waitingView.topToSuperview(true).widthMatchesSuperview(true).bottomToKeyboard(this)

        noAppLoadedView = NoAppLoadedView()
        view.addSubview(noAppLoadedView)
        noAppLoadedView.centerVertical().centerHorizontal(true)

        navigationItem.setHidesBackButton(true)
        val backButton = UIBarButtonItem(appDelegate.texts.get(STRING_BUTTON_BACK), UIBarButtonItemStyle.Plain)
        navigationItem.leftBarButtonItem = backButton
        backButton.setOnClickListener {
            if (mosaikRuntime.canNavigateBack())
                mosaikRuntime.navigateBack()
            else
                navigationController.popViewController(true)
        }
    }

    override fun viewDidAppear(animated: Boolean) {
        super.viewDidAppear(animated)
        viewControllerScope.launch {
            mosaikRuntime.viewTree.contentState.collect {
                runOnMainThread {
                    mosaikView.updateView()
                }
            }
        }
        viewControllerScope.launch {
            mosaikRuntime.viewTree.uiLockedState.collect { locked ->
                if (waitingView.isHidden == locked) runOnMainThread {
                    waitingView.isHidden = !locked
                }
            }
        }
        onResume()
    }

    override fun onResume() {
        super.onResume()
        mosaikRuntime.checkViewTreeValidity()
    }

    private val mosaikRuntime = object : AppMosaikRuntime(
        "Ergo Wallet App (iOS)",
        CrashHandler.getAppVersion() ?: "",
        platformType = {
            when (UIDevice.getCurrentDevice().userInterfaceIdiom) {
                UIUserInterfaceIdiom.Phone ->
                    org.ergoplatform.mosaik.model.MosaikContext.Platform.PHONE
                UIUserInterfaceIdiom.Pad ->
                    org.ergoplatform.mosaik.model.MosaikContext.Platform.TABLET
                else -> org.ergoplatform.mosaik.model.MosaikContext.Platform.DESKTOP
            }
        },
        MosaikGuidManager().apply {
            appDatabase = getAppDelegate().database
        }
    ) {
        override fun onAppNavigated(manifest: MosaikManifest) {
            runOnMainThread {
                title = manifest.appName
                // TODO favorite button
                scrollView.scrollToTop(false)
            }
        }

        override fun appNotLoaded(cause: Throwable) {
            runOnMainThread {
                noAppLoadedView.errorLabel.text = getUserErrorMessage(cause)
                noAppLoadedView.isHidden = false
            }
        }

        override val coroutineScope: CoroutineScope
            get() = viewControllerScope

        override fun onAddressLongPress(address: String) {
            pasteToClipboard(address)
        }

        override fun openBrowser(url: String) {
            openUrlInBrowser(url)
        }

        override fun pasteToClipboard(text: String) {
            pasteToClipboard(text)
        }

        override fun runErgoAuthAction(action: ErgoAuthAction) {
            TODO("Not yet implemented")
        }

        override fun runErgoPayAction(action: ErgoPayAction) {
            TODO("Not yet implemented")
        }

        override fun runTokenInformationAction(tokenId: String) {
            TODO("Not yet implemented")
        }

        override fun scanQrCode(actionId: String) {
            TODO("Not yet implemented")
        }

        override fun showDialog(dialog: MosaikDialog) {
            val uac = UIAlertController("", dialog.message, UIAlertControllerStyle.Alert)
            uac.addAction(
                UIAlertAction(
                    dialog.positiveButtonText,
                    UIAlertActionStyle.Default
                ) {
                    dialog.positiveButtonClicked?.invoke()
                })

            dialog.negativeButtonText?.let { buttonText ->
                uac.addAction(
                    UIAlertAction(
                        buttonText,
                        UIAlertActionStyle.Default
                    ) {
                        dialog.negativeButtonClicked?.invoke()
                    }
                )
            }

            runOnMainThread {
                presentViewController(uac, true) {}
            }
        }

        override fun showErgoAddressChooser(valueId: String) {
            TODO("Not yet implemented")
        }

        override fun showErgoWalletChooser(valueId: String) {
            TODO("Not yet implemented")
        }

    }

    class WaitingView : UIView(CGRect.Zero()) {
        init {
            backgroundColor = UIColor.black().addAlpha(0.5)

            val progressIndicator = UIActivityIndicatorView()
            progressIndicator.activityIndicatorViewStyle = UIActivityIndicatorViewStyle.Large

            addSubview(progressIndicator)
            progressIndicator.centerVertical().centerHorizontal()

            progressIndicator.startAnimating()
        }
    }

    inner class NoAppLoadedView : UIStackView() {
        val errorLabel = Body1Label()

        init {
            axis = UILayoutConstraintAxis.Vertical
            spacing = DEFAULT_MARGIN * 3
            alignment = UIStackViewAlignment.Center
            isLayoutMarginsRelativeArrangement = true
            layoutMargins = UIEdgeInsets(
                DEFAULT_MARGIN * 3, DEFAULT_MARGIN * 3,
                DEFAULT_MARGIN * 3, DEFAULT_MARGIN * 3
            )

            addArrangedSubview(errorLabel)
            errorLabel.textAlignment = NSTextAlignment.Center

            val retryButton = PrimaryButton(getAppDelegate().texts.get(STRING_BUTTON_RETRY))
            retryButton.addOnTouchUpInsideListener { _, _ ->
                this.isHidden = true
                mosaikRuntime.retryLoadingLastAppNotLoaded()
            }
            addArrangedSubview(retryButton.fixedWidth(200.0))

            isHidden = true
        }
    }
}