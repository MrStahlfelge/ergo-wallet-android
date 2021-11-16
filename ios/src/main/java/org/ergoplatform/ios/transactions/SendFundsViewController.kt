package org.ergoplatform.ios.transactions

import com.badlogic.gdx.utils.I18NBundle
import kotlinx.coroutines.CoroutineScope
import org.ergoplatform.ErgoAmount
import org.ergoplatform.NodeConnector
import org.ergoplatform.URL_COLD_WALLET_HELP
import org.ergoplatform.ios.ui.*
import org.ergoplatform.toErgoAmount
import org.ergoplatform.transactions.TransactionResult
import org.ergoplatform.uilogic.*
import org.ergoplatform.uilogic.transactions.SendFundsUiLogic
import org.ergoplatform.utils.LogUtils
import org.ergoplatform.utils.formatFiatToString
import org.ergoplatform.wallet.addresses.getAddressLabel
import org.ergoplatform.wallet.getNumOfAddresses
import org.robovm.apple.coregraphics.CGRect
import org.robovm.apple.foundation.NSArray
import org.robovm.apple.foundation.NSRange
import org.robovm.apple.uikit.*

class SendFundsViewController(
    private val walletId: Int,
    private val derivationIdx: Int = -1,
    private val paymentRequest: String? = null
) : ViewControllerWithKeyboardLayoutGuide() {
    private lateinit var texts: I18NBundle
    private val uiLogic = IosSendFundsUiLogic()
    private lateinit var scrollView: UIView
    private lateinit var walletTitle: UILabel
    private lateinit var addressNameLabel: UILabel
    private lateinit var balanceLabel: UILabel
    private lateinit var fiatLabel: UILabel
    private lateinit var readOnlyHint: UITextView
    private lateinit var feeLabel: UILabel
    private lateinit var grossAmountLabel: UILabel
    private lateinit var sendButton: UIButton

    private lateinit var inputReceiver: UITextField
    private lateinit var inputErgoAmount: UITextField

    override fun viewDidLoad() {
        super.viewDidLoad()

        texts = getAppDelegate().texts
        title = texts.get(STRING_BUTTON_SEND)
        view.backgroundColor = UIColor.systemBackground()
        navigationController.navigationBar?.tintColor = UIColor.label()

        // TODO qr code scan
        //val uiBarButtonItem = UIBarButtonItem(UIBarButtonSystemItem.Action)
        //uiBarButtonItem.setOnClickListener {
        //}
        //navigationController.topViewController.navigationItem.rightBarButtonItem = uiBarButtonItem

        walletTitle = Body1Label()
        walletTitle.numberOfLines = 1
        addressNameLabel = Body1BoldLabel()
        addressNameLabel.numberOfLines = 1
        addressNameLabel.textColor = uiColorErgo
        balanceLabel = Body1Label()
        balanceLabel.numberOfLines = 1

        val introLabel = Body1Label()
        introLabel.text = texts.get(STRING_DESC_SEND_FUNDS)

        readOnlyHint = UITextView(CGRect.Zero()).apply {
            setHtmlText(texts.get(STRING_HINT_READ_ONLY).replace("href=\"\"", "href=\"$URL_COLD_WALLET_HELP\""))
            textColor = UIColor.label()
            tintColor = uiColorErgo
            textAlignment = NSTextAlignment.Center
            layer.borderWidth = 1.0
            layer.cornerRadius = 4.0
            layer.borderColor = UIColor.systemGray().cgColor
            font = UIFont.getSystemFont(FONT_SIZE_BODY1, UIFontWeight.Regular)

        }

        inputReceiver = createTextField().apply {
            placeholder = texts.get(STRING_LABEL_RECEIVER_ADDRESS)
            returnKeyType = UIReturnKeyType.Next
            delegate = object : UITextFieldDelegateAdapter() {
                override fun shouldReturn(textField: UITextField?): Boolean {
                    inputErgoAmount.becomeFirstResponder()
                    return true
                }
            }

            addOnEditingChangedListener {
                setHasError(false)
                uiLogic.receiverAddress = text
            }
        }
        inputErgoAmount = createTextField().apply {
            placeholder = texts.get(STRING_LABEL_AMOUNT)
            keyboardType = UIKeyboardType.NumbersAndPunctuation
            returnKeyType = UIReturnKeyType.Next
            delegate = object : UITextFieldDelegateAdapter() {
                override fun shouldChangeCharacters(
                    textField: UITextField?,
                    range: NSRange?,
                    string: String?
                ): Boolean {
                    // TODO does not work as intended (allows multiple dots)
                    return string?.matches(Regex("^\\d*\\.?(\\d)*$")) ?: true
                }

                override fun shouldReturn(textField: UITextField?): Boolean {
                    inputErgoAmount.resignFirstResponder()
                    return true
                }
            }
            addOnEditingChangedListener {
                setHasError(false)
                uiLogic.amountToSend = text.toErgoAmount() ?: ErgoAmount.ZERO
            }
        }

        fiatLabel = Body1Label()
        fiatLabel.textAlignment = NSTextAlignment.Right
        fiatLabel.isHidden = true

        feeLabel = Body1Label()
        grossAmountLabel = Headline1Label()
        grossAmountLabel.textAlignment = NSTextAlignment.Center

        sendButton = PrimaryButton(texts.get(STRING_BUTTON_SEND))
        sendButton.setImage(
            UIImage.getSystemImage(
                IMAGE_SEND,
                UIImageSymbolConfiguration.getConfigurationWithPointSizeWeightScale(
                    30.0,
                    UIImageSymbolWeight.Regular,
                    UIImageSymbolScale.Small
                )
            ), UIControlState.Normal
        )
        sendButton.tintColor = UIColor.label()
        sendButton.addOnTouchUpInsideListener { _, _ -> startPayment() }

        val buttonContainer = UIView()
        buttonContainer.addSubview(sendButton)
        sendButton.topToSuperview().bottomToSuperview().rightToSuperview().fixedWidth(120.0)

        val container = UIView()
        val stackView = UIStackView(
            NSArray(
                walletTitle,
                addressNameLabel,
                balanceLabel,
                readOnlyHint,
                introLabel,
                inputReceiver,
                inputErgoAmount,
                fiatLabel,
                feeLabel,
                grossAmountLabel,
                buttonContainer
            )
        )
        stackView.axis = UILayoutConstraintAxis.Vertical
        stackView.spacing = 2 * DEFAULT_MARGIN
        stackView.setCustomSpacing(0.0, walletTitle)
        stackView.setCustomSpacing(0.0, addressNameLabel)
        stackView.setCustomSpacing(0.0, inputErgoAmount)
        scrollView = container.wrapInVerticalScrollView()
        container.addSubview(stackView)
        stackView.topToSuperview(topInset = DEFAULT_MARGIN)
            .widthMatchesSuperview(inset = DEFAULT_MARGIN, maxWidth = MAX_WIDTH)
            .bottomToSuperview(bottomInset = DEFAULT_MARGIN)

        view.addSubview(scrollView)
        scrollView.topToSuperview().widthMatchesSuperview().bottomToKeyboard(this)
        scrollView.isHidden = true
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        uiLogic.initWallet(getAppDelegate().database, walletId, derivationIdx, paymentRequest)
    }

    private fun startPayment() {
        val checkResponse = uiLogic.checkCanMakePayment()

        inputReceiver.setHasError(checkResponse.receiverError)
        inputErgoAmount.setHasError(checkResponse.amountError)
        if (checkResponse.receiverError) {
            inputReceiver.becomeFirstResponder()
        } else if (checkResponse.amountError) {
            inputErgoAmount.becomeFirstResponder()
        }
        if (checkResponse.tokenError) {
            // TODO
        }

        if (checkResponse.canPay) {
            startAuthFlow(uiLogic.wallet!!.walletConfig) { mnemonic ->
                uiLogic.startPaymentWithMnemonicAsync(mnemonic, getAppDelegate().prefs)
            }
        }
    }

    inner class IosSendFundsUiLogic : SendFundsUiLogic() {
        var progressViewController: ProgressViewController? = null

        override val coroutineScope: CoroutineScope
            get() = viewControllerScope

        override fun notifyWalletStateLoaded() {
            runOnMainThread {
                walletTitle.text = texts.format(STRING_LABEL_SEND_FROM, wallet!!.walletConfig.displayName)
                readOnlyHint.isHidden = uiLogic.wallet!!.walletConfig.secretStorage != null
                sendButton.isEnabled = readOnlyHint.isHidden // TODO cold wallet
                scrollView.isHidden = false
            }
        }

        override fun notifyDerivedAddressChanged() {
            runOnMainThread {
                addressNameLabel.text = derivedAddress?.getAddressLabel(IosStringProvider(texts))
                    ?: texts.format(STRING_LABEL_ALL_ADDRESSES, wallet?.getNumOfAddresses())
            }
        }

        override fun notifyTokensChosenChanged() {
            // TODO
        }

        override fun notifyAmountsChanged() {
            runOnMainThread {
                feeLabel.text = texts.format(STRING_DESC_FEE, feeAmount.toStringRoundToDecimals())
                grossAmountLabel.text = grossAmount.toStringRoundToDecimals() + " ERG"
                val nodeConnector = NodeConnector.getInstance()
                fiatLabel.isHidden = (nodeConnector.fiatCurrency.isEmpty())
                fiatLabel.text = texts.format(
                    STRING_LABEL_FIAT_AMOUNT,
                    formatFiatToString(
                        amountToSend.toDouble() * nodeConnector.fiatValue.value.toDouble(),
                        nodeConnector.fiatCurrency, IosStringProvider(texts)
                    )
                )
            }
        }

        override fun notifyBalanceChanged() {
            runOnMainThread {
                balanceLabel.text = texts.format(STRING_LABEL_WALLET_BALANCE, balance.toStringRoundToDecimals())
            }
        }

        override fun notifyUiLocked(locked: Boolean) {
            runOnMainThread {
                if (locked) {
                    if (progressViewController == null) {
                        progressViewController = ProgressViewController()
                        progressViewController?.modalPresentationStyle = UIModalPresentationStyle.FormSheet
                        progressViewController?.isModalInPresentation = true
                        presentViewController(progressViewController!!, true) {}
                    }
                } else {
                    progressViewController?.dismissViewController(true) {}
                    progressViewController = null
                }
            }
        }

        override fun notifyHasTxId(txId: String) {
            LogUtils.logDebug("SendFunds", "Success, tx id $txId")
            // TODO show it
        }

        override fun notifyHasErgoTxResult(txResult: TransactionResult) {
            if (!txResult.success) {
                runOnMainThread {
                    val message =
                        texts.get(STRING_ERROR_SEND_TRANSACTION) + (txResult.errorMsg?.let { "\n\n$it" } ?: "")
                    val alertVc =
                        UIAlertController(texts.get(STRING_BUTTON_SEND), message, UIAlertControllerStyle.Alert)
                    alertVc.addAction(UIAlertAction(texts.get(STRING_ZXING_BUTTON_OK), UIAlertActionStyle.Default) {})
                    presentViewController(alertVc, true) {}
                }
            }
        }

        override fun notifyHasSigningPromptData(signingPrompt: String?) {
            TODO("Not yet implemented")
        }
    }

    inner class ProgressViewController : UIViewController() {
        private lateinit var progressIndicator: UIActivityIndicatorView
        override fun viewDidLoad() {
            super.viewDidLoad()
            progressIndicator = UIActivityIndicatorView()
            progressIndicator.activityIndicatorViewStyle = UIActivityIndicatorViewStyle.Large
            view.addSubview(progressIndicator)
            progressIndicator.centerVertical().centerHorizontal()
        }

        override fun viewWillAppear(animated: Boolean) {
            super.viewWillAppear(animated)
            progressIndicator.startAnimating()
        }
    }
}