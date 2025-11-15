package com.indrek.slopper.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.indrek.slopper.C.PREFS_NAME
import com.indrek.slopper.R
import com.indrek.slopper.data.DownloadStatus
import com.indrek.slopper.data.ModelDownloadManager
import com.indrek.slopper.data.SessionStateRepository
import com.indrek.slopper.databinding.ActivitySlopperBinding
import com.indrek.slopper.domain.model.Model
import com.indrek.slopper.domain.model.ModelDelivery
import com.indrek.slopper.service.FloatingToolService
import com.indrek.slopper.service.ScreenCaptureService
import com.revenuecat.purchases.ui.revenuecatui.ExperimentalPreviewRevenueCatUIPurchasesAPI
import com.revenuecat.purchases.ui.revenuecatui.activity.PaywallActivityLauncher
import com.revenuecat.purchases.ui.revenuecatui.activity.PaywallResult
import com.revenuecat.purchases.ui.revenuecatui.activity.PaywallResultHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@OptIn(ExperimentalPreviewRevenueCatUIPurchasesAPI::class)
@AndroidEntryPoint
class SlopperActivity : AppCompatActivity(), PaywallResultHandler {

    @Inject
    lateinit var sessionStateRepo: SessionStateRepository

    private lateinit var paywallActivityLauncher: PaywallActivityLauncher
    private lateinit var root: View
    private lateinit var binding: ActivitySlopperBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlayPermissionLauncherForSession: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncherForButton: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncherOnCreate: ActivityResultLauncher<String>

    private var isCheckingPermissions = false

    private val viewModel: SlopperActivityViewModel by viewModels()

    companion object {
        const val TAG = "SlopperActivity"
        const val KEY_SERVICES_ENABLED = "services_enabled"
        const val KEY_SELECTED_MODEL = "selected_model"
        const val PREF_KEY_LICENSE_ACCEPTED_PREFIX = "license_accepted_"
        const val KEY_DAILY_LIMIT_COUNT = "daily_limit_count"
        const val KEY_DAILY_LIMIT_DAY = "daily_limit_day"
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        setTheme(R.style.Base_Theme_Slopper)


        binding = ActivitySlopperBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paywallActivityLauncher = PaywallActivityLauncher(this, this)

        // FIXED: Changed from LiveData observe to StateFlow collect
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelDownloadManager.modelStates.collect { states ->
                    updateModelListUI(states)
                    checkFastFollowStatus(states)
                }
            }
        }

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        notificationPermissionLauncherOnCreate = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Notification permission granted via on-create request.")
            } else {
                Log.w(TAG, "Notification permission denied via on-create request.")
            }
        }
        requestNotificationPermissionOnCreate()

        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isCheckingPermissions) return@registerForActivityResult
            if (isGranted) {
                Log.d(TAG, "Notification permission granted.")
                checkAndRequestScreenCapture()
            } else {
                Log.w(TAG, "Notification permission denied.")
                Toast.makeText(this, "Notification permission denied. Needed for capture session.", Toast.LENGTH_LONG).show()
                disableSession()
            }
        }

        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Log.d(TAG, "Screen capture permission granted.")
                startScreenCaptureService(result.resultCode, result.data!!)
            } else {
                Log.w(TAG, "Screen capture permission denied.")
                isCheckingPermissions = false
                Toast.makeText(this@SlopperActivity, "Screen capture permission denied. Needed for session.", Toast.LENGTH_LONG).show()
                disableSession()
            }
        }

        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // UI will auto-update via onResume -> viewModel.refreshAllData()
        }

        notificationPermissionLauncherForButton = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Notification permission granted via button.")
            } else {
                Log.w(TAG, "Notification permission denied via button.")
                Toast.makeText(this, "Notification permission is required on this device.", Toast.LENGTH_SHORT).show()
            }
        }

        overlayPermissionLauncherForSession = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (!isCheckingPermissions) return@registerForActivityResult
            if (Settings.canDrawOverlays(this)) {
                Log.d(TAG, "Overlay permission granted during session setup.")
                checkAndRequestScreenCapture()
            } else {
                Log.w(TAG, "Overlay permission denied during session setup.")
                Toast.makeText(this, "Draw over other apps permission denied. Required for floating tool.", Toast.LENGTH_LONG).show()
                disableSession()
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            Log.d(TAG, "Grant Overlay button clicked.")
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
                overlayPermissionLauncher.launch(intent)
            }
        }

        binding.btnGrantNotification.setOnClickListener {
            Log.d(TAG, "Grant Notification button clicked.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open notification settings", e)
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:$packageName".toUri()
                        }
                        startActivity(intent)
                        Toast.makeText(this, "Please enable notifications for Slopper.", Toast.LENGTH_LONG).show()
                    } catch (e2: Exception) {
                        Log.e(TAG, "Failed to open any app settings", e2)
                        Toast.makeText(this, "Could not open settings. Please enable manually.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Log.d(TAG, "Notification button clicked on pre-TIRAMISU device.")
            }
        }

        binding.swEnableSession.setOnCheckedChangeListener { _, isChecked ->
            if (isCheckingPermissions) {
                Log.d(TAG, "Session Switch toggle ignored, currently checking permissions.")
                return@setOnCheckedChangeListener
            }
            Log.d(TAG, "Session Switch toggled by user: isChecked=$isChecked")
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "Session switch ON ignored, Overlay permission missing.")
                    Toast.makeText(this, "Please grant 'Draw Over Apps' permission first (Step 1).", Toast.LENGTH_LONG).show()
                    binding.swEnableSession.isChecked = false
                    return@setOnCheckedChangeListener
                }

                val savedModelName = prefs.getString(KEY_SELECTED_MODEL, Model.GEMMA3_1B_IT.name)
                val model = try { Model.valueOf(savedModelName ?: Model.GEMMA3_1B_IT.name) } catch (e: Exception) { Model.GEMMA3_1B_IT }

                val licenseAcceptedKey = PREF_KEY_LICENSE_ACCEPTED_PREFIX + model.name
                val isLicenseAccepted = prefs.getBoolean(licenseAcceptedKey, false)

                val startSessionAction = {
                    Log.d(TAG, "License accepted, proceeding to start session.")
                    isCheckingPermissions = true
                    sessionStateRepo.setSessionEnabled(true)
                    checkNotificationPermission()
                }

                if (isLicenseAccepted) {
                    startSessionAction()
                } else {
                    Log.d(TAG, "License for ${model.name} not accepted. Showing dialog.")
                    binding.swEnableSession.isChecked = false
                    showLicenseAgreementDialog(model) {
                        binding.swEnableSession.isChecked = true
                    }
                }
            } else {
                sessionStateRepo.setSessionEnabled(false)
                stopFloatingToolService()
                stopScreenCaptureService()
            }
        }

        binding.btnEditPrompts.setOnClickListener {
            startActivity(Intent(this, PromptEditorActivity::class.java))
        }

        binding.cardPaywall.setOnClickListener {
            launchPaywallActivity()
        }

        // Observe the final, combined UI state
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUiStates(state)
                }
            }
        }

        // FIXED: Changed from LiveData observe to StateFlow collect
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isTrustedTimeInitialized.collect { isInitialized ->
                    if (isInitialized) {
                        Log.d(TAG, "TrustedTime client is ready, telling ViewModel.")
                        viewModel.onTrustedTimeReady()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume called, refreshing data.")
        ModelDownloadManager.refreshStates()
        isCheckingPermissions = false
        viewModel.refreshAllData()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun requestNotificationPermissionOnCreate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting Notification permission on create.")
                notificationPermissionLauncherOnCreate.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "Notification permission was already granted.")
            }
        }
    }

    private fun disableSession() {
        Log.w(TAG, "Disabling session.")
        isCheckingPermissions = false
        sessionStateRepo.setSessionEnabled(false)
        stopFloatingToolService()
        stopScreenCaptureService()
        binding.swEnableSession.post {
            binding.swEnableSession.isChecked = false
        }
    }

    private fun updateUiStates(state: SlopperUiState) {
        // --- Premium & Limit UI (from ViewModel) ---
        if (state.isPremium) {
            //binding.tvDailyLimitStatus.visibility = View.GONE

            binding.tvDailyLimitStatus.text = "Daily limit: UNLIMITED"
            binding.tvDailyLimitStatus.visibility = View.VISIBLE

            updatePaywallCardForPremium()
        } else {
            if (!state.limitData.isReady) {
                binding.tvDailyLimitStatus.text = "Checking daily limit..."
            } else {
                binding.tvDailyLimitStatus.text = "Daily limit: ${state.limitData.remaining} / ${state.limitData.total}"
            }
            binding.tvDailyLimitStatus.visibility = View.VISIBLE

            resetPaywallCardForFree()
        }

        // --- Permission UI (from ViewModel state) ---
        val hasOverlay = state.hasOverlayPermission
        val hasNotification = state.hasNotificationPermission
        val hasAllPermissions = hasOverlay && hasNotification

        Log.d(TAG, "updateUiStates: HasOverlay=$hasOverlay, HasNotification=$hasNotification")

        binding.tvOverlayStatus.text = if (hasOverlay) "Status: Granted" else "Status: Not Granted"
        binding.btnGrantOverlay.isEnabled = !hasOverlay
        binding.btnGrantOverlay.text = if (hasOverlay) "Permission Granted" else "Grant Overlay Permission"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            binding.notificationPermissionLayout.visibility = View.VISIBLE
            binding.permissionDivider.visibility = View.VISIBLE
            binding.tvNotificationStatus.text = if (hasNotification) "Status: Granted" else "Status: Not Granted"
            binding.btnGrantNotification.isEnabled = !hasNotification
            binding.btnGrantNotification.text = if (hasNotification) "Permission Granted" else "Grant Notification Permission"
        } else {
            binding.notificationPermissionLayout.visibility = View.GONE
            binding.permissionDivider.visibility = View.GONE
        }

        if (hasAllPermissions) {
            binding.ivPermissionCheck.setImageResource(R.drawable.outline_check_circle_24)
            binding.ivPermissionCheck.imageTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
        } else {
            binding.ivPermissionCheck.setImageResource(R.drawable.outline_radio_button_partial_24)
            binding.ivPermissionCheck.imageTintList = ContextCompat.getColorStateList(this, R.color.colorError)
        }

        // --- Service & Session UI (from ViewModel) ---
        val isCaptureRunning = state.isCaptureServiceRunning
        val isToolRunning = state.isFloatingToolRunning

        checkFastFollowStatus(ModelDownloadManager.modelStates.value)

        val isSessionFullyRunning = isCaptureRunning && isToolRunning
        if (isSessionFullyRunning) {
            binding.ivSessionCheck.setImageResource(R.drawable.outline_check_circle_24)
            binding.ivSessionCheck.imageTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
        } else {
            binding.ivSessionCheck.setImageResource(R.drawable.outline_pause_circle_24)
            binding.ivSessionCheck.imageTintList = ContextCompat.getColorStateList(this, R.color.colorError)
        }

        // --- Switch Logic (from ViewModel state) ---
        val isSessionEnabledByUser = state.isSessionEnabledByUser

        binding.swEnableSession.setOnCheckedChangeListener(null) // Prevent loop
        val shouldSwitchBeOn = isSessionEnabledByUser && hasAllPermissions && isCaptureRunning
        if (binding.swEnableSession.isChecked != shouldSwitchBeOn) {
            Log.d(TAG, "Session Switch state updated via StateFlow to: $shouldSwitchBeOn")
            binding.swEnableSession.isChecked = shouldSwitchBeOn
        }

        binding.swEnableSession.setOnCheckedChangeListener { _, isChecked ->
            if (isCheckingPermissions) {
                Log.d(TAG, "Session Switch toggle ignored (from UI update), currently checking permissions.")
                return@setOnCheckedChangeListener
            }
            Log.d(TAG, "Session Switch toggled from UI update listener: isChecked=$isChecked")
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "Session switch ON ignored (from UI update), Overlay permission missing.")
                    binding.swEnableSession.isChecked = false
                    return@setOnCheckedChangeListener
                }

                val savedModelName = prefs.getString(KEY_SELECTED_MODEL, Model.GEMMA3_1B_IT.name)
                val model = try { Model.valueOf(savedModelName ?: Model.GEMMA3_1B_IT.name) } catch (e: Exception) { Model.GEMMA3_1B_IT }

                val licenseAcceptedKey = PREF_KEY_LICENSE_ACCEPTED_PREFIX + model.name
                val isLicenseAccepted = prefs.getBoolean(licenseAcceptedKey, false)

                val startSessionAction = {
                    Log.d(TAG, "License accepted, proceeding to start session (from UI update).")
                    isCheckingPermissions = true
                    sessionStateRepo.setSessionEnabled(true)
                    checkNotificationPermission()
                }

                if (isLicenseAccepted) {
                    startSessionAction()
                } else {
                    Log.d(TAG, "License for ${model.name} not accepted. Showing dialog (from UI update).")
                    binding.swEnableSession.isChecked = false

                    showLicenseAgreementDialog(model) {
                        binding.swEnableSession.isChecked = true
                    }
                }
            } else {
                sessionStateRepo.setSessionEnabled(false)
                stopFloatingToolService()
                stopScreenCaptureService()
            }
        }
    }

    private fun checkNotificationPermission() {
        Log.d(TAG, "Session Step 1: Checking Notification permission...")
        isCheckingPermissions = true
        if (!sessionStateRepo.isSessionEnabled()) {
            Log.w(TAG, "checkNotificationPermission: Aborting (User intent disabled).")
            isCheckingPermissions = false
            disableSession()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Requesting Notification permission.")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Log.d(TAG, "Notification permission already granted.")
                checkAndRequestScreenCapture()
            }
        } else {
            Log.d(TAG, "Skipping Notification permission check (API < 33).")
            checkAndRequestScreenCapture()
        }
    }

    private fun checkAndRequestScreenCapture() {
        Log.d(TAG, "Session Step 2: Checking Screen Capture requirement...")
        if (!sessionStateRepo.isSessionEnabled() || !isCheckingPermissions) {
            Log.w(TAG, "checkAndRequestScreenCapture: Aborting (User intent disabled or check cancelled).")
            isCheckingPermissions = false
            disableSession()
            return
        }

        // We read from the ViewModel state now, but checking the prefs is also fine for user intent
        if (viewModel.uiState.value.isCaptureServiceRunning != true) {
            Log.d(TAG, "Requesting Screen Capture permission.")
            val screenCaptureIntent = mediaProjectionManager.createScreenCaptureIntent()
            screenCaptureLauncher.launch(screenCaptureIntent)
        } else {
            Log.d(TAG, "Screen Capture already running.")
            isCheckingPermissions = false
        }
    }

    private fun stopFloatingToolService() {
        if (viewModel.uiState.value.isFloatingToolRunning == true) {
            Log.i(TAG, "Stopping FloatingToolService.")
            stopService(Intent(this, FloatingToolService::class.java))
        }
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        if (viewModel.uiState.value.isCaptureServiceRunning != true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Cannot start ScreenCaptureService: Notification permission missing!")
                Toast.makeText(this, "Notification permission needed.", Toast.LENGTH_SHORT).show()
                disableSession()
                return
            }
            Log.i(TAG, "Starting ScreenCaptureService.")
            startForegroundService(ScreenCaptureService.getStartIntent(this, resultCode, data))
        } else {
            Log.w(TAG, "startScreenCaptureService called but service already running.")
        }
    }

    private fun stopScreenCaptureService() {
        if (viewModel.uiState.value.isCaptureServiceRunning == true) {
            Log.i(TAG, "Stopping ScreenCaptureService.")
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
    }

    private fun checkFastFollowStatus(states: Map<String, DownloadStatus>) {
        val defaultModel = Model.entries.find { it.deliveryType == ModelDelivery.FAST_FOLLOW }
        if (defaultModel == null) {
            Log.e(TAG, "No FAST_FOLLOW model defined!")
            binding.tvModelStatus.text = "Error: No default model."
            binding.tvModelStatus.visibility = View.VISIBLE
            binding.swEnableSession.isEnabled = false
            binding.ivConfigCheck.setImageResource(R.drawable.outline_radio_button_partial_24)
            binding.ivConfigCheck.imageTintList = ContextCompat.getColorStateList(this, R.color.colorError)
            return
        }

        val defaultState = states[defaultModel.assetPackName]

        val hasOverlay = Settings.canDrawOverlays(this)
        val hasNotification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasAllPermissions = hasOverlay && hasNotification

        val isReady: Boolean
        when (defaultState) {
            is DownloadStatus.Completed -> {
                binding.tvModelStatus.visibility = View.GONE
                isReady = true
            }
            is DownloadStatus.Downloading -> {
                binding.tvModelStatus.text = "Downloading required model (${defaultState.progress}%)..."
                binding.tvModelStatus.visibility = View.VISIBLE
                isReady = false
            }
            is DownloadStatus.Transferring -> {
                binding.tvModelStatus.text = "Finalizing model installation..."
                binding.tvModelStatus.visibility = View.VISIBLE
                isReady = false
            }
            is DownloadStatus.Pending -> {
                binding.tvModelStatus.text = "Initializing download..."
                binding.tvModelStatus.visibility = View.VISIBLE
                isReady = false
            }
            is DownloadStatus.WaitingForWifi -> {
                binding.tvModelStatus.text = "Download paused, waiting for Wi-Fi..."
                binding.tvModelStatus.visibility = View.VISIBLE
                isReady = false
            }
            else -> { // NotInstalled, Failed, null
                binding.tvModelStatus.text = "Waiting for required model download..."
                binding.tvModelStatus.visibility = View.VISIBLE
                isReady = false
            }
        }

        if (isReady) {
            binding.ivConfigCheck.setImageResource(R.drawable.outline_check_circle_24)
            binding.ivConfigCheck.imageTintList = ContextCompat.getColorStateList(this, R.color.colorPrimary)
        } else {
            binding.ivConfigCheck.setImageResource(R.drawable.outline_radio_button_partial_24)
            binding.ivConfigCheck.imageTintList = ContextCompat.getColorStateList(this, R.color.colorError)
        }

        binding.swEnableSession.isEnabled = isReady && hasAllPermissions
    }

    private fun updateModelListUI(states: Map<String, DownloadStatus>) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedModelName = prefs.getString(KEY_SELECTED_MODEL, null)

        // If no model is saved, find the first completed model or default to GEMMA3_1B_IT
        val modelToSelect = if (savedModelName != null) {
            savedModelName
        } else {
            // Try to find a completed model
            val completedModel = Model.entries.firstOrNull {
                states[it.assetPackName] is DownloadStatus.Completed
            }
            val defaultModelName = completedModel?.name ?: Model.GEMMA3_1B_IT.name
            // Save this default selection
            prefs.edit { putString(KEY_SELECTED_MODEL, defaultModelName) }
            defaultModelName
        }

        val inflater = LayoutInflater.from(this)

        binding.modelRadioGroup.removeAllViews()

        Model.entries.forEach { model ->
            val view = inflater.inflate(R.layout.list_item_model, binding.modelRadioGroup, false)
            val state = states[model.assetPackName]

            val radioButton = view.findViewById<RadioButton>(R.id.model_radio_button)
            val modelName = view.findViewById<TextView>(R.id.model_name)
            val modelStatusText = view.findViewById<TextView>(R.id.model_status_text)
            val progressBar = view.findViewById<CircularProgressIndicator>(R.id.model_progress_bar)
            val deleteButton = view.findViewById<ImageButton>(R.id.model_delete_button)
            val progressText = view.findViewById<TextView>(R.id.model_progress_text)
            val downloadButton = view.findViewById<ImageButton>(R.id.model_download_button)
            val checkIcon = view.findViewById<ImageView>(R.id.model_check_icon)
            val licenseButton = view.findViewById<ImageButton>(R.id.model_license_button)

            modelName.text = model.displayName
            radioButton.isChecked = (model.name == modelToSelect)

            val isModelDownloaded = state is DownloadStatus.Completed

            modelName.alpha = if (isModelDownloaded) 1.0f else 0.5f
            modelStatusText.alpha = 1.0f

            progressText.visibility = View.GONE
            licenseButton.visibility = View.GONE
            progressBar.visibility = View.GONE
            downloadButton.visibility = View.GONE
            deleteButton.visibility = View.GONE
            checkIcon.visibility = View.GONE

            when (state) {
                is DownloadStatus.Completed -> {
                    modelStatusText.text = "Downloaded"

                    if (model.deliveryType == ModelDelivery.ON_DEMAND) {
                        deleteButton.visibility = View.VISIBLE
                    } else {
                        checkIcon.visibility = View.VISIBLE
                    }
                    radioButton.isEnabled = true
                    licenseButton.visibility = View.VISIBLE
                    licenseButton.setOnClickListener { showLicenseReviewDialog(model) }
                }
                is DownloadStatus.Downloading -> {
                    val percent = state.progress
                    modelStatusText.text = "Downloading..."
                    progressText.text = "$percent%"
                    progressText.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = false
                    progressBar.progress = percent
                    radioButton.isEnabled = false
                }
                is DownloadStatus.Pending -> {
                    modelStatusText.text = "Starting download..."
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    radioButton.isEnabled = false
                }
                is DownloadStatus.Transferring -> {
                    modelStatusText.text = "Finalizing..."
                    progressBar.visibility = View.VISIBLE
                    progressBar.isIndeterminate = true
                    radioButton.isEnabled = false
                }
                is DownloadStatus.WaitingForWifi -> {
                    modelStatusText.text = "Paused, waiting for Wi-Fi"
                    downloadButton.visibility = View.VISIBLE
                    radioButton.isEnabled = false
                }
                is DownloadStatus.Failed -> {
                    modelStatusText.text = "Download failed (Error: ${state.errorCode})"
                    downloadButton.visibility = View.VISIBLE
                    radioButton.isEnabled = false
                }
                else -> { // NotInstalled or null
                    modelStatusText.text = "Click to download"
                    downloadButton.visibility = View.VISIBLE
                    radioButton.isEnabled = false
                }
            }

            val performSelection = {
                binding.modelRadioGroup.children.forEach { childView ->
                    val otherRadioButton = childView.findViewById<RadioButton>(R.id.model_radio_button)
                    if (otherRadioButton != radioButton) {
                        otherRadioButton.isChecked = false
                    }
                }

                if (!radioButton.isChecked) {
                    radioButton.isChecked = true
                }

                prefs.edit { putString(KEY_SELECTED_MODEL, model.name) }
                Toast.makeText(this, "Model set to: ${model.displayName}", Toast.LENGTH_SHORT).show()
            }

            val selectAction = {
                val licenseAcceptedKey = PREF_KEY_LICENSE_ACCEPTED_PREFIX + model.name
                val isLicenseAccepted = prefs.getBoolean(licenseAcceptedKey, false)

                if (isLicenseAccepted) {
                    performSelection()
                } else {
                    showLicenseAgreementDialog(model) { performSelection() }
                }
            }

            radioButton.setOnClickListener {
                if (radioButton.isEnabled) {
                    selectAction()
                }
            }

            downloadButton.setOnClickListener {
                ModelDownloadManager.startDownload(model.assetPackName)
            }

            deleteButton.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Model?")
                    .setMessage("Are you sure you want to delete ${model.displayName}?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        ModelDownloadManager.removePack(model.assetPackName)
                    }
                    .show()
            }

            view.setOnClickListener {
                if (downloadButton.visibility == View.VISIBLE) {
                    downloadButton.performClick()
                } else if (radioButton.isEnabled) {
                    selectAction()
                }
            }

            binding.modelRadioGroup.addView(view)
        }
    }

    private fun readLicenseFile(model: Model): String {
        val location = ModelDownloadManager.getPackLocation(model.assetPackName)
        if (location == null) {
            Log.e(TAG, "Asset pack '${model.assetPackName}' location not found.")
            return "Error: Asset pack location not found. Is the model downloaded?"
        }

        val licenseFile = File(location.assetsPath(), model.licenseFileNameInPack)
        if (!licenseFile.exists()) {
            Log.e(TAG, "License file '${model.licenseFileNameInPack}' not found in pack at ${licenseFile.absolutePath}")
            return "Error: License file not found in asset pack."
        }

        return try {
            licenseFile.readText()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read license file", e)
            "Error: Could not read license file. ${e.message}"
        }
    }

    private fun showLicenseAgreementDialog(model: Model, onAgree: () -> Unit) {
        val licenseText = readLicenseFile(model)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = licenseText
            setPadding(48, 24, 48, 24)
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(this)
            .setTitle("${model.displayName} License Agreement")
            .setView(scrollView)
            .setCancelable(false)
            .setPositiveButton("Agree & Select") { dialog, _ ->
                prefs.edit { putBoolean(PREF_KEY_LICENSE_ACCEPTED_PREFIX + model.name, true) }
                onAgree()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLicenseReviewDialog(model: Model) {
        val licenseText = readLicenseFile(model)

        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = licenseText
            setPadding(48, 24, 48, 24)
            autoLinkMask = Linkify.WEB_URLS
            movementMethod = LinkMovementMethod.getInstance()
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(this)
            .setTitle("${model.displayName} License")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun launchPaywallActivity() {
        paywallActivityLauncher.launchIfNeeded(requiredEntitlementIdentifier = "")
    }

    /**
     * Updates the paywall card to show premium status
     */
    private fun updatePaywallCardForPremium() {
        // Find the TextViews inside the paywall card
        val paywallCard = binding.cardPaywall
        val paywallTitle = paywallCard.findViewById<TextView>(R.id.tv_paywall_title)
            ?: findTextViewByText(paywallCard, "Get Slopper Unlimited")
        val paywallSubtitle = paywallCard.findViewById<TextView>(R.id.tv_paywall_subtitle)
            ?: findTextViewByText(paywallCard, "Send as many")

        // Update title text
        paywallTitle?.text = "You are unstoppable."

        // Update subtitle text
        paywallSubtitle?.text = "You have full access to all Slopper features. Thank you for your support!"
    }

    /**
     * Resets the paywall card to show free tier messaging
     */
    private fun resetPaywallCardForFree() {
        val paywallCard = binding.cardPaywall
        val paywallTitle = paywallCard.findViewById<TextView>(R.id.tv_paywall_title)
            ?: findTextViewByText(paywallCard, "You are unstoppable")
        val paywallSubtitle = paywallCard.findViewById<TextView>(R.id.tv_paywall_subtitle)
            ?: findTextViewByText(paywallCard, "You have full access")

        // Reset to original text
        paywallTitle?.text = "Get Slopper Unlimited"
        paywallSubtitle?.text = "Send as many private replies as you want and create/modify prompts."
    }

    /**
     * Helper to convert dp to pixels
     */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    /**
     * Helper to find a TextView by its text content
     */
    private fun findTextViewByText(parent: View, searchText: String): TextView? {
        if (parent is TextView && parent.text.toString().startsWith(searchText.take(10))) {
            return parent
        }
        if (parent is ViewGroup) {
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val result = findTextViewByText(child, searchText)
                if (result != null) return result
            }
        }
        return null
    }

    override fun onActivityResult(result: PaywallResult) {
        Log.d(TAG, "Paywall result: $result")
        when (result) {
            is PaywallResult.Purchased -> {
                // User completed a purchase
                Log.d(TAG, "Purchase completed! Refreshing premium status.")
                viewModel.refreshAllData()
                Toast.makeText(this, "Purchase successful! Welcome to Slopper Unlimited!", Toast.LENGTH_LONG).show()
            }
            is PaywallResult.Restored -> {
                // User restored purchases
                Log.d(TAG, "Purchases restored! Refreshing premium status.")
                viewModel.refreshAllData()
                Toast.makeText(this, "Purchases restored successfully!", Toast.LENGTH_LONG).show()
            }
            is PaywallResult.Cancelled -> {
                // User cancelled the paywall
                Log.d(TAG, "Paywall cancelled by user.")
            }
            is PaywallResult.Error -> {
                // An error occurred
                Log.e(TAG, "Paywall error: ${result.error}")
                Toast.makeText(this, "An error occurred. Please try again.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}