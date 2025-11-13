package com.indrek.slopper

import android.app.Application
import android.util.Log
import com.google.android.gms.time.TrustedTime
import com.google.android.gms.time.TrustedTimeClient
import com.indrek.slopper.data.ModelDownloadManager
import com.revenuecat.purchases.EntitlementVerificationMode
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltAndroidApp
class SlopperApplication : Application() {

    var trustedTimeClient: TrustedTimeClient? = null
        private set

    private val _isTrustedTimeInitialized = MutableStateFlow(false)
    val isTrustedTimeInitialized: StateFlow<Boolean> = _isTrustedTimeInitialized.asStateFlow()

    override fun onCreate() {

        super.onCreate()
        Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(
            PurchasesConfiguration.Builder(this, "goog_jhfhRHYXodoCuWlucVgGArwOwXb")
                .entitlementVerificationMode(EntitlementVerificationMode.INFORMATIONAL)
                .build())
        ModelDownloadManager.initialize(this)

        Log.d("SlopperApplication", "Initializing TrustedTimeClient...")
        TrustedTime.createClient(this).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                trustedTimeClient = task.result
                _isTrustedTimeInitialized.value = true
                Log.d("SlopperApplication", "TrustedTimeClient initialized successfully.")
            } else {
                _isTrustedTimeInitialized.value = false
                Log.e("SlopperApplication", "Failed to create TrustedTimeClient.", task.exception)
            }
        }
    }
}