package com.indrek.slopper.domain.usecase

import com.indrek.slopper.C.PREMIUM_ENTITLEMENT_ID
import com.indrek.slopper.domain.Result
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.VerificationResult
import com.revenuecat.purchases.getCustomerInfoWith
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * REFACTORED: Domain use case for checking premium entitlement status.
 * Encapsulates RevenueCat integration logic.
 */
class CheckPremiumStatusUseCase @Inject constructor() {

    /**
     * Checks if the user has an active premium subscription.
     * Returns Result with boolean indicating premium status.
     */
    suspend operator fun invoke(): Result<Boolean> {
        return suspendCancellableCoroutine { continuation ->
            Purchases.sharedInstance.getCustomerInfoWith(
                onError = { error ->
                    continuation.resume(
                        Result.Error(
                            message = "Failed to check premium status: ${error.message}",
                            exception = error.underlyingErrorMessage?.let { Exception(it) }
                        )
                    )
                },
                onSuccess = { customerInfo ->
                    val isPremium = isPremiumActive(customerInfo)
                    continuation.resume(Result.Success(isPremium))
                }
            )
        }
    }

    /**
     * Validates premium entitlement with verification.
     */
    private fun isPremiumActive(customerInfo: CustomerInfo): Boolean {
        val entitlement = customerInfo.entitlements[PREMIUM_ENTITLEMENT_ID]
        val isActive = entitlement?.isActive == true
        val isVerified = customerInfo.entitlements.verification != VerificationResult.FAILED
        return isActive && isVerified
    }
}