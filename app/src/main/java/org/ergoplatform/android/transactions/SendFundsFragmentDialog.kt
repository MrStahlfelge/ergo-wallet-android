package org.ergoplatform.android.transactions

import StageConstants
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.integration.android.IntentIntegrator
import org.ergoplatform.android.R
import org.ergoplatform.android.databinding.FragmentSendFundsBinding
import org.ergoplatform.android.formatErgsToString
import org.ergoplatform.android.parseContentFromQrCode
import org.ergoplatform.android.ui.FullScreenFragmentDialog
import org.ergoplatform.android.ui.PasswordDialogCallback
import org.ergoplatform.android.ui.hideForcedSoftKeyboard
import org.ergoplatform.android.ui.inputTextToFloat

/**
 * Here's the place to send transactions
 */
class SendFundsFragmentDialog : FullScreenFragmentDialog(), PasswordDialogCallback {
    private var _binding: FragmentSendFundsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: SendFundsViewModel
    private val args: SendFundsFragmentDialogArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        viewModel =
            ViewModelProvider(this).get(SendFundsViewModel::class.java)

        // Inflate the layout for this fragment
        _binding = FragmentSendFundsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.initWallet(requireContext(), args.walletId)

        viewModel.walletName.observe(viewLifecycleOwner, {
            binding.walletName.text = getString(R.string.label_send_from, it)
        })
        viewModel.walletBalance.observe(viewLifecycleOwner, {
            binding.tvBalance.text = getString(
                    R.string.label_wallet_balance,
                    formatErgsToString(
                        it,
                        requireContext()
                    )
                )
        })
        viewModel.feeAmount.observe(viewLifecycleOwner, {
            binding.tvFee.text = getString(
                R.string.desc_fee,
                formatErgsToString(
                    it,
                    requireContext()
                )

            )
        })
        viewModel.grossAmount.observe(viewLifecycleOwner, { binding.grossAmount.amount = it })
        viewModel.lockInterface.observe(viewLifecycleOwner, {
            binding.lockProgress.visibility = if (it) View.VISIBLE else View.GONE
            dialog?.setCancelable(!it)
        })
        viewModel.paymentDoneLiveData.observe(viewLifecycleOwner, {
            if (!it.success) {
                val snackbar = Snackbar.make(
                    requireView(),
                    R.string.error_transaction,
                    Snackbar.LENGTH_LONG
                )
                it.errorMsg?.let { errorMsg ->
                    snackbar.setAction(
                        R.string.label_details
                    ) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setMessage(errorMsg)
                            .setPositiveButton(R.string.button_copy) { p, p1 ->
                                val clipboard = ContextCompat.getSystemService(
                                    requireContext(),
                                    ClipboardManager::class.java
                                )
                                val clip = ClipData.newPlainText("", errorMsg)
                                clipboard?.setPrimaryClip(clip)
                            }
                            .setNegativeButton(R.string.label_dismiss, null)
                            .show();
                    }
                }
                snackbar.show()
            }
        })
        viewModel.txId.observe(viewLifecycleOwner, {
            binding.cardviewTxEdit.visibility = View.GONE
            binding.cardviewTxDone.visibility = View.VISIBLE
            binding.labelTxId.text = it
        })
        binding.buttonShareTx.setOnClickListener {
            val txUrl =
                StageConstants.EXPLORER_WEB_ADDRESS + "en/transactions/" + binding.labelTxId.text.toString()
            val sendIntent: Intent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, txUrl)
                type = "text/plain"
            }

            val shareIntent = Intent.createChooser(sendIntent, null)
            startActivity(shareIntent)
        }
        binding.buttonDismiss.setOnClickListener { dismiss() }

        binding.buttonSend.setOnClickListener {
            startPayment()
        }

        binding.buttonScan.setOnClickListener {
            IntentIntegrator.forSupportFragment(this).initiateScan(setOf(IntentIntegrator.QR_CODE))
        }

        binding.tvReceiver.editText?.setText(viewModel.receiverAddress)
        if (viewModel.amountToSend > 0) {
            setAmountEdittext(viewModel.amountToSend)
        }

        binding.amount.editText?.addTextChangedListener(MyTextWatcher(binding.amount))
        binding.tvReceiver.editText?.addTextChangedListener(MyTextWatcher(binding.tvReceiver))
    }

    private fun setAmountEdittext(amountToSend: Float) {
        binding.amount.editText?.setText(
            formatErgsToString(
                amountToSend,
                requireContext()
            )
        )
    }

    private fun startPayment() {
        if (!viewModel.checkReceiverAddress()) {
            binding.tvReceiver.error = getString(R.string.error_receiver_address)
            binding.tvReceiver.editText?.requestFocus()
        } else if (!viewModel.checkAmount()) {
            binding.amount.error = getString(R.string.error_amount)
            binding.amount.editText?.requestFocus()
        } else {
            viewModel.preparePayment(this)
        }
    }

    override fun onPasswordEntered(password: String?): String? {
        password?.let {
            val success = viewModel.startPaymentWithPassword(password)
            if (!success) {
                return getString(R.string.error_password_wrong)
            } else
            // okay, transaction is started. ViewModel will handle waiting dialog for us
                return null
        }
        return getString(R.string.error_password_empty)
    }

    fun showBiometricPrompt() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.title_authenticate))
            .setConfirmationRequired(true) // don't send funds immediately when face is recognized
            .setDeviceCredentialAllowed(true)
            .build()

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                try {
                    viewModel.startPaymentUserAuth()
                } catch (t: Throwable) {
                    hideForcedSoftKeyboard(requireContext(), binding.amount.editText!!)
                    Snackbar.make(
                        requireView(),
                        getString(R.string.error_device_security, t.message),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                hideForcedSoftKeyboard(requireContext(), binding.amount.editText!!)
                Snackbar.make(
                    requireView(),
                    getString(R.string.error_device_security, errString),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        BiometricPrompt(this, callback).authenticate(promptInfo)
    }

    private fun inputChangesToViewModel() {
        viewModel.receiverAddress = binding.tvReceiver.editText?.text?.toString() ?: ""

        val amountStr = binding.amount.editText?.text.toString()
        viewModel.amountToSend = inputTextToFloat(amountStr)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            result.contents?.let {
                val content = parseContentFromQrCode(it)
                content?.let { binding.tvReceiver.editText?.setText(content.address) }
                content?.amount?.let { amount -> if (amount > 0) setAmountEdittext(amount) }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    inner class MyTextWatcher(private val textInputLayout: TextInputLayout) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {

        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

        }

        override fun afterTextChanged(s: Editable?) {
            textInputLayout.error = null
            inputChangesToViewModel()
        }

    }
}