package com.indrek.slopper

import android.app.Application
import android.util.Log
import androidx.lifecycle.MutableLiveData // --- ADD THIS ---
import com.google.android.gms.time.TrustedTime
import com.google.android.gms.time.TrustedTimeClient
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration

class SlopperApplication : Application() {

    var trustedTimeClient: TrustedTimeClient? = null
        private set

    // --- ADDED: LiveData to broadcast init status ---
    val isTrustedTimeInitialized = MutableLiveData(false)
    // --- END ADDED ---

    override fun onCreate() {
        super.onCreate()
        Purchases.logLevel = LogLevel.DEBUG
        Purchases.configure(
            PurchasesConfiguration.Builder(this, "goog_jhfhRHYXodoCuWlucVgGArwOwXb")
                .build())
        ModelDownloadManager.initialize(this)

        Log.d("SlopperApplication", "Initializing TrustedTimeClient...")
        TrustedTime.createClient(this).addOnCompleteListener { task ->
            if (task.isSuccessful) {
                trustedTimeClient = task.result
                isTrustedTimeInitialized.postValue(true) // <-- Notify observers
                Log.d("SlopperApplication", "TrustedTimeClient initialized successfully.")
            } else {
                isTrustedTimeInitialized.postValue(false) // <-- Notify of failure
                Log.e("SlopperApplication", "Failed to create TrustedTimeClient.", task.exception)
                val exception = task.exception
            }
        }
    }
}