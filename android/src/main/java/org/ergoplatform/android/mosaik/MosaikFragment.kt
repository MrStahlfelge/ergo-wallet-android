package org.ergoplatform.android.mosaik

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ergoplatform.ApiServiceManager
import org.ergoplatform.android.AppDatabase
import org.ergoplatform.android.Preferences
import org.ergoplatform.android.R
import org.ergoplatform.android.databinding.FragmentMosaikBinding
import org.ergoplatform.android.ergoauth.ErgoAuthenticationFragment
import org.ergoplatform.android.persistence.AndroidCacheFiles
import org.ergoplatform.android.transactions.ErgoPaySigningFragment
import org.ergoplatform.android.ui.*
import org.ergoplatform.android.wallet.ChooseWalletListBottomSheetDialog
import org.ergoplatform.android.wallet.WalletChooserCallback
import org.ergoplatform.android.wallet.addresses.AddressChooserCallback
import org.ergoplatform.android.wallet.addresses.ChooseAddressListDialogFragment
import org.ergoplatform.compose.tokens.getAppMosaikTokenLabelBuilder
import org.ergoplatform.mosaik.MosaikComposeConfig
import org.ergoplatform.mosaik.MosaikViewTree
import org.ergoplatform.mosaik.model.MosaikContext
import org.ergoplatform.persistance.WalletConfig
import org.ergoplatform.wallet.getNumOfAddresses

class MosaikFragment : Fragment(), WalletChooserCallback, AddressChooserCallback {
    private var _binding: FragmentMosaikBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MosaikViewModel by viewModels()
    private val args by navArgs<MosaikFragmentArgs>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        parentFragmentManager.setFragmentResultListener(
            ErgoAuthenticationFragment.ergoAuthActionRequestKey,
            this
        ) { _, bundle ->
            if (bundle.getBoolean(
                    ErgoAuthenticationFragment.ergoAuthActionCompletedBundleKey,
                    false
                )
            ) {
                viewModel.ergoAuthActionCompleted()
            }
        }
        parentFragmentManager.setFragmentResultListener(
            ErgoPaySigningFragment.ergoPayActionRequestKey,
            this
        ) { _, bundle ->
            if (bundle.getBoolean(ErgoPaySigningFragment.ergoPayActionCompletedBundleKey, false)) {
                viewModel.ergoPayActionCompleted()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMosaikBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fragmentTitle.text = args.title ?: args.url

        // Observe events and live data
        viewModel.browserEvent.observe(viewLifecycleOwner) { url ->
            url?.let { openUrlWithBrowser(requireContext(), url) }
        }
        viewModel.pasteToClipboardEvent.observe(viewLifecycleOwner) { text ->
            text?.let { copyStringToClipboard(text, requireContext(), binding.root) }
        }
        viewModel.showDialogEvent.observe(viewLifecycleOwner) { dialog ->
            hideForcedSoftKeyboard(requireContext(), binding.composeView)
            dialog?.let {
                val builder = MaterialAlertDialogBuilder(requireContext())
                    .setMessage(dialog.message)
                    .setPositiveButton(dialog.positiveButtonText) { _, _ ->
                        dialog.positiveButtonClicked?.invoke()
                    }

                dialog.negativeButtonText?.let {
                    builder.setNegativeButton(dialog.negativeButtonText) { _, _ ->
                        dialog.negativeButtonClicked?.invoke()
                    }
                }

                builder.show()

            }
        }
        viewModel.manifestLiveData.observe(viewLifecycleOwner) { manifest ->
            binding.fragmentTitle.text = manifest?.appName
            requireActivity().invalidateOptionsMenu()
            hideForcedSoftKeyboard(requireContext(), binding.composeView)

            backPressedHandler.isEnabled = viewModel.mosaikRuntime.canNavigateBack()
        }
        viewModel.noAppLiveData.observe(viewLifecycleOwner) { errorCause ->
            binding.layoutNoApp.visibility = if (errorCause == null) View.GONE else View.VISIBLE
            binding.composeView.visibility = if (errorCause == null) View.VISIBLE else View.GONE
            errorCause?.let {
                binding.textNoApp.text = viewModel.mosaikRuntime.getUserErrorMessage(errorCause)
            }
        }
        viewModel.showWalletOrAddressChooserEvent.observe(viewLifecycleOwner) { valueId ->
            valueId?.let {
                ChooseWalletListBottomSheetDialog().show(childFragmentManager, null)
            }
        }
        viewModel.ergoPayActionEvent.observe(viewLifecycleOwner) { ergoPayAction ->
            ergoPayAction?.let {
                findNavController().navigateSafe(
                    MosaikFragmentDirections.actionMosaikFragmentToErgoPaySigningFragment(
                        ergoPayAction.url
                    )
                )
            }
        }
        viewModel.ergoAuthActionEvent.observe(viewLifecycleOwner) { ergoAuthAction ->
            ergoAuthAction?.let {
                findNavController().navigateSafe(
                    MosaikFragmentDirections.actionMosaikFragmentToErgoAuthenticationFragment(
                        ergoAuthAction.url
                    )
                )
            }
        }
        viewModel.scanQrCodeEvent.observe(viewLifecycleOwner) { qrScanActionId ->
            qrScanActionId?.let {
                IntentIntegrator.forSupportFragment(this)
                    .initiateScan(setOf(IntentIntegrator.QR_CODE))
            }
        }
        viewModel.showTokenInfoEvent.observe(viewLifecycleOwner) { tokenId ->
            tokenId?.let {
                findNavController().navigateSafe(
                    MosaikFragmentDirections.actionMosaikFragmentToTokenInformationDialogFragment(
                        tokenId
                    )
                )
            }
        }

        // click listeners
        binding.buttonNoApp.setOnClickListener {
            viewModel.retryLoading(args.url)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.mosaikRuntime.viewTree.uiLockedState.collect { locked ->
                if (locked && _binding != null)
                    hideForcedSoftKeyboard(requireContext(), binding.composeView)
            }
        }

        // set up Compose view
        MosaikComposeConfig.apply {
            val dpToPx = binding.root.resources.displayMetrics.density
            val minPixelSize = (300 * dpToPx).toInt()
            scrollMinAlpha = 0f
            convertByteArrayToImageBitmap = { byteArray ->
                decodeSampledBitmapFromByteArray(
                    byteArray,
                    minPixelSize,
                    minPixelSize
                ).asImageBitmap()
            }
            qrCodeSize = 250.dp
            val qrSizePx = (250 * dpToPx).toInt()
            convertQrCodeContentToImageBitmap =
                { convertQrCodeToBitmap(it, qrSizePx, qrSizePx)?.asImageBitmap() }
            preselectEditableInputs = false

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
                tokenDb = { AppDatabase.getInstance(requireContext()).tokenDbProvider },
                apiService = { ApiServiceManager.getOrInit(Preferences(requireContext())) },
                stringResolver = { AndroidStringProvider(requireContext()) }
            )
        }
        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            AppComposeTheme {
                MosaikViewTree(viewModel.mosaikRuntime.viewTree)
            }
        }

        // initialize our view
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedHandler
        )

        val context = requireContext()
        viewModel.initialize(
            args.url,
            AppDatabase.getInstance(context),
            getPlatformType(),
            AndroidStringProvider(context),
            Preferences(context),
            AndroidCacheFiles(context)
        )
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.fragment_mosaik_app, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.menu_switch_favorite).apply {
            setIcon(
                if (viewModel.mosaikRuntime.isFavoriteApp) R.drawable.ic_favorite_24
                else R.drawable.ic_favorite_no_24
            )
            isEnabled = viewModel.mosaikRuntime.appUrl != null
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.mosaikRuntime.checkViewTreeValidity()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_switch_favorite) {
            viewModel.mosaikRuntime.switchFavorite()
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onWalletChosen(walletConfig: WalletConfig) {
        // ok, let's see if we have multiple addresses
        viewLifecycleOwner.lifecycleScope.launch {
            val wallet = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(requireContext())
                    .walletDbProvider.loadWalletWithStateById(walletConfig.id)
            }
            val done = viewModel.onWalletChosen(wallet)
            if (!done) {
                // we need to choose an address
                if (wallet?.getNumOfAddresses() == 1) {
                    viewModel.onAddressChosen(0)
                } else {
                    ChooseAddressListDialogFragment.newInstance(walletConfig.id)
                        .show(childFragmentManager, null)
                }
            }
        }
    }

    override fun onAddressChosen(addressDerivationIdx: Int?) {
        viewModel.onAddressChosen(addressDerivationIdx!!)
    }

    private fun getPlatformType(): MosaikContext.Platform {
        // Chrome OS
        return if (requireContext().packageManager.hasSystemFeature("org.chromium.arc.device_management"))
            MosaikContext.Platform.DESKTOP
        else if (resources.getBoolean(R.bool.isTablet))
            MosaikContext.Platform.TABLET
        else
            MosaikContext.Platform.PHONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // TokenLabel references this fragment (requireContext Call), so we set it as a DisposableEffect that is cleaned up
        MosaikComposeConfig.TokenLabel = { properties, modifier, content -> }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            result.contents?.let { viewModel.qrCodeScanned(it) }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private val backPressedHandler = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.mosaikRuntime.navigateBack()
            isEnabled = viewModel.mosaikRuntime.canNavigateBack()
        }

    }
}