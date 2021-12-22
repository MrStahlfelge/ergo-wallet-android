package org.ergoplatform.ios.transactions

import com.badlogic.gdx.utils.I18NBundle
import kotlinx.coroutines.CoroutineScope
import org.ergoplatform.*
import org.ergoplatform.ios.tokens.SendTokenEntryView
import org.ergoplatform.ios.ui.*
import org.ergoplatform.transactions.TransactionResult
import org.ergoplatform.uilogic.*
import org.ergoplatform.uilogic.transactions.SendFundsUiLogic
import org.ergoplatform.utils.LogUtils
import org.ergoplatform.utils.formatFiatToString
import org.ergoplatform.wallet.addresses.getAddressLabel
import org.ergoplatform.wallet.getNumOfAddresses
import org.robovm.apple.coregraphics.CGRect
import org.robovm.apple.foundation.NSArray
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
    private lateinit var grossAmountLabel: ErgoAmountView
    private lateinit var tokensUiList: UIStackView
    private lateinit var tokensError: UILabel
    private lateinit var sendButton: UIButton
    private lateinit var addTokenButton: UIButton

    private lateinit var inputReceiver: UITextField
    private lateinit var inputErgoAmount: UITextField

    private var txDoneView: UIView? = null

    override fun viewDidLoad() {
        super.viewDidLoad()

        texts = getAppDelegate().texts
        title = texts.get(STRING_BUTTON_SEND)
        view.backgroundColor = UIColor.systemBackground()
        navigationController.navigationBar?.tintColor = UIColor.label()

        val uiBarButtonItem = UIBarButtonItem(
            getIosSystemImage(IMAGE_QR_SCAN, UIImageSymbolScale.Small),
            UIBarButtonItemStyle.Plain
        )
        uiBarButtonItem.setOnClickListener {
            presentViewController(QrScannerViewController {
                // TODO check cold wallet QR
                val content = parsePaymentRequest(it)
                content?.let {
                    inputReceiver.text = content.address
                    inputReceiver.sendControlEventsActions(UIControlEvents.EditingChanged)
                    content.amount.let { amount ->
                        if (amount.nanoErgs > 0) setInputAmount(amount)
                    }
                    uiLogic.addTokensFromPaymentRequest(content.tokens)
                }
            }, true) {}
        }
        navigationController.topViewController.navigationItem.rightBarButtonItem = uiBarButtonItem

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
            textAlignment = NSTextAlignment.Center
            layer.borderWidth = 1.0
            layer.cornerRadius = 4.0
            layer.borderColor = uiColorErgo.cgColor
            font = UIFont.getSystemFont(FONT_SIZE_BODY1, UIFontWeight.Semibold)

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
            delegate = object : OnlyNumericInputTextFieldDelegate() {
                override fun shouldReturn(textField: UITextField?): Boolean {
                    inputErgoAmount.resignFirstResponder()
                    return true
                }
            }
            addOnEditingChangedListener {
                hasAmountError = false
                uiLogic.amountToSend = text.toErgoAmount() ?: ErgoAmount.ZERO
            }
        }
        addMaxAmountActionToTextField()

        fiatLabel = Body1Label()
        fiatLabel.textAlignment = NSTextAlignment.Right
        fiatLabel.isHidden = true

        feeLabel = Body1Label()
        grossAmountLabel = ErgoAmountView(true, FONT_SIZE_HEADLINE1)
        val grossAmountContainer = UIView()
        grossAmountContainer.layoutMargins = UIEdgeInsets.Zero()
        grossAmountContainer.addSubview(grossAmountLabel)
        grossAmountLabel.topToSuperview().bottomToSuperview().centerHorizontal()

        tokensUiList = UIStackView(CGRect.Zero()).apply {
            axis = UILayoutConstraintAxis.Vertical
            isHidden = true
            layoutMargins = UIEdgeInsets(0.0, DEFAULT_MARGIN, 0.0, DEFAULT_MARGIN)
            isLayoutMarginsRelativeArrangement = true
        }
        tokensError = Body1Label().apply {
            text = texts.get(STRING_ERROR_TOKEN_AMOUNT)
            isHidden = true
            textAlignment = NSTextAlignment.Center
            textColor = uiColorErgo
        }

        sendButton = PrimaryButton(
            texts.get(STRING_BUTTON_SEND),
            getIosSystemImage(IMAGE_SEND, UIImageSymbolScale.Small)
        )
        sendButton.addOnTouchUpInsideListener { _, _ -> startPayment() }

        addTokenButton = CommonButton(
            texts.get(STRING_LABEL_ADD_TOKEN), getIosSystemImage(
                IMAGE_PLUS, UIImageSymbolScale.Small
            )
        )
        addTokenButton.isHidden = true
        addTokenButton.addOnTouchUpInsideListener { _, _ ->
            presentViewController(
                ChooseTokenListViewController(
                    uiLogic.getTokensToChooseFrom()
                ) { tokenToAdd ->
                    tokensUiList.superview.animateLayoutChanges {
                        uiLogic.newTokenChosen(tokenToAdd)
                    }
                }, true
            ) {}
        }

        val buttonContainer = UIView()
        buttonContainer.addSubview(sendButton)
        buttonContainer.addSubview(addTokenButton)
        sendButton.topToSuperview().bottomToSuperview().rightToSuperview().fixedWidth(120.0)
        addTokenButton.centerVerticallyTo(sendButton).leftToSuperview().fixedWidth(120.0)

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
                grossAmountContainer,
                tokensUiList,
                tokensError,
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

    private var hasAmountError = false
        set(hasError) {
            if (field != hasError) {
                field = hasError
                inputErgoAmount.setHasError(hasError)

                // restore the max amount action button when error state is reset
                if (!hasError) {
                    addMaxAmountActionToTextField()
                }
            }
        }

    private fun addMaxAmountActionToTextField() {
        inputErgoAmount.setCustomActionField(
            getIosSystemImage(
                IMAGE_FULL_AMOUNT,
                UIImageSymbolScale.Small
            )!!
        ) { setInputAmount(uiLogic.getMaxPossibleAmountToSend()) }
    }

    private fun setInputAmount(amountToSend: ErgoAmount) {
        inputErgoAmount.text = amountToSend.toStringTrimTrailingZeros()
        inputErgoAmount.sendControlEventsActions(UIControlEvents.EditingChanged)
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        uiLogic.initWallet(getAppDelegate().database, walletId, derivationIdx, paymentRequest)

        inputReceiver.text = uiLogic.receiverAddress
        if (uiLogic.amountToSend.nanoErgs > 0) setInputAmount(uiLogic.amountToSend)
    }

    private fun startPayment() {
        val checkResponse = uiLogic.checkCanMakePayment()

        inputReceiver.setHasError(checkResponse.receiverError)
        hasAmountError = checkResponse.amountError
        if (checkResponse.receiverError) {
            inputReceiver.becomeFirstResponder()
        } else if (checkResponse.amountError) {
            inputErgoAmount.becomeFirstResponder()
        }
        if (checkResponse.tokenError) {
            tokensError.setHiddenAnimated(false)
            setFocusToEmptyTokenAmountInput()
        }

        if (checkResponse.canPay) {
            startAuthFlow(uiLogic.wallet!!.walletConfig) { mnemonic ->
                val appDelegate = getAppDelegate()
                uiLogic.startPaymentWithMnemonicAsync(
                    mnemonic, appDelegate.prefs, IosStringProvider(
                        appDelegate.texts
                    )
                )
            }
        }
    }

    private fun setFocusToEmptyTokenAmountInput() {
        (tokensUiList.arrangedSubviews.firstOrNull { (it as? SendTokenEntryView)?.hasAmount() == false }
                as? SendTokenEntryView)?.setFocus()
    }

    inner class IosSendFundsUiLogic : SendFundsUiLogic() {
        var progressViewController: ProgressViewController? = null

        override val coroutineScope: CoroutineScope
            get() = viewControllerScope

        override fun notifyWalletStateLoaded() {
            runOnMainThread {
                // if we already have a successful tx, there is no need to refresh the ui
                if (txDoneView == null) {
                    walletTitle.text = texts.format(STRING_LABEL_SEND_FROM, wallet!!.walletConfig.displayName)
                    readOnlyHint.isHidden = uiLogic.wallet!!.walletConfig.secretStorage != null
                    sendButton.isEnabled = readOnlyHint.isHidden // TODO cold wallet
                    scrollView.isHidden = false
                }
            }
        }

        override fun notifyDerivedAddressChanged() {
            runOnMainThread {
                addressNameLabel.text = derivedAddress?.getAddressLabel(IosStringProvider(texts))
                    ?: texts.format(STRING_LABEL_ALL_ADDRESSES, wallet?.getNumOfAddresses())
            }
        }

        override fun notifyTokensChosenChanged() {
            runOnMainThread {
                addTokenButton.isHidden = (uiLogic.tokensChosen.size >= uiLogic.tokensAvail.size)
                tokensUiList.clearArrangedSubviews()
                tokensError.isHidden = true
                uiLogic.tokensChosen.forEach {
                    val ergoId = it.key
                    tokensAvail.firstOrNull { it.tokenId.equals(ergoId) }?.let { tokenEntity ->
                        val tokenEntry =
                            SendTokenEntryView(uiLogic, tokensError, tokenEntity, it.value)
                        tokensUiList.addArrangedSubview(tokenEntry)
                    }
                }
                tokensUiList.isHidden = uiLogic.tokensChosen.isEmpty()
                setFocusToEmptyTokenAmountInput()

                uiLogic.getPaymentRequestWarnings(IosStringProvider(texts))?.let {
                    val uac = buildSimpleAlertController("", it, texts)
                    presentViewController(uac, true) {}

                }
            }
        }

        override fun notifyAmountsChanged() {
            runOnMainThread {
                feeLabel.text = texts.format(STRING_DESC_FEE, feeAmount.toStringRoundToDecimals())
                grossAmountLabel.setErgoAmount(grossAmount)
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
                        forceDismissKeyboard()
                        progressViewController = ProgressViewController()
                        progressViewController?.presentModalAbove(this@SendFundsViewController)
                    }
                } else {
                    progressViewController?.dismissViewController(false) {}
                    progressViewController = null
                }
            }
        }

        override fun notifyHasTxId(txId: String) {
            LogUtils.logDebug("SendFunds", "Success, tx id $txId")

            runOnMainThread {
                scrollView.isHidden = true
                val txDoneCardView = CardView()
                view.addSubview(txDoneCardView)
                val texts = getAppDelegate().texts

                val image = UIImageView(getIosSystemImage(IMAGE_TX_DONE, UIImageSymbolScale.Large))
                image.contentMode = UIViewContentMode.ScaleAspectFit
                image.tintColor = uiColorErgo
                image.fixedHeight(100.0)

                val descLabel = Body1Label()
                descLabel.text = texts.get(STRING_DESC_TRANSACTION_SEND)
                descLabel.textAlignment = NSTextAlignment.Center
                val txLabel = Body1BoldLabel()
                txLabel.text = txId
                txLabel.textAlignment = NSTextAlignment.Center
                txLabel.isUserInteractionEnabled = true
                txLabel.addGestureRecognizer(UITapGestureRecognizer {
                    shareText(getExplorerTxUrl(txId), txLabel)
                })

                val doneButton = PrimaryButton(texts.get(STRING_BUTTON_DONE))
                doneButton.addOnTouchUpInsideListener { _, _ -> navigationController.popViewController(true) }
                val doneButtonContainer = UIView()
                doneButtonContainer.addSubview(doneButton)
                doneButton.centerHorizontal().topToSuperview().bottomToSuperview().fixedWidth(150.0)

                val txDoneStack = UIStackView(NSArray(image, descLabel, txLabel, doneButtonContainer))
                txDoneStack.axis = UILayoutConstraintAxis.Vertical
                txDoneStack.spacing = DEFAULT_MARGIN * 3

                txDoneCardView.contentView.addSubview(txDoneStack)
                txDoneStack.edgesToSuperview(inset = DEFAULT_MARGIN * 2)

                txDoneCardView.centerVertical().widthMatchesSuperview(inset = DEFAULT_MARGIN * 2, maxWidth = MAX_WIDTH)
                txDoneView = txDoneCardView
            }
        }

        override fun notifyHasErgoTxResult(txResult: TransactionResult) {
            if (!txResult.success) {
                runOnMainThread {
                    val message =
                        texts.get(STRING_ERROR_SEND_TRANSACTION) + (txResult.errorMsg?.let { "\n\n$it" } ?: "")
                    val alertVc =
                        UIAlertController(
                            texts.get(STRING_BUTTON_SEND),
                            message,
                            UIAlertControllerStyle.Alert
                        )
                    alertVc.addAction(
                        UIAlertAction(
                            texts.get(STRING_ZXING_BUTTON_OK),
                            UIAlertActionStyle.Default
                        ) {})
                    presentViewController(alertVc, true) {}
                }
            }
        }

        override fun notifyHasSigningPromptData(signingPrompt: String?) {
            TODO("Cold wallet not yet implemented")
        }
    }
}