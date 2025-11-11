package com.indrek.slopper

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.assetpacks.AssetPackLocation
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.AssetPackStates
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A custom sealed class to represent the download state in a way
 * that we can control for "optimistic UI" updates.
 */
sealed class DownloadStatus {
    data object NotInstalled : DownloadStatus()
    data object Pending : DownloadStatus() // Our custom "instant" state
    data class Downloading(val progress: Int) : DownloadStatus()
    data object Transferring : DownloadStatus()
    data object Completed : DownloadStatus()
    data class Failed(val errorCode: Int) : DownloadStatus()
    data object WaitingForWifi : DownloadStatus()
}

/**
 * Singleton object to manage all model asset pack downloads.
 * This lives for the entire application lifecycle, ensuring
 * download state is monitored even when the Activity is not visible.
 */
object ModelDownloadManager {

    private const val TAG = "ModelDownloadManager"

    private lateinit var assetPackManager: AssetPackManager
    private lateinit var appContext: Context

    // This is the single source of truth for all model states
    private val _modelStates = MutableLiveData<Map<String, DownloadStatus>>(emptyMap())
    val modelStates: LiveData<Map<String, DownloadStatus>> = _modelStates

    private val allModelPackNames by lazy { Model.entries.map { it.assetPackName } }
    private val isInitialized = AtomicBoolean(false)

    /**
     * The single listener that receives all updates from Google Play.
     */
    private val assetPackListener = object : AssetPackStateUpdateListener {
        override fun onStateUpdate(state: AssetPackState) {
            val packName = state.name()
            Log.d(TAG, "AssetPackListener: ${packName} is ${statusToString(state.status())}")

            val newStatus = when (state.status()) {
                AssetPackStatus.COMPLETED -> DownloadStatus.Completed
                AssetPackStatus.DOWNLOADING -> {
                    val percent = (state.bytesDownloaded() * 100 / (state.totalBytesToDownload().takeIf { it > 0 } ?: 1)).toInt()
                    DownloadStatus.Downloading(percent)
                }
                AssetPackStatus.TRANSFERRING -> DownloadStatus.Transferring
                AssetPackStatus.PENDING -> DownloadStatus.Pending
                AssetPackStatus.WAITING_FOR_WIFI -> DownloadStatus.WaitingForWifi
                AssetPackStatus.FAILED -> DownloadStatus.Failed(state.errorCode())
                AssetPackStatus.NOT_INSTALLED -> DownloadStatus.NotInstalled
                else -> _modelStates.value?.get(packName) ?: DownloadStatus.NotInstalled // Keep old state if unknown
            }

            updateStateMap(packName, newStatus)
        }
    }

    /**
     * Must be called from the Application's onCreate.
     */
    fun initialize(context: Context) {
        if (isInitialized.getAndSet(true)) return // Run only once

        appContext = context.applicationContext
        assetPackManager = AssetPackManagerFactory.getInstance(appContext)

        // Register the listener to run for the app's entire lifecycle
        assetPackManager.registerListener(assetPackListener)

        // Get the initial states
        refreshStates()
    }

    /**
     * Asks Google Play for the current status of all packs.
     * This is useful when the app resumes.
     */
    fun refreshStates() {
        if (!isInitialized.get()) return

        assetPackManager.getPackStates(allModelPackNames).addOnSuccessListener { states ->
            processStateUpdate(states.packStates())

            // Trigger fetch for any fast-follow packs that aren't downloaded
            val fastFollowPacksToFetch = Model.entries
                .filter { it.deliveryType == ModelDelivery.FAST_FOLLOW }
                .mapNotNull { model ->
                    val status = states.packStates()[model.assetPackName]?.status()
                    if (status == AssetPackStatus.NOT_INSTALLED) {
                        model.assetPackName
                    } else null
                }

            if (fastFollowPacksToFetch.isNotEmpty()) {
                Log.d(TAG, "Triggering fetch for fast-follow packs: $fastFollowPacksToFetch")
                assetPackManager.fetch(fastFollowPacksToFetch)
            }

        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get pack states on refresh", e)
        }
    }

    /**
     * Starts a download for a specific model.
     * This provides *instant* UI feedback.
     */
    fun startDownload(packName: String) {
        if (!isInitialized.get()) return

        // --- INSTANT FEEDBACK ---
        // Optimistically set the state to Pending so the UI updates *immediately*.
        // The real listener will overwrite this soon, but the user sees an instant change.
        updateStateMap(packName, DownloadStatus.Pending)
        // --- END INSTANT FEEDBACK ---

        Toast.makeText(appContext, "Starting download...", Toast.LENGTH_SHORT).show()

        assetPackManager.fetch(listOf(packName)).addOnFailureListener { e ->
            Log.e(TAG, "Failed to initiate fetch for $packName", e)
            updateStateMap(packName, DownloadStatus.Failed(0)) // Use a generic error code
        }
    }

    /**
     * Requests removal of an on-demand asset pack.
     */
    fun removePack(packName: String) {
        if (!isInitialized.get()) return

        assetPackManager.removePack(packName).addOnSuccessListener {
            Log.d(TAG, "Successfully requested removal for $packName")
            // The listener will get a NOT_INSTALLED update
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to request removal for $packName", e)
            Toast.makeText(appContext, "Could not remove pack.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Gets the asset pack location. This is a *synchronous* call.
     */
    fun getPackLocation(packName: String): AssetPackLocation? {
        if (!isInitialized.get()) return null
        return assetPackManager.getPackLocation(packName)
    }

    /**
     * This is a BLOCKING call to get a model's file path.
     * It *must* only be run from a background thread (like in ScreenCaptureService).
     */
    fun getAbsoluteModelPath(model: Model): String? {
        if (!isInitialized.get()) return null

        val packName = model.assetPackName
        try {
            // This is a blocking call to wait for the async task to finish
            val packStatesTask: Task<AssetPackStates> = assetPackManager.getPackStates(listOf(packName))
            val statesResult: AssetPackStates = Tasks.await(packStatesTask)
            val state: AssetPackState? = statesResult.packStates()[packName]

            if (state != null && state.status() == AssetPackStatus.COMPLETED) {
                // This call is synchronous
                val location: AssetPackLocation? = assetPackManager.getPackLocation(packName)

                if (location != null) {
                    val modelFile = File(location.assetsPath(), model.fileNameInPack)
                    if (modelFile.exists()) {
                        Log.d(TAG, "Model '${model.fileNameInPack}' found: ${modelFile.absolutePath}")
                        return modelFile.absolutePath
                    } else {
                        Log.e(TAG, "Asset pack '$packName' complete, but file '${model.fileNameInPack}' not found.")
                    }
                } else {
                    Log.e(TAG, "Asset pack '$packName' complete, but location is null.")
                }
            } else {
                Log.e(TAG, "Model pack '$packName' is not downloaded. Status: ${state?.status()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get model path from AssetPackManager", e)
        }
        return null
    }

    // --- Private Helper Functions ---

    private fun processStateUpdate(states: Map<String, AssetPackState>) {
        val newStates = _modelStates.value.orEmpty().toMutableMap()
        for (model in Model.entries) {
            val packName = model.assetPackName
            val state = states[packName]

            newStates[packName] = if (state == null) {
                DownloadStatus.NotInstalled // Default if not found
            } else {
                when (state.status()) {
                    AssetPackStatus.COMPLETED -> DownloadStatus.Completed
                    AssetPackStatus.DOWNLOADING -> {
                        val percent = (state.bytesDownloaded() * 100 / (state.totalBytesToDownload().takeIf { it > 0 } ?: 1)).toInt()
                        DownloadStatus.Downloading(percent)
                    }
                    AssetPackStatus.TRANSFERRING -> DownloadStatus.Transferring
                    AssetPackStatus.PENDING -> DownloadStatus.Pending
                    AssetPackStatus.WAITING_FOR_WIFI -> DownloadStatus.WaitingForWifi
                    AssetPackStatus.FAILED -> DownloadStatus.Failed(state.errorCode())
                    AssetPackStatus.NOT_INSTALLED -> DownloadStatus.NotInstalled
                    else -> DownloadStatus.NotInstalled
                }
            }
        }
        _modelStates.postValue(newStates)
    }

    private fun updateStateMap(packName: String, status: DownloadStatus) {
        val newStates = _modelStates.value.orEmpty().toMutableMap()
        newStates[packName] = status
        _modelStates.postValue(newStates)
    }

    private fun statusToString(status: Int): String {
        return when (status) {
            AssetPackStatus.PENDING -> "PENDING"
            AssetPackStatus.DOWNLOADING -> "DOWNLOADING"
            AssetPackStatus.TRANSFERRING -> "TRANSFERRING"
            AssetPackStatus.COMPLETED -> "COMPLETED"
            AssetPackStatus.FAILED -> "FAILED"
            AssetPackStatus.CANCELED -> "CANCELED"
            AssetPackStatus.WAITING_FOR_WIFI -> "WAITING_FOR_WIFI"
            AssetPackStatus.NOT_INSTALLED -> "NOT_INSTALLED"
            else -> "UNKNOWN"
        }
    }
}