package com.indrek.slopper.service

import android.app.*
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
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.indrek.slopper.AutofillSettingsActivity
import com.indrek.slopper.Model
import com.indrek.slopper.ModelLocation
import com.indrek.slopper.MyApplication
import com.indrek.slopper.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var commandReceiver: BroadcastReceiver
    private var captureAttempts = 0
    private var captureRect: Rect? = null
    private var captureTone: String? = null // Variable to hold the chosen tone

    // For reply generation
    private val executor = Executors.newSingleThreadExecutor()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.w(TAG, "MediaProjection was stopped. Stopping service.")
            stopSelf()
        }
    }

    companion object {
        const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_ID = 123
        const val CHANNEL_ID = "ScreenCapture"
        const val ACTION_CAPTURE_AND_RECOGNIZE = "com.indrek.slopper.ACTION_CAPTURE_AND_RECOGNIZE"
        const val EXTRA_REPLY_TONE = "extra_reply_tone" // Key for passing the tone
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        private val _isServiceRunning = MutableLiveData<Boolean>(false)
        val isServiceRunning: LiveData<Boolean> = _isServiceRunning

        fun getStartIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.postValue(true)
        createNotificationChannel()

        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CAPTURE_AND_RECOGNIZE) {
                    captureRect = intent.getParcelableExtra(FloatingToolService.EXTRA_CAPTURE_RECT)
                    captureTone = intent.getStringExtra(EXTRA_REPLY_TONE)?.lowercase()
                    Log.d(TAG, "Received capture command for TONE: $captureTone")
                    captureAttempts = 0
                    handler.postDelayed({ attemptCapture() }, 300)
                }
            }
        }
        registerReceiver(
            commandReceiver,
            IntentFilter(ACTION_CAPTURE_AND_RECOGNIZE),
            RECEIVER_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
        if (data != null) {
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(projectionCallback, handler)
            setupVirtualDisplay()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun setupVirtualDisplay() {
        val displayMetrics = Resources.getSystem().displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", screenWidth, screenHeight, displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, handler
        )
    }

    private fun attemptCapture() {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            captureAttempts++
            if (captureAttempts < 5) handler.postDelayed(
                { attemptCapture() },
                200L * captureAttempts
            )
            return
        }
        try {
            captureAndProcessImage(image)
        } finally {
            image.close()
        }
    }

    private fun captureAndProcessImage(image: Image) {
        val fullBitmap = imageToBitmap(image)
        val bitmapToProcess: Bitmap
        try {
            val rect = captureRect
            if (rect != null && rect.width() > 0 && rect.height() > 0) {
                val finalRect = Rect(
                    maxOf(0, rect.left),
                    maxOf(0, rect.top),
                    minOf(fullBitmap.width, rect.right),
                    minOf(fullBitmap.height, rect.bottom)
                )
                if (finalRect.width() <= 0 || finalRect.height() <= 0) {
                    fullBitmap.recycle(); return
                }
                bitmapToProcess = Bitmap.createBitmap(
                    fullBitmap,
                    finalRect.left,
                    finalRect.top,
                    finalRect.width(),
                    finalRect.height()
                )
                fullBitmap.recycle()
            } else {
                bitmapToProcess = fullBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap", e); fullBitmap.recycle(); return
        }

        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bitmapToProcess, 0))
            .addOnSuccessListener { visionText ->
                val tone = captureTone
                if (tone == null) {
                    Log.e(TAG, "Cannot generate reply, tone was null.")
                    broadcastCompletion(0)
                    return@addOnSuccessListener
                }

                Log.d(
                    TAG,
                    "Text recognition successful. Starting SINGLE reply generation for tone: $tone"
                )
                broadcastStart()
                executor.execute {
                    val singleReply = generateSingleReply(visionText.text, tone)
                    val replies = if (singleReply != null) listOf(singleReply) else emptyList()
                    MyApplication.cachedReplies = replies
                    // Copy to clipboard if reply was generated
                    if (singleReply != null) {
                        copyToClipboard(singleReply.second)
                    }
                    broadcastCompletion(replies.size)
                }
            }
            .addOnFailureListener { e -> Log.e(TAG, "Text recognition failed", e) }
            .addOnCompleteListener { if (!bitmapToProcess.isRecycled) bitmapToProcess.recycle() }
    }

    private fun broadcastStart() {
        sendBroadcast(Intent(FloatingToolService.ACTION_GENERATION_STARTED))
    }

    private fun broadcastCompletion(count: Int) {
        val completeIntent = Intent(FloatingToolService.ACTION_GENERATION_COMPLETE).apply {
            putExtra(FloatingToolService.EXTRA_REPLY_COUNT, count)
        }
        sendBroadcast(completeIntent)
    }

    fun generateSingleReply(contextText: String, tone: String): Pair<String, String>? {
        if (contextText.isBlank()) return null
        val bestText =
            contextText.split("\n").filter { it.trim().length > 10 }.maxByOrNull { it.length }
                ?: contextText.take(200)

        try {
            val llm = createLlmInstance() ?: return null

            val prompt = when (tone.lowercase()) {
                "answer" -> """
                        Answer this question in the range of 5–200 characters. Be direct and factual.
                        
                        Question: "$bestText"
                        
                        Rules:
                        - Start directly with the answer (no "Sure," or "Well,").
                        - No follow-up questions.
                        - No engagement or filler phrases.
                        - Just facts.
                        - Use an emoji only if it naturally fits.
                        
                        Answer:
                    """.trimIndent()

                "question" -> """
                         Ask one interesting, specific question about the topic below — as if you're a regular person
                            commenting on social media who’s genuinely curious or skeptical.
                        Context: "$bestText"
                        
                        Rules:
                        - Ask something that shows genuine curiosity or seeks clarification.
                        - Avoid generic questions like "What do you mean?" or "Can you explain?"
                        - No greetings or fluff.
                        - Keep it short (5–150 characters).
                        - You may add a fitting emoji.
                        
                        Question:
                    """.trimIndent()

                "post" -> """
                        Generate a random, engaging Twitter post (5–200 characters).
                        
                            Rules:
                            - Write as if you're a real person sharing a thought, observation, or hot take.
                            - Be witty, relatable, or thought-provoking.
                            - No promotional language or calls to action.
                            - No questions to followers.
                            - Make it standalone — it should work without any context.
                            - An emoji is fine if it fits naturally.
                            
                            Post:
                        """.trimIndent()
                else -> """
                        Write a $tone reply (in the range of 5–200 characters) to: "$bestText"
                        
                        Rules:
                        - Make ONE clear statement. No low-effort starters.
                        - No questions.
                        - No "let’s..." phrases.
                        - No calls to action.
                        - An emoji is fine if appropriate.
                        
                        Reply:
                    """.trimIndent()
            }

            Log.d(TAG, "Generating SINGLE response for tone: $tone...")

            val randomTemp = listOf(0.6f, 0.7f, 0.75f, 0.8f, 0.85f, 0.9f).random()
            val randomTopK = listOf(20, 30, 40, 50).random()

            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(randomTopK)
                .setTemperature(randomTemp)
                .setRandomSeed(System.currentTimeMillis().toInt())
                .build()

            Log.d(TAG, "Generating response for tone: $tone with temp=$randomTemp, topK=$randomTopK")

            // Use session for generation
            val session = LlmInferenceSession.createFromOptions(llm, sessionOptions)
            session.addQueryChunk(prompt)
            val rawResponse = session.generateResponse()

            // Close session and LLM
            session.close()
            llm.close()

            val cleanedResponse = rawResponse
                ?.trim()
                ?.dropWhile { !it.isLetterOrDigit() }
                ?.dropLastWhile { !it.isLetterOrDigit() }
                ?.replace("\\n", " ")
                ?.replace("\\r", "")
                ?.replace("\\t", " ")
                ?.replace("\n", " ")
                ?.replace("\r", "")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()

            return if (!cleanedResponse.isNullOrBlank()) {
                Log.i(TAG, "Generated [$tone]: '$cleanedResponse'")
                Pair(tone, cleanedResponse)
            } else {
                Log.w(TAG, "LLM returned an empty response for tone: $tone")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate single reply", e)
            return null
        }
    }

    private fun copyToClipboard(text: String) {
        handler.post {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Generated Reply", text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "Copied to clipboard: $text")
            Toast.makeText(this, "Reply copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Reads the user's selected model from SharedPreferences.
     * @return The file name of the selected model.
     */
    private fun getCurrentModelFileName(): String {
        val sharedPrefs = getSharedPreferences(AutofillSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedModelName = sharedPrefs.getString(
            AutofillSettingsActivity.KEY_SELECTED_MODEL,
            Model.GEMMA_2B.name // Default to Gemma 2B if nothing is saved
        )
        val selectedModel = try {
            Model.valueOf(savedModelName ?: Model.GEMMA_2B.name)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid model name '$savedModelName' in prefs, defaulting to GEMMA_2B.")
            Model.GEMMA_2B
        }
        Log.d(TAG, "Using model: ${selectedModel.displayName}")
        return selectedModel.fileName
    }

    private fun createLlmInstance(): LlmInference? {
        try {
            val modelFileName = getCurrentModelFileName() // Use the function to get the selected model
            val modelPath = getModelPath(modelFileName) ?: return null
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .build()
            return LlmInference.createFromOptions(applicationContext, options)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create LLM instance", e); return null
        }
    }

    private fun getModelPath(modelFileName: String): String? {
        // Get the model enum to check its location
        val model = Model.values().find { it.fileName == modelFileName }

        if (model == null) {
            Log.e(TAG, "Unknown model file: $modelFileName")
            return null
        }

        return when (model.location) {
            ModelLocation.ASSETS -> getModelPathFromAssets(modelFileName)
            ModelLocation.EXTERNAL -> getModelPathFromExternal(modelFileName)
        }
    }

    private fun getModelPathFromAssets(modelFileName: String): String? {
        // First check if already copied to internal storage
        val internalModelFile = File(filesDir, modelFileName)
        if (internalModelFile.exists()) {
            Log.d(TAG, "Using model from internal storage: ${internalModelFile.absolutePath}")
            return internalModelFile.absolutePath
        }

        // Try to copy from assets
        try {
            assets.open(modelFileName).use { input ->
                FileOutputStream(internalModelFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied model from assets: ${internalModelFile.absolutePath}")
            return internalModelFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy model from assets: $modelFileName", e)
            return null
        }
    }

    private fun getModelPathFromExternal(modelFileName: String): String? {
        // Check app's external files directory
        // Path: /storage/emulated/0/Android/data/com.indrek.slopper/files/
        val externalDir = getExternalFilesDir(null)
        if (externalDir != null) {
            val externalModelFile = File(externalDir, modelFileName)
            if (externalModelFile.exists()) {
                Log.d(TAG, "Using model from external storage: ${externalModelFile.absolutePath}")
                return externalModelFile.absolutePath
            } else {
                Log.e(TAG, "Model file not found in external storage: ${externalModelFile.absolutePath}")
                Toast.makeText(
                    this,
                    "Model '$modelFileName' not found. Please place it in: ${externalDir.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Also check internal storage as fallback
        val internalModelFile = File(filesDir, modelFileName)
        if (internalModelFile.exists()) {
            Log.d(TAG, "Found model in internal storage instead: ${internalModelFile.absolutePath}")
            return internalModelFile.absolutePath
        }

        Log.e(TAG, "Model file '$modelFileName' not found in any location")
        return null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return bitmap
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Capture Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                serviceChannel
            )
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Slopper Analysis Session")
            .setContentText("Service is active. Tap the bubble to capture.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.postValue(false)
        if (::commandReceiver.isInitialized) unregisterReceiver(commandReceiver)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        Log.d(TAG, "ScreenCaptureService destroyed.")
    }
}