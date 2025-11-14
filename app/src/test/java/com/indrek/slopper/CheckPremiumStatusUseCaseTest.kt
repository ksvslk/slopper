package com.indrek.slopper.domain.usecase

import com.indrek.slopper.C.PREMIUM_ENTITLEMENT_ID
import com.revenuecat.purchases.CustomerInfo
import com.revenuecat.purchases.EntitlementInfo
import com.revenuecat.purchases.EntitlementInfos
import com.revenuecat.purchases.VerificationResult
import io.mockk.*
import org.junit.Before
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for CheckPremiumStatusUseCase.
 *
 * Note: These tests verify the isPremiumActive() logic.
 * The actual RevenueCat callback mocking is complex, so we focus on
 * testing the premium validation logic directly.
 */
class CheckPremiumStatusUseCaseTest {

    private lateinit var useCase: CheckPremiumStatusUseCase

    @Before
    fun setup() {
        useCase = CheckPremiumStatusUseCase()
    }

    // ==================== Premium Active Tests ====================

    @Test
    fun `active premium entitlement with verified status returns true`() {
        // Given
        val mockEntitlement = mockk<EntitlementInfo>()
        every { mockEntitlement.isActive } returns true

        val mockEntitlements = mockk<EntitlementInfos>()
        every { mockEntitlements[PREMIUM_ENTITLEMENT_ID] } returns mockEntitlement
        every { mockEntitlements.verification } returns VerificationResult.VERIFIED

        val mockCustomerInfo = mockk<CustomerInfo>()
        every { mockCustomerInfo.entitlements } returns mockEntitlements

        // When - Use reflection to test the private method
        val isPremiumMethod = CheckPremiumStatusUseCase::class.java
            .getDeclaredMethod("isPremiumActive", CustomerInfo::class.java)
        isPremiumMethod.isAccessible = true

        val result = isPremiumMethod.invoke(useCase, mockCustomerInfo) as Boolean

        // Then
        assertTrue(result, "Active and verified entitlement should return true")
    }

    @Test
    fun `inactive premium entitlement returns false`() {
        // Given
        val mockEntitlement = mockk<EntitlementInfo>()
        every { mockEntitlement.isActive } returns false

        val mockEntitlements = mockk<EntitlementInfos>()
        every { mockEntitlements[PREMIUM_ENTITLEMENT_ID] } returns mockEntitlement
        every { mockEntitlements.verification } returns VerificationResult.VERIFIED

        val mockCustomerInfo = mockk<CustomerInfo>()
        every { mockCustomerInfo.entitlements } returns mockEntitlements

        // When
        val isPremiumMethod = CheckPremiumStatusUseCase::class.java
            .getDeclaredMethod("isPremiumActive", CustomerInfo::class.java)
        isPremiumMethod.isAccessible = true

        val result = isPremiumMethod.invoke(useCase, mockCustomerInfo) as Boolean

        // Then
        assertFalse(result, "Inactive entitlement should return false")
    }

    @Test
    fun `null entitlement returns false`() {
        // Given
        val mockEntitlements = mockk<EntitlementInfos>()
        every { mockEntitlements[PREMIUM_ENTITLEMENT_ID] } returns null
        every { mockEntitlements.verification } returns VerificationResult.VERIFIED

        val mockCustomerInfo = mockk<CustomerInfo>()
        every { mockCustomerInfo.entitlements } returns mockEntitlements

        // When
        val isPremiumMethod = CheckPremiumStatusUseCase::class.java
            .getDeclaredMethod("isPremiumActive", CustomerInfo::class.java)
        isPremiumMethod.isAccessible = true

        val result = isPremiumMethod.invoke(useCase, mockCustomerInfo) as Boolean

        // Then
        assertFalse(result, "Null entitlement should return false")
    }

    // ==================== Verification Tests ====================

    @Test
    fun `failed verification returns false even with active entitlement`() {
        // Given - Active but FAILED verification
        val mockEntitlement = mockk<EntitlementInfo>()
        every { mockEntitlement.isActive } returns true

        val mockEntitlements = mockk<EntitlementInfos>()
        every { mockEntitlements[PREMIUM_ENTITLEMENT_ID] } returns mockEntitlement
        every { mockEntitlements.verification } returns VerificationResult.FAILED // Security check!

        val mockCustomerInfo = mockk<CustomerInfo>()
        every { mockCustomerInfo.entitlements } returns mockEntitlements

        // When
        val isPremiumMethod = CheckPremiumStatusUseCase::class.java
            .getDeclaredMethod("isPremiumActive", CustomerInfo::class.java)
        isPremiumMethod.isAccessible = true

        val result = isPremiumMethod.invoke(useCase, mockCustomerInfo) as Boolean

        // Then
        assertFalse(result, "Failed verification should return false for security")
    }

    @Test
    fun `verified on device is accepted`() {
        // Given
        val mockEntitlement = mockk<EntitlementInfo>()
        every { mockEntitlement.isActive } returns true

        val mockEntitlements = mockk<EntitlementInfos>()
        every { mockEntitlements[PREMIUM_ENTITLEMENT_ID] } returns mockEntitlement
        every { mockEntitlements.verification } returns VerificationResult.VERIFIED_ON_DEVICE

        val mockCustomerInfo = mockk<CustomerInfo>()
        every { mockCustomerInfo.entitlements } returns mockEntitlements

        // When
        val isPremiumMethod = CheckPremiumStatusUseCase::class.java
            .getDeclaredMethod("isPremiumActive", CustomerInfo::class.java)
        isPremiumMethod.isAccessible = true

        val result = isPremiumMethod.invoke(useCase, mockCustomerInfo) as Boolean

        // Then
        assertTrue(result, "VERIFIED_ON_DEVICE should be accepted")
    }

    @Test
    fun `not requested verification is accepted`() {
        // Given
        val mockEntitlement = mockk<EntitlementInfo>()
        every { mockEntitlement.isActive } returns true

        val mockEntitlements = mockk<EntitlementInfos>()
        every { mockEntitlements[PREMIUM_ENTITLEMENT_ID] } returns mockEntitlement
        every { mockEntitlements.verification } returns VerificationResult.NOT_REQUESTED

        val mockCustomerInfo = mockk<CustomerInfo>()
        every { mockCustomerInfo.entitlements } returns mockEntitlements

        // When
        val isPremiumMethod = CheckPremiumStatusUseCase::class.java
            .getDeclaredMethod("isPremiumActive", CustomerInfo::class.java)
        isPremiumMethod.isAccessible = true

        val result = isPremiumMethod.invoke(useCase, mockCustomerInfo) as Boolean

        // Then
        assertTrue(result, "NOT_REQUESTED should be accepted")
    }

    // ==================== Edge Cases ====================

    @Test
    fun `expired entitlement with active false returns false`() {
        // Given - Entitlement exists but expired
        val mockEntitlement = mockk<EntitlementInfo>()
        every { mockEntitlement.isActive } returns false

        val mockEntitlements = mockk<EntitlementInfos>()
        every { mockEntitlements[PREMIUM_ENTITLEMENT_ID] } returns mockEntitlement
        every { mockEntitlements.verification } returns VerificationResult.VERIFIED

        val mockCustomerInfo = mockk<CustomerInfo>()
        every { mockCustomerInfo.entitlements } returns mockEntitlements

        // When
        val isPremiumMethod = CheckPremiumStatusUseCase::class.java
            .getDeclaredMethod("isPremiumActive", CustomerInfo::class.java)
        isPremiumMethod.isAccessible = true

        val result = isPremiumMethod.invoke(useCase, mockCustomerInfo) as Boolean

        // Then
        assertFalse(result, "Expired entitlement should return false")
    }

    @Test
    fun `active entitlement with verified status is premium`() {
        // Given - Both conditions met
        val mockEntitlement = mockk<EntitlementInfo>()
        every { mockEntitlement.isActive } returns true

        val mockEntitlements = mockk<EntitlementInfos>()
        every { mockEntitlements[PREMIUM_ENTITLEMENT_ID] } returns mockEntitlement
        every { mockEntitlements.verification } returns VerificationResult.VERIFIED

        val mockCustomerInfo = mockk<CustomerInfo>()
        every { mockCustomerInfo.entitlements } returns mockEntitlements

        // When
        val isPremiumMethod = CheckPremiumStatusUseCase::class.java
            .getDeclaredMethod("isPremiumActive", CustomerInfo::class.java)
        isPremiumMethod.isAccessible = true

        val result = isPremiumMethod.invoke(useCase, mockCustomerInfo) as Boolean

        // Then
        assertTrue(result, "Active and verified should be premium")
    }
}