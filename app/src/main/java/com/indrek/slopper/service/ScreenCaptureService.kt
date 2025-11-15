package com.indrek.slopper.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.indrek.slopper.C.PREFS_NAME
import com.indrek.slopper.C.PREMIUM_ENTITLEMENT_ID
import com.indrek.slopper.domain.model.Model
import com.indrek.slopper.data.ModelDownloadManager
import com.indrek.slopper.R
import com.indrek.slopper.ui.SlopperActivity
import com.indrek.slopper.data.ServiceStateRepository
import com.indrek.slopper.data.UserLimitRepository
import com.indrek.slopper.domain.usecase.GeneratePromptUseCase
import com.indrek.slopper.domain.model.getTemperatureKey
import com.indrek.slopper.domain.model.getTopKKey
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.VerificationResult
import com.revenuecat.purchases.getCustomerInfoWith
import dagger.hilt.android.AndroidEntryPoint
import java.lang.IllegalStateException
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var commandReceiver: BroadcastReceiver
    private var captureAttempts = 0

    private var lastRecognizedText: String? = null

    private val executor = Executors.newSingleThreadExecutor()

    @Inject
    lateinit var userLimitRepo: UserLimitRepository
    @Inject
    lateinit var serviceStateRepo: ServiceStateRepository

    @Inject lateinit var generatePromptUseCase: GeneratePromptUseCase

    private val permissionCheckRunnable = object : Runnable {
        override fun run() {
            if (!Settings.canDrawOverlays(this@ScreenCaptureService)) {
                Log.w(TAG, "Overlay permission revoked while service running. Stopping service.")
                Toast.makeText(this@ScreenCaptureService, "Overlay permission revoked. Stopping capture session.", Toast.LENGTH_LONG).show()
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().apply {
                    putBoolean(SlopperActivity.KEY_SERVICES_ENABLED, false)
                    apply()
                }
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
            stopSelf()
        }
    }

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
        const val EXTRA_ERROR_MESSAGE = "extra_error_message"

        fun getStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        serviceStateRepo.setCaptureServiceState(true)
        createNotificationChannel()

        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CAPTURE_AND_RECOGNIZE) {
                    checkLimitAndProceedSecure(
                        onSuccess = {
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
                            handler.post {
                                Toast.makeText(this@ScreenCaptureService, errorMsg, Toast.LENGTH_LONG).show()
                            }
                            broadcastCompletion(null, errorMsg)
                        }
                    )
                }
            }
        }
        val receiverFlags = RECEIVER_EXPORTED
        try {
            registerReceiver(commandReceiver, IntentFilter(ACTION_CAPTURE_AND_RECOGNIZE), null, handler, receiverFlags)
            Log.d(TAG, "Command receiver registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering receiver", e)
            stopSelf()
        }

        handler.postDelayed(permissionCheckRunnable, 2000)
    }

    private fun checkLimitAndProceedSecure(onSuccess: () -> Unit, onFailure: (String) -> Unit) {
        Purchases.sharedInstance.getCustomerInfoWith(
            onSuccess = { customerInfo ->
                val entitlement = customerInfo.entitlements[PREMIUM_ENTITLEMENT_ID]
                val isActive = entitlement?.isActive == true
                val isVerified = customerInfo.entitlements.verification != VerificationResult.FAILED
                val isPremium = isActive && isVerified

                if (isPremium) {
                    Log.d(TAG, "Premium user confirmed. Proceeding.")
                    onSuccess()
                    return@getCustomerInfoWith
                }

                if (userLimitRepo.canIncrementGeneration()) {
                    Log.d(TAG, "Free user limit OK. Proceeding.")
                    onSuccess()
                } else {
                    Log.w(TAG, "Free user limit REACHED or time untrusted. Denying.")
                    onFailure("Daily free limit reached. Upgrade for more.")
                }
            },
            onError = {
                Log.e(TAG, "Could not verify subscription status. Denying request. ${it.message}")
                onFailure("Could not verify subscription. Please check connection.")
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")

        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i(TAG, "Received stop command")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)

        if (resultCode == null || resultCode != Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Invalid intent data for screen capture")
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Initialize media projection
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(projectionCallback, handler)

        // --- ADD THIS INSTEAD ---
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density: Int
        val width: Int
        val height: Int

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Modern way (API 30+)
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            width = bounds.width()
            height = bounds.height()
            // We can still get density from resources, it's usually reliable
            density = resources.displayMetrics.densityDpi
        } else {
            // Legacy way (API < 30)
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(metrics) // getRealMetrics() gets the physical size
            width = metrics.widthPixels
            height = metrics.heightPixels
            density = metrics.densityDpi
        }
// --- END OF REPLACEMENT ---

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )

        Log.d(TAG, "Virtual display created: ${width}x${height} @${density}dpi")

        // *** ADD THIS LINE: Start FloatingToolService now that we're ready ***
        startFloatingToolIfNeeded()

        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        if (mediaProjection == null) {
            Log.w(TAG, "setupVirtualDisplay called but mediaProjection is null. Aborting.")
            return
        }

        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        Log.d(TAG, "setupVirtualDisplay: New dimensions $screenWidth x $screenHeight")
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
                stopSelf()
            } else {
                Log.d(TAG, "VirtualDisplay created successfully.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up VirtualDisplay or ImageReader", e)
            Toast.makeText(this, "Error starting screen capture display.", Toast.LENGTH_SHORT).show()
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

    private fun regenerateReply() {
        val textToProcess = lastRecognizedText
        if (textToProcess == null) {
            Log.e(TAG, "Regeneration failed: cached text is null.")
            handler.post { broadcastCompletion(null) }
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
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

    private fun broadcastCompletion(reply: String?, error: String? = null) {
        Log.d(TAG, "Broadcasting GENERATION_COMPLETE. Reply: ${reply?.take(50)}...")
        val completeIntent = Intent(FloatingToolService.ACTION_GENERATION_COMPLETE).apply {
            putExtra(EXTRA_SINGLE_REPLY, reply)
            putExtra(EXTRA_ERROR_MESSAGE, error)
        }
        sendBroadcast(completeIntent)
    }

    private fun generateSingleReply(contextText: String, styleName: String, replyTone: String, retryCount: Int = 0): String? {
        if (contextText.isBlank() && replyTone != "Post") {
            Log.w(TAG, "Context is blank and reply type is not 'Post', skipping generation.")
            return null
        }

        try {
            val model = getCurrentModel()
            val modelPath = ModelDownloadManager.getAbsoluteModelPath(model)

            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val topK = prefs.getInt(getTopKKey(styleName), 65)
            val finalTemperature = prefs.getFloat(getTemperatureKey(styleName), 1.0f)

            if (modelPath == null) {
                Log.e(TAG, "Model path is null, cannot generate reply.")
                handler.post {
                    Toast.makeText(applicationContext, "Model '${model.displayName}' is not downloaded.", Toast.LENGTH_SHORT).show()
                }
                return null
            }

            val prompt = generatePromptUseCase(
                categoryName = styleName,
                actionOrTone = replyTone,
                contextText = contextText.replace("\"", "'")
            )


            val llmOptions = LlmInference.LlmInferenceOptions.builder().setModelPath(modelPath).build()
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


    private fun getCurrentModel(): Model {
        val sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, SlopperActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = NotificationCompat.Action.Builder(
            R.drawable.outline_pause_circle_24,
            "Stop Session",
            stopPendingIntent
        ).build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Slopper Capture Active")
            .setContentText("Tap the floating bubble to select text.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(stopAction)
            .build()
    }

    private fun startFloatingToolIfNeeded() {
        if (Settings.canDrawOverlays(this)) {
            Log.d(TAG, "ScreenCaptureService fully initialized, starting FloatingToolService")
            // Small delay to ensure everything is ready
            handler.postDelayed({
                try {
                    startService(Intent(this, FloatingToolService::class.java))
                    Log.d(TAG, "FloatingToolService start command sent successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start FloatingToolService from ScreenCaptureService", e)
                }
            }, 100)
        } else {
            Log.w(TAG, "Cannot start FloatingToolService: overlay permission missing")
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        serviceStateRepo.setCaptureServiceState(false)

        handler.removeCallbacks(permissionCheckRunnable)

        if (::commandReceiver.isInitialized) {
            try {
                unregisterReceiver(commandReceiver)
                Log.d(TAG, "Command receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered or already unregistered?")
            }
        }

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