package com.indrek.slopper

import android.app.Application
import android.service.autofill.FillCallback
import android.view.autofill.AutofillId

/**
 * A central, stable place to store data that lives as long as the app process.
 * This solves the problem of services being destroyed.
 */
class MyApplication : Application() {

    companion object {
        // This holds the reply after it's generated.
        var cachedReplies: List<Pair<String, String>>? = null

        // --- NEW: These will hold the "pending" autofill request ---
        var pendingCallback: FillCallback? = null
        var pendingAutofillId: AutofillId? = null
    }
}