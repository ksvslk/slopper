package com.indrek.slopper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indrek.slopper.SlopperApplication
import com.indrek.slopper.data.PermissionStateRepository
import com.indrek.slopper.data.ServiceStateRepository
import com.indrek.slopper.data.SessionStateRepository
import com.indrek.slopper.data.UserLimitData
import com.indrek.slopper.data.UserLimitRepository
import com.indrek.slopper.domain.Result
import com.indrek.slopper.domain.usecase.CheckPremiumStatusUseCase
import com.indrek.slopper.domain.usecase.StartCaptureSessionUseCase
import com.indrek.slopper.domain.usecase.StopCaptureSessionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * This data class represents EVERYTHING the UI needs to know.
 * The ViewModel's only job is to create and update this state.
 */
data class SlopperUiState(
    val isPremium: Boolean = false,
    val limitData: UserLimitData = UserLimitData(),
    val isCaptureServiceRunning: Boolean = false,
    val isFloatingToolRunning: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isSessionEnabledByUser: Boolean = false,
    val error: String? = null,  // NEW: For displaying errors to the user
    val isLoading: Boolean = false  // NEW: For showing loading states
)

/**
 * REFACTORED: Now uses domain use cases for business logic.
 * The ViewModel is much simpler and focused on coordinating state.
 */
@HiltViewModel
class SlopperActivityViewModel @Inject constructor(
    private val app: SlopperApplication,
    private val userLimitRepo: UserLimitRepository,
    private val serviceStateRepo: ServiceStateRepository,
    private val permissionStateRepo: PermissionStateRepository,
    private val sessionStateRepo: SessionStateRepository,
    private val checkPremiumStatusUseCase: CheckPremiumStatusUseCase,
    private val startCaptureSessionUseCase: StartCaptureSessionUseCase,
    private val stopCaptureSessionUseCase: StopCaptureSessionUseCase
) : ViewModel() {

    // Internal, private flow to hold premium state
    private val _isPremium = MutableStateFlow(false)

    // Error and loading state
    private val _error = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(false)

    // The single, combined state flow that the UI will observe
    private val _uiState = MutableStateFlow(SlopperUiState())
    val uiState = _uiState.asStateFlow() // Read-only version for the UI

    // We can expose the app's StateFlow directly for the TrustedTime check
    val isTrustedTimeInitialized: StateFlow<Boolean> = app.isTrustedTimeInitialized
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // Combine all state sources into a single UI state
        combine(
            _isPremium,
            userLimitRepo.limitDataFlow,
            serviceStateRepo.isCaptureServiceRunning,
            serviceStateRepo.isFloatingToolRunning,
            permissionStateRepo.hasOverlayPermission,
            permissionStateRepo.hasNotificationPermission,
            sessionStateRepo.isSessionEnabledByUser,
            _error,
            _isLoading
        ) { values: Array<Any?> ->
            SlopperUiState(
                isPremium = values[0] as Boolean,
                limitData = values[1] as UserLimitData,
                isCaptureServiceRunning = values[2] as Boolean,
                isFloatingToolRunning = values[3] as Boolean,
                hasOverlayPermission = values[4] as Boolean,
                hasNotificationPermission = values[5] as Boolean,
                isSessionEnabledByUser = values[6] as Boolean,
                error = values[7] as String?,
                isLoading = values[8] as Boolean
            )
        }.onEach { newState ->
            _uiState.value = newState
        }.launchIn(viewModelScope)

        // Initial data refresh
        refreshAllData()
    }

    /**
     * REFACTORED: Now uses the use case to check premium status.
     * Call this from the Activity's onResume() or when a purchase is made.
     */
    fun refreshAllData() {
        viewModelScope.launch {
            _isLoading.value = true

            // 1. Check premium status using use case
            when (val result = checkPremiumStatusUseCase()) {
                is Result.Success -> _isPremium.value = result.data
                is Result.Error -> {
                    _isPremium.value = false
                    _error.value = result.message
                }
                is Result.Loading -> { /* Not used in this case */ }
            }

            // 2. Refresh limit data (this updates the flow in the repository)
            userLimitRepo.getLimitData()

            // 3. Refresh permissions
            permissionStateRepo.refreshPermissions()

            _isLoading.value = false
        }
    }

    /**
     * The Activity will call this when the TrustedTime client is ready.
     * This triggers a re-check of the limit data.
     */
    fun onTrustedTimeReady() {
        userLimitRepo.getLimitData() // This will update the flow
    }

    /**
     * REFACTORED: Uses the StartCaptureSessionUseCase for complex logic.
     * The Activity/Fragment calls this when the user wants to start capturing.
     */
    fun startCaptureSession() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null // Clear previous errors

            when (val result = startCaptureSessionUseCase(isPremium = _isPremium.value)) {
                is Result.Success -> {
                    // Success! The use case has already started the services
                    // and updated the session state
                }
                is Result.Error -> {
                    _error.value = result.message
                }
                is Result.Loading -> { /* Not used */ }
            }

            _isLoading.value = false
        }
    }

    /**
     * REFACTORED: Uses the StopCaptureSessionUseCase.
     * The Activity/Fragment calls this when the user wants to stop capturing.
     */
    fun stopCaptureSession() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            when (val result = stopCaptureSessionUseCase()) {
                is Result.Success -> {
                    // Success! Services stopped
                }
                is Result.Error -> {
                    _error.value = result.message
                }
                is Result.Loading -> { /* Not used */ }
            }

            _isLoading.value = false
        }
    }

    /**
     * Clears the current error message.
     * Call this after the UI has displayed the error to the user.
     */
    fun clearError() {
        _error.value = null
    }
}