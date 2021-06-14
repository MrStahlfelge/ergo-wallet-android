package org.ergoplatform.android.transactions

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ergoplatform.android.*
import org.ergoplatform.android.ui.PasswordDialogFragment
import org.ergoplatform.android.ui.SingleLiveEvent
import org.ergoplatform.android.wallet.ENC_TYPE_PASSWORD
import org.ergoplatform.android.wallet.WalletConfigDbEntity
import org.ergoplatform.api.AesEncryptionManager
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.Parameters

class SendFundsViewModel : ViewModel() {
    var wallet: WalletConfigDbEntity? = null
        private set

    var receiverAddress: String = ""
        set(value) {
            field = value
            calcGrossAmount()
        }
    var amountToSend: Float = 0f
        set(value) {
            field = value
            calcGrossAmount()
        }

    private val _lockInterface = MutableLiveData<Boolean>()
    val lockInterface: LiveData<Boolean> = _lockInterface
    private val _walletName = MutableLiveData<String>()
    val walletName: LiveData<String> = _walletName
    private val _feeAmount = MutableLiveData<Float>().apply {
        value = nanoErgsToErgs(Parameters.MinFee)
    }
    val feeAmount: LiveData<Float> = _feeAmount
    private val _grossAmount = MutableLiveData<Float>().apply {
        value = 0f
    }
    val grossAmount: LiveData<Float> = _grossAmount
    private val _paymentDoneLiveData = SingleLiveEvent<PaymentResult>()
    val paymentDoneLiveData: LiveData<PaymentResult> = _paymentDoneLiveData
    private val _txId = MutableLiveData<String>()
    val txId: LiveData<String> = _txId


    fun initWallet(ctx: Context, walletId: Int) {
        viewModelScope.launch {
            wallet =
                AppDatabase.getInstance(ctx).walletDao().loadWalletById(walletId)

            wallet?.displayName?.let {
                _walletName.postValue(it)
            }
        }
        calcGrossAmount()
    }

    private fun calcGrossAmount() {
        _grossAmount.postValue(feeAmount.value!! + amountToSend)
    }

    fun checkReceiverAddress(): Boolean {
        return isValidErgoAddress(receiverAddress)
    }

    fun checkAmount(): Boolean {
        return amountToSend >= nanoErgsToErgs(Parameters.MinChangeValue)
    }

    fun preparePayment(fragment: SendFundsFragmentDialog) {
        if (wallet?.encryptionType == ENC_TYPE_PASSWORD) {
            PasswordDialogFragment().show(
                fragment.childFragmentManager,
                null
            )
        }
    }

    fun startPaymentWithPassword(password: String): Boolean {
        wallet?.secretStorage?.let {
            val mnemonic: String?
            try {
                val decryptData = AesEncryptionManager.decryptData(password, it)
                mnemonic = deserializeSecrets(String(decryptData!!))
            } catch (t: Throwable) {
                // Password wrong
                return false
            }

            if (mnemonic == null) {
                // deserialization error, corrupted db data
                return false
            }

            viewModelScope.launch {
                val ergoTxId: String?
                withContext(Dispatchers.IO) {
                    ergoTxId = sendErgoTx(
                        Address.create(receiverAddress), ergsToNanoErgs(amountToSend),
                        mnemonic, ""
                    )
                }
                _lockInterface.postValue(false)
                val success = ergoTxId != null && ergoTxId.isNotEmpty()
                if (success) {
                    NodeConnector.getInstance().invalidateCache()
                    _txId.postValue(ergoTxId!!)
                }
                _paymentDoneLiveData.postValue(if (success) PaymentResult.SUCCESS else PaymentResult.ERROR)
            }

            _lockInterface.postValue(true)

            return true
        }

        return false
    }
}

enum class PaymentResult {
    NONE, SUCCESS, ERROR
}