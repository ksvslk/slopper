package com.indrek.slopper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.indrek.slopper.domain.Result
import com.indrek.slopper.domain.usecase.CheckPremiumStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for PromptEditorActivity.
 * Manages premium status for feature gating.
 */
@HiltViewModel
class PromptEditorViewModel @Inject constructor(
    private val checkPremiumStatusUseCase: CheckPremiumStatusUseCase
) : ViewModel() {

    private val _isPremium = MutableStateFlow(false)
    val isPremium: StateFlow<Boolean> = _isPremium.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        checkPremiumStatus()
    }

    /**
     * Checks the user's premium status.
     * Call this when returning from purchase flow.
     */
    fun checkPremiumStatus() {
        viewModelScope.launch {
            _isLoading.value = true

            when (val result = checkPremiumStatusUseCase()) {
                is Result.Success -> {
                    _isPremium.value = result.data
                }
                is Result.Error -> {
                    _isPremium.value = false
                    // Could expose error state if needed
                }
                is Result.Loading -> { /* Not used */ }
            }

            _isLoading.value = false
        }
    }

    /**
     * Call this from onResume to refresh premium status
     * after user returns from purchase flow.
     */
    fun refreshPremiumStatus() {
        checkPremiumStatus()
    }
}