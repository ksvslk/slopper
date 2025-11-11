package com.indrek.slopper.service

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.indrek.slopper.SlopperActivity
import com.indrek.slopper.Model

import java.util.concurrent.Executors
import androidx.core.graphics.createBitmap
import androidx.core.content.edit
// --- REMOVED ASSET PACK IMPORTS ---
import com.indrek.slopper.R
// --- MODIFIED: Imports for Limit Check ---
import com.indrek.slopper.SlopperActivity.Companion.FREE_TIER_LIMIT
import com.indrek.slopper.SlopperActivity.Companion.KEY_DAILY_LIMIT_COUNT
import com.indrek.slopper.SlopperActivity.Companion.KEY_DAILY_LIMIT_DAY
import com.indrek.slopper.SlopperActivity.Companion.PREFS_NAME
import com.indrek.slopper.SlopperActivity.Companion.PREMIUM_ENTITLEMENT_ID
import com.indrek.slopper.SlopperApplication // <-- ADD THIS IMPORT
import com.revenuecat.purchases.CacheFetchPolicy
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.getCustomerInfoWith
import java.util.Calendar
// --- END MODIFIED ---
import com.indrek.slopper.generatePrompt
import com.indrek.slopper.getTemperatureKey
import com.indrek.slopper.getTopKKey
// --- ADDED SINGLETON IMPORT ---
import com.indrek.slopper.ModelDownloadManager
import java.io.File
import java.lang.IllegalStateException

class ScreenCaptureService : Service() {
    // ... (All properties like mediaProjection, virtualDisplay, etc. are unchanged) ...
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var commandReceiver: BroadcastReceiver
    private var captureAttempts = 0

    private var lastRecognizedText: String? = null

    private val executor = Executors.newSingleThreadExecutor()

    // --- MODIFICATION: START ---
    private var configChangeReceiver: BroadcastReceiver? = null
    // --- MODIFICATION: END ---

    // --- ADDED: Helper to get the application class ---
    private val slopperApp: SlopperApplication
        get() = applicationContext as SlopperApplication
    // --- END ADDED ---

    private val permissionCheckRunnable = object : Runnable {
        override fun run() {
            if (!Settings.canDrawOverlays(this@ScreenCaptureService)) {
                Log.w(TAG, "Overlay permission revoked while service running. Stopping service.")
                Toast.makeText(this@ScreenCaptureService, "Overlay permission revoked. Stopping capture session.", Toast.LENGTH_LONG).show()
                getSharedPreferences(SlopperActivity.PREFS_NAME, MODE_PRIVATE).edit().apply {
                    putBoolean(SlopperActivity.KEY_SERVICES_ENABLED, false)
                    apply()
                }
                _isServiceRunning.postValue(false)
                stopSelf()
            } else {
                handler.postDelayed(this, 2000)
            }
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection was stopped externally. Stopping service.")
            _isServiceRunning.postValue(false)
            stopSelf()
        }
    }

    // ... (Companion object is unchanged) ...
    companion object {
        const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_ID = 123
        const val CHANNEL_ID = "ScreenCapture"
        const val ACTION_CAPTURE_AND_RECOGNIZE = "com.indrek.slopper.ACTION_CAPTURE_AND_RECOGNIZE"
        const val ACTION_STOP_SERVICE = "com.indrek.slopper.ACTION_STOP_SERVICE"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_SINGLE_REPLY = "extra_single_reply"
        const val EXTRA_IS_REGENERATION = "is_regeneration"
        const val EXTRA_ERROR_MESSAGE = "extra_error_message" // --- ADDED ---

        private val _isServiceRunning = MutableLiveData<Boolean>(false)
        val isServiceRunning: LiveData<Boolean> = _isServiceRunning

        fun getStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }
    }

    // ... (onCreate is unchanged) ...
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        _isServiceRunning.postValue(true)
        createNotificationChannel()

        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CAPTURE_AND_RECOGNIZE) {

                    // --- MODIFIED: This is the "Security Check" ---
                    // All requests, new or regeneration, must pass this check.
                    checkLimitAndProceed_Secure(
                        onSuccess = {
                            // Limit OK! Proceed with the original logic
                            val isRegeneration = intent.getBooleanExtra(EXTRA_IS_REGENERATION, false)
                            if (isRegeneration) {
                                Log.d(TAG, "Received regeneration command")
                                regenerateReply()
                            } else {
                                Log.d(TAG, "Received initial capture command")
                                val captureRect: Rect? = intent.getParcelableExtra(FloatingToolService.EXTRA_CAPTURE_RECT)
                                captureAttempts = 0
                                handler.postDelayed({ attemptCapture(captureRect) }, 300)
                            }
                        },
                        onFailure = { errorMsg ->
                            // Limit reached or error. Show error and stop.
                            handler.post {
                                Toast.makeText(this@ScreenCaptureService, errorMsg, Toast.LENGTH_LONG).show()
                            }
                            // Broadcast a completion (with null) to stop any loading spinners
                            broadcastCompletion(null, errorMsg)
                        }
                    )
                    // --- END MODIFIED ---
                }
            }
        }
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        try {
            registerReceiver(commandReceiver, IntentFilter(ACTION_CAPTURE_AND_RECOGNIZE), null, handler, receiverFlags)
            Log.d(TAG, "Command receiver registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver", e)
            _isServiceRunning.postValue(false)
            stopSelf()
        }

        // --- MODIFICATION: START ---
        // Register receiver for configuration changes (like rotation)
        configChangeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_CONFIGURATION_CHANGED) {
                    Log.d(TAG, "Configuration changed. Re-setting up VirtualDisplay.")
                    // Post to handler to ensure it runs on the service's main thread
                    handler.post { setupVirtualDisplay() }
                }
            }
        }
        try {
            val configFilter = IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            registerReceiver(configChangeReceiver, configFilter, null, handler)
            Log.d(TAG, "ConfigChange receiver registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering ConfigChange receiver", e)
            // Not fatal, but logging it
        }
        // --- MODIFICATION: END ---

        handler.postDelayed(permissionCheckRunnable, 2000)
    }

    // --- This function is UNCHANGED ---
    private fun checkLimitAndProceed_Secure(onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        // 1. Check RevenueCat (fetch from network to be sure)
        Purchases.sharedInstance.getCustomerInfoWith(
            fetchPolicy = CacheFetchPolicy.FETCH_CURRENT,
            onSuccess = { customerInfo ->
                val isPremium = customerInfo.entitlements[PREMIUM_ENTITLEMENT_ID]?.isActive == true

                if (isPremium) {
                    // User is premium, all good!
                    Log.d(TAG, "Premium user confirmed. Proceeding.")
                    onSuccess()
                    return@getCustomerInfoWith
                }

                // --- User is FREE. Check and increment the counter ---
                if (checkAndIncrementCounter()) {
                    // Limit OK
                    Log.d(TAG, "Free user limit OK. Proceeding.")
                    onSuccess()
                } else {
                    // Limit Reached
                    Log.w(TAG, "Free user limit REACHED. Denying.")
                    onFailure("Daily free limit reached. Upgrade for more.")
                }
            },
            onError = {
                // Fail closed. If we can't check RC, assume they are free and deny.
                Log.e(TAG, "Could not verify subscription status. Denying request. ${it.message}")
                onFailure("Could not verify subscription. Please check connection.")
            }
        )
    }

    /**
     * Checks and increments the daily limit counter.
     * --- THIS IS THE FIX ---
     * @return `true` if generation is allowed, `false` if limit is reached.
     */
    private fun checkAndIncrementCounter(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // --- MODIFIED: Use the new TrustedTimeClient ---
        val trustedTime: Long? = slopperApp.trustedTimeClient?.computeCurrentUnixEpochMillis()

        if (trustedTime == null) {
            // This case handles when the client failed to init or has no time signal.
            // As per the docs, this can happen if there's no internet since boot.
            // We "fail closed" and deny the request to be safe.
            Log.e(TAG, "TrustedTimeClient is null or has no time signal. Denying request.")
            // You could also fall back to System.currentTimeMillis() if you want to allow
            // the vulnerable behavior, but failing closed is safer.
            // val trustedTime = System.currentTimeMillis() // <-- Vulnerable fallback
            return false // Deny request
        }

        val trustedCalendar = Calendar.getInstance().apply { timeInMillis = trustedTime }
        val trustedDay = trustedCalendar.get(Calendar.DAY_OF_YEAR)
        // --- END MODIFIED ---

        val lastGenDay = prefs.getInt(KEY_DAILY_LIMIT_DAY, -1)
        var generationCount = prefs.getInt(KEY_DAILY_LIMIT_COUNT, 0)

        if (trustedDay != lastGenDay) {
            // New day, reset counter
            generationCount = 1
            prefs.edit {
                putInt(KEY_DAILY_LIMIT_COUNT, generationCount)
                putInt(KEY_DAILY_LIMIT_DAY, trustedDay)
                apply()
            }
            Log.d(TAG, "New trusted day detected. Count reset to 1.")
            return true // Allowed
        } else {
            // Same day, check limit
            if (generationCount < FREE_TIER_LIMIT) {
                // Limit not reached, increment
                generationCount++
                prefs.edit {
                    putInt(KEY_DAILY_LIMIT_COUNT, generationCount)
                    apply()
                }
                Log.d(TAG, "Same trusted day. Count incremented to $generationCount.")
                return true // Allowed
            } else {
                // Limit IS reached
                Log.w(TAG, "Same trusted day. Count ($generationCount) at or over limit ($FREE_TIER_LIMIT).")
                return false // Denied
            }
        }
    }
    // --- END FIX ---


    // ... (onStartCommand, setupVirtualDisplay, attemptCapture, captureAndProcessImage are UNCHANGED) ...

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received")

        // --- MODIFICATION: START ---
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.d(TAG, "Received STOP command from notification.")

            // Set preference to OFF
            getSharedPreferences(SlopperActivity.PREFS_NAME, MODE_PRIVATE).edit {
                putBoolean(SlopperActivity.KEY_SERVICES_ENABLED, false)
            }

            // Stop floating tool
            stopService(Intent(this, FloatingToolService::class.java))

            // Stop self
            _isServiceRunning.postValue(false)
            stopSelf()
            return START_NOT_STICKY
        }
        // --- MODIFICATION: END ---

        if (intent == null) {
            Log.w(TAG, "onStartCommand called with null intent, likely restart. Stopping.")
            _isServiceRunning.postValue(false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "ScreenCaptureService requires Overlay permission. Stopping self.")
            Toast.makeText(this, "Overlay permission required for capture session.", Toast.LENGTH_LONG).show()
            _isServiceRunning.postValue(false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "ScreenCaptureService requires Notification permission on API 33+ to run in foreground. Stopping self.")
            Toast.makeText(this, "Slopper requires Notification permission.", Toast.LENGTH_SHORT).show()
            _isServiceRunning.postValue(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Service started in foreground.")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
            Toast.makeText(this, "Could not start capture service.", Toast.LENGTH_SHORT).show()
            _isServiceRunning.postValue(false)
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)

        if (resultCode == Activity.RESULT_OK && data != null) {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            if (mediaProjection != null) {
                Log.w(TAG, "MediaProjection already exists, stopping old one.")
                mediaProjection?.stop()
            }
            mediaProjection = try {
                mediaProjectionManager.getMediaProjection(resultCode, data)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get MediaProjection", e)
                null
            }

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to get MediaProjection instance.")
                Toast.makeText(this, "Failed to initialize screen capture.", Toast.LENGTH_SHORT).show()
                _isServiceRunning.postValue(false)
                stopSelf()
                return START_NOT_STICKY
            }
            mediaProjection?.registerCallback(projectionCallback, handler)
            Log.d(TAG, "MediaProjection obtained and callback registered.")
            setupVirtualDisplay()
        } else {
            Log.e(TAG, "Screen capture intent data invalid (resultCode=$resultCode, data==null is ${data==null}).")
            Toast.makeText(this, "Failed to initialize screen capture.", Toast.LENGTH_SHORT).show()
            _isServiceRunning.postValue(false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        // --- MODIFICATION: START ---
        if (mediaProjection == null) {
            Log.w(TAG, "setupVirtualDisplay called but mediaProjection is null. Aborting.")
            // This can happen if config change receiver fires before projection is ready
            return
        }
        // --- MODIFICATION: END ---

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        Log.d(TAG, "setupVirtualDisplay: New dimensions $screenWidth x $screenHeight") // Added log
        try {
            imageReader?.close()
            virtualDisplay?.release()

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", screenWidth, screenHeight, displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, handler
            )
            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create VirtualDisplay.")
                Toast.makeText(this, "Failed to start screen capture display.", Toast.LENGTH_SHORT).show()
                _isServiceRunning.postValue(false)
                stopSelf()
            } else {
                Log.d(TAG, "VirtualDisplay created successfully.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up VirtualDisplay or ImageReader", e)
            Toast.makeText(this, "Error starting screen capture display.", Toast.LENGTH_SHORT).show()
            _isServiceRunning.postValue(false)
            stopSelf()
        }
    }

    private fun attemptCapture(captureRect: Rect?) {
        Log.d(TAG, "Attempting capture...")
        val currentImageReader = imageReader
        if (currentImageReader == null || virtualDisplay == null || mediaProjection == null) {
            Log.e(TAG, "Cannot capture, service state invalid (reader=$currentImageReader, display=$virtualDisplay, projection=$projectionCallback).")
            broadcastCompletion(null)
            return
        }

        val image: Image? = try {
            currentImageReader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to acquire image, reader might be closed.", e)
            null
        }

        if (image == null) {
            captureAttempts++
            Log.w(TAG, "Failed to acquire image (attempt $captureAttempts). Retrying...")
            if (captureAttempts < 5) handler.postDelayed(
                { attemptCapture(captureRect) },
                200L * captureAttempts
            ) else {
                Log.e(TAG, "Failed to acquire image after multiple attempts.")
                Toast.makeText(this, "Capture failed. Try again.", Toast.LENGTH_SHORT).show()
                broadcastCompletion(null)
            }
            return
        }
        Log.d(TAG, "Image acquired successfully.")
        try {
            captureAndProcessImage(image, captureRect)
        } finally {
            try { image.close() } catch (e: Exception) { Log.e(TAG, "Error closing image", e) }
        }
    }

    private fun captureAndProcessImage(image: Image, captureRect: Rect?) {
        val fullBitmap: Bitmap? = try {
            imageToBitmap(image)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert Image to Bitmap", e)
            null
        }

        if (fullBitmap == null) {
            broadcastCompletion(null)
            return
        }

        val bitmapToProcess: Bitmap
        try {
            if (captureRect != null && captureRect.width() > 0 && captureRect.height() > 0) {
                val finalRect = Rect(
                    maxOf(0, captureRect.left),
                    maxOf(0, captureRect.top),
                    minOf(fullBitmap.width, captureRect.right),
                    minOf(fullBitmap.height, captureRect.bottom)
                )
                if (finalRect.width() <= 0 || finalRect.height() <= 0) {
                    Log.e(TAG, "Invalid capture rectangle dimensions after clamping: ${finalRect}")
                    if (!fullBitmap.isRecycled) fullBitmap.recycle()
                    broadcastCompletion(null)
                    return
                }
                Log.d(TAG, "Cropping bitmap to rect: $finalRect")
                bitmapToProcess = Bitmap.createBitmap(fullBitmap, finalRect.left, finalRect.top, finalRect.width(), finalRect.height())
                if (!fullBitmap.isRecycled) fullBitmap.recycle()
            } else {
                Log.d(TAG, "Processing full bitmap.")
                bitmapToProcess = fullBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap", e);
            if (!fullBitmap.isRecycled) fullBitmap.recycle();
            broadcastCompletion(null)
            return
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmapToProcess, 0))
            .addOnSuccessListener { visionText ->
                lastRecognizedText = visionText.text
                Log.d(TAG, "Text recognition successful. Caching text: '${lastRecognizedText?.take(50)}...'")
                broadcastStart()
                regenerateReply()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Text recognition failed", e)
                broadcastCompletion(null)
            }
            .addOnCompleteListener {
                Log.d(TAG, "Text recognition complete. Recycling bitmap.")
                if (!bitmapToProcess.isRecycled) {
                    bitmapToProcess.recycle()
                }
            }
    }

    // --- Regenerate Reply is UNCHANGED, as the check happens in the commandReceiver ---
    private fun regenerateReply() {
        val textToProcess = lastRecognizedText
        if (textToProcess == null) {
            Log.e(TAG, "Regeneration failed: cached text is null.")
            handler.post { broadcastCompletion(null) }
            return
        }

        val prefs = getSharedPreferences(SlopperActivity.PREFS_NAME, MODE_PRIVATE)
        val styleName = prefs.getString(FloatingToolService.KEY_SELECTED_STYLE, "POST")!!
        val replyType = prefs.getString(FloatingToolService.KEY_LAST_REPLY_TYPE, "Answer")!!

        executor.execute {
            val reply = generateSingleReply(textToProcess, styleName, replyType)
            handler.post { broadcastCompletion(reply) }
        }
    }

    private fun broadcastStart() {
        Log.d(TAG, "Broadcasting GENERATION_STARTED")
        sendBroadcast(Intent(FloatingToolService.ACTION_GENERATION_STARTED))
    }

    // --- MODIFIED: Allow passing an error message ---
    private fun broadcastCompletion(reply: String?, error: String? = null) {
        Log.d(TAG, "Broadcasting GENERATION_COMPLETE. Reply: ${reply?.take(50)}...")
        val completeIntent = Intent(FloatingToolService.ACTION_GENERATION_COMPLETE).apply {
            putExtra(EXTRA_SINGLE_REPLY, reply)
            putExtra(EXTRA_ERROR_MESSAGE, error) // Pass the error
        }
        sendBroadcast(completeIntent)
    }

    private fun generateSingleReply(contextText: String, styleName: String, replyTone: String, retryCount: Int = 0): String? {
        if (contextText.isBlank() && replyTone != "Post") {
            Log.w(TAG, "Context is blank and reply type is not 'Post', skipping generation.")
            return null
        }

        try {
            // --- MODIFIED: Use new model path logic from singleton ---
            val model = getCurrentModel()

            // This is a BLOCKING call, which is OK because we are on a background thread
            val modelPath = ModelDownloadManager.getAbsoluteModelPath(model)

            // --- ADDED: Load LLM parameters from prefs ---
            val prefs = getSharedPreferences(SlopperActivity.PREFS_NAME, MODE_PRIVATE)
            val topK = prefs.getInt(getTopKKey(styleName), 65)
            val finalTemperature = prefs.getFloat(getTemperatureKey(styleName), 1.0f)

            if (modelPath == null) {
                Log.e(TAG, "Model path is null, cannot generate reply.")
                handler.post {
                    Toast.makeText(applicationContext, "Model '${model.displayName}' is not downloaded.", Toast.LENGTH_SHORT).show()
                }
                return null
            }
            // --- END MODIFICATION ---

            val prompt = generatePrompt(
                applicationContext,
                styleName,
                replyTone,
                contextText.replace("\"", "'")
            )

            val llmOptions = LlmInference.LlmInferenceOptions.builder().setModelPath(modelPath).build()
            llmOptions.maxTopK()
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(topK)
                .setTemperature(finalTemperature)
                .setRandomSeed((System.nanoTime() % Int.MAX_VALUE).toInt())
                .build()

            Log.d(TAG, "Generating response for style: $styleName, replyType: $replyTone with temp=$finalTemperature, topK=$topK, prompt:\n$prompt")

            LlmInference.createFromOptions(applicationContext, llmOptions).use { llmInference ->
                LlmInferenceSession.createFromOptions(llmInference, sessionOptions).use { session ->
                    session.addQueryChunk(prompt)
                    val rawResponse = session.generateResponse()
                    val responseToProcess = if (styleName == "POST" || styleName == "CHAT") rawResponse.lowercase() else rawResponse

                    if (responseToProcess.isNullOrBlank()) {
                        Log.w(TAG, "LLM returned an empty or blank response for replyType: $replyTone")
                        return null
                    }
                    Log.d(TAG, "Raw LLM Response: '$responseToProcess'")

                    val cleanedReply = responseToProcess
                        .trim()
                        .lines()
                        .firstOrNull { it.isNotBlank() }
                        ?.replace(Regex("^\\d+\\.?\\s*"), "")
                        ?.replace(Regex("\\s*\\(.*?\\)$"), "")
                        ?.replace(Regex("^\"?(.*?)\"?$"), "$1")
                        ?.replace(Regex("^'(.*?)'$"), "$1")
                        ?.replace(Regex("^`?(.*?)`?$"), "$1")
                        ?.replace(Regex("^\\*?(.*?)\\*?$"), "$1")
                        ?.replace("\\n", " ")
                        ?.replace("\\r", "")
                        ?.replace("\\t", " ")
                        ?.replace("\n", " ")
                        ?.replace("\r", "")
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()

                    if (cleanedReply.isNullOrBlank() && retryCount < 2) {
                        Log.w(TAG, "Parsing resulted in empty string, retrying... (Attempt ${retryCount + 1}), Raw: $rawResponse")
                        Thread.sleep(200L * (retryCount + 1))
                        return generateSingleReply(contextText, styleName, replyTone, retryCount + 1)
                    }

                    Log.i(TAG, "Generated and parsed reply for [$replyTone]: '$cleanedReply'")
                    return cleanedReply
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate reply", e)
            return null
        }
    }


    // --- UNCHANGED: Still need this to know *which* model to ask for ---
    private fun getCurrentModel(): Model {
        val sharedPrefs = getSharedPreferences(SlopperActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedModelName = sharedPrefs.getString(
            SlopperActivity.KEY_SELECTED_MODEL,
            Model.GEMMA3_1B_IT.name
        )
        return try {
            Model.valueOf(savedModelName ?: Model.GEMMA3_1B_IT.name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid model name '$savedModelName' found in prefs, using default.")
            Model.GEMMA3_1B_IT
        }
    }

    // --- REMOVED: getModelPath() ---
    // This logic is now in ModelDownloadManager.getAbsoluteModelPath()

    // ... (imageToBitmap, createNotificationChannel, createNotification, onBind, onDestroy are all UNCHANGED) ...
    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        if (planes.isEmpty()) {
            throw IllegalStateException("Image has no planes")
        }
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) {
            throw IllegalStateException("Invalid image strides: pixelStride=$pixelStride, rowStride=$rowStride")
        }

        val rowPadding = rowStride - pixelStride * image.width
        val width = if (pixelStride > 0) image.width + rowPadding / pixelStride else image.width
        if (width <= 0 || image.height <= 0) {
            throw IllegalStateException("Invalid image dimensions derived from strides: width=$width, height=${image.height}")
        }

        val bitmap = createBitmap(width, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        if (width > image.width) {
            try {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                return croppedBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Error cropping bitmap padding", e)
                return bitmap
            }
        }
        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notification channel for Slopper screen capture service"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel) ?: Log.e(TAG, "NotificationManager not found.")
            Log.d(TAG, "Notification channel created (or already exists).")
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, SlopperActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        // --- MODIFICATION: START ---
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1, // Use a different request code
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.outline_pause_circle_24, // Assumes this drawable exists
            "Stop Session",
            stopPendingIntent
        ).build()
        // --- MODIFICATION: END ---

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Slopper Capture Active")
            .setContentText("Tap the floating bubble to select text.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(stopAction) // <-- MODIFICATION: ADDED
            .build()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        _isServiceRunning.postValue(false)

        handler.removeCallbacks(permissionCheckRunnable)

        if (::commandReceiver.isInitialized) {
            try {
                unregisterReceiver(commandReceiver)
                Log.d(TAG, "Command receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered or already unregistered?")
            }
        }

        // --- MODIFICATION: START ---
        if (configChangeReceiver != null) {
            try {
                unregisterReceiver(configChangeReceiver)
                Log.d(TAG, "ConfigChange receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "ConfigChange receiver not registered?")
            }
        }
        // --- MODIFICATION: END ---

        try { virtualDisplay?.release() } catch (e: Exception) { Log.e(TAG, "Error releasing VirtualDisplay", e) }
        virtualDisplay = null
        try { imageReader?.close() } catch (e: Exception) { Log.e(TAG, "Error closing ImageReader", e) }
        imageReader = null
        try { mediaProjection?.unregisterCallback(projectionCallback) } catch (e: Exception) { Log.e(TAG, "Error unregistering MediaProjection callback", e) }
        try { mediaProjection?.stop() } catch (e: Exception) { Log.e(TAG, "Error stopping MediaProjection", e) }
        mediaProjection = null

        Log.d(TAG, "ScreenCaptureService resources released.")
    }
}