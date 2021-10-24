package org.ergoplatform.ios.wallet

import org.ergoplatform.ios.ui.*
import org.robovm.apple.coregraphics.CGRect
import org.robovm.apple.foundation.NSRange
import org.robovm.apple.uikit.*

class RestoreWalletViewController : ViewControllerWithKeyboardLayoutGuide() {
    override fun viewDidLoad() {
        super.viewDidLoad()

        val texts = getAppDelegate().texts
        title = texts.get(STRING_LABEL_RESTORE_WALLET)
        view.backgroundColor = UIColor.systemBackground()

        if (navigationController.viewControllers.size == 1) {
            val cancelButton = UIBarButtonItem(UIBarButtonSystemItem.Cancel)
            cancelButton.setOnClickListener { this.dismissViewController(true) {} }
            navigationItem.leftBarButtonItem = cancelButton
        }

        navigationController.navigationBar?.tintColor = uiColorErgo

        val nextButton = UIBarButtonItem(UIBarButtonSystemItem.Done)
        navigationItem.rightBarButtonItem = nextButton
        nextButton.setOnClickListener { goToNextPage() }

        val descLabel = Body1Label()
        descLabel.text = texts.get(STRING_DESC_RESTORE_WALLET)
        val textView = UITextView(CGRect.Zero())
        textView.layer.borderWidth = 1.0
        textView.layer.borderColor = UIColor.systemGray().cgColor
        textView.textContentType = UITextContentType.OneTimeCode
        textView.isSecureTextEntry = true
        textView.returnKeyType = UIReturnKeyType.Done

        val errorLabel = Body1Label()
        errorLabel.textColor = UIColor.red()

        textView.setDelegate(object : UITextViewDelegateAdapter() {
            override fun shouldChangeCharacters(textView: UITextView?, range: NSRange?, text: String?): Boolean {
                if (text == "\n") {
                    goToNextPage()
                }
                return true
            }
        })

        container.addSubviews(listOf(descLabel, textView, errorLabel))

        descLabel.widthMatchesSuperview(false, DEFAULT_MARGIN).topToSuperview(false, DEFAULT_MARGIN)
        textView.widthMatchesSuperview(false, DEFAULT_MARGIN).topToBottomOf(descLabel, DEFAULT_MARGIN)
        errorLabel.widthMatchesSuperview().topToBottomOf(textView).bottomToKeyboard(this, DEFAULT_MARGIN)

    }

    private fun goToNextPage() {
        // TODO check logic
        navigationController.pushViewController(SaveWalletViewController(), true)
    }
}