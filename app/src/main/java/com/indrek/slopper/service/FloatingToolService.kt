package com.indrek.slopper.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.indrek.slopper.AutofillSettingsActivity
import com.indrek.slopper.R

class FloatingToolService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var selectionLayout: FrameLayout? = null
    private lateinit var selectionView: SelectionView
    private val handler = Handler(Looper.getMainLooper())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var bubbleIcon: ImageView? = null
    private var bubbleProgress: ProgressBar? = null
    private var bubbleSuccessLayout: LinearLayout? = null

    private val generationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_GENERATION_STARTED -> showLoadingState()
                ACTION_GENERATION_COMPLETE -> {
                    showSuccessState()
                }
            }
        }
    }

    companion object {
        const val TAG = "FloatingToolService"
        const val EXTRA_CAPTURE_RECT = "capture_rect"
        const val ACTION_GENERATION_STARTED = "com.indrek.slopper.GENERATION_STARTED"
        const val ACTION_GENERATION_COMPLETE = "com.indrek.slopper.GENERATION_COMPLETE"
        const val EXTRA_REPLY_COUNT = "extra_reply_count"

        // --- KEY CHANGE 1: Add LiveData for state reporting ---
        private val _isServiceRunning = MutableLiveData<Boolean>(false)
        val isServiceRunning: LiveData<Boolean> = _isServiceRunning
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // --- KEY CHANGE 2: Report that the service is now running ---
        _isServiceRunning.postValue(true)
        Log.d(TAG, "onCreate: Service is running, posting true to LiveData.")

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_GENERATION_STARTED)
            addAction(ACTION_GENERATION_COMPLETE)
        }
        registerReceiver(generationStateReceiver, intentFilter, RECEIVER_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sharedPrefs = getSharedPreferences(AutofillSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isToolEnabled = sharedPrefs.getBoolean(AutofillSettingsActivity.KEY_TOOL_ENABLED, false)

        if (!isToolEnabled) {
            Log.w(TAG, "Service started, but tool is disabled in settings. Stopping self.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (bubbleView == null) {
            createBubbleView()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        bubbleView = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)

        bubbleIcon = bubbleView?.findViewById(R.id.bubble_icon)
        bubbleProgress = bubbleView?.findViewById(R.id.bubble_progress)
        bubbleSuccessLayout = bubbleView?.findViewById(R.id.bubble_success_layout)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER or Gravity.END
            x = 0
            y = 0
        }

        bubbleView?.setOnTouchListener(object : View.OnTouchListener {
            private var isClick = true
            private val clickThreshold = 10f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isClick = true
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isClick && (kotlin.math.abs(event.rawX - initialTouchX) > clickThreshold || kotlin.math.abs(event.rawY - initialTouchY) > clickThreshold)) {
                            isClick = false
                        }
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubbleView, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (isClick) v.performClick()
                        return true
                    }
                }
                return false
            }
        })

        bubbleView?.setOnClickListener { expandToSelectionView() }

        try {
            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bubble view", e)
        }
    }

    private fun expandToSelectionView() {
        showIdleState()
        bubbleView?.visibility = View.GONE

        selectionLayout = LayoutInflater.from(this).inflate(R.layout.selection_overlay, null) as FrameLayout
        selectionView = selectionLayout!!.findViewById(R.id.selection_view)

        val btnCancel = selectionLayout!!.findViewById<Button>(R.id.btn_cancel)
        val btnPost = selectionLayout!!.findViewById<Button>(R.id.btn_post)
        val btnQuestion = selectionLayout!!.findViewById<Button>(R.id.btn_question)
        val btnAnswer = selectionLayout!!.findViewById<Button>(R.id.btn_answer)
        val btnConflicting = selectionLayout!!.findViewById<Button>(R.id.btn_conflicting)
        val btnFunny = selectionLayout!!.findViewById<Button>(R.id.btn_funny)
        val btnPositive = selectionLayout!!.findViewById<Button>(R.id.btn_positive)
        val btnSmart = selectionLayout!!.findViewById<Button>(R.id.btn_smart)
        val btnEmphatic = selectionLayout!!.findViewById<Button>(R.id.btn_emphatic)
        val btnShocked = selectionLayout!!.findViewById<Button>(R.id.btn_shocked)

        btnCancel.setOnClickListener { collapseToBubble() }
        btnPost.setOnClickListener { triggerCapture("Post") }
        btnAnswer.setOnClickListener { triggerCapture("Answer") }
        btnQuestion.setOnClickListener { triggerCapture("Question") }
        btnConflicting.setOnClickListener { triggerCapture("Conflicting") }
        btnEmphatic.setOnClickListener { triggerCapture("Emphatic") }
        btnFunny.setOnClickListener { triggerCapture("Satirical") }
        btnPositive.setOnClickListener { triggerCapture("Deep") }
        btnSmart.setOnClickListener { triggerCapture("Smart") }
        btnShocked.setOnClickListener { triggerCapture("Shocked") }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        try {
            windowManager.addView(selectionLayout, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create selection view", e)
            collapseToBubble()
        }
    }

    private fun collapseToBubble() {
        if (selectionLayout != null) {
            try { windowManager.removeView(selectionLayout) } catch (e: Exception) { /* ignore */ }
            selectionLayout = null
        }
        bubbleView?.visibility = View.VISIBLE
    }

    private fun triggerCapture(tone: String) {
        // This now checks if the capture service is running before attempting a capture.
        if (ScreenCaptureService.isServiceRunning.value != true) {
            Log.w(TAG, "Capture triggered but ScreenCaptureService is not active.")
            Toast.makeText(applicationContext, "Please start the Capture Session from the app's settings first.", Toast.LENGTH_LONG).show()
            collapseToBubble()
            return
        }

        val selectionRect = selectionView.getSelectionRectOnScreen()
        Log.d(TAG, "Triggering capture for TONE: $tone, RECT: $selectionRect")
        selectionLayout?.visibility = View.GONE

        val intent = Intent(ScreenCaptureService.ACTION_CAPTURE_AND_RECOGNIZE).apply {
            putExtra(EXTRA_CAPTURE_RECT, selectionRect)
            putExtra(ScreenCaptureService.EXTRA_REPLY_TONE, tone)
        }
        sendBroadcast(intent)

        handler.postDelayed({ collapseToBubble() }, 100)
    }

    private fun showIdleState() {
        bubbleIcon?.visibility = View.VISIBLE
        bubbleProgress?.visibility = View.GONE
        bubbleSuccessLayout?.visibility = View.GONE
    }

    private fun showLoadingState() {
        bubbleIcon?.visibility = View.GONE
        bubbleProgress?.visibility = View.VISIBLE
        bubbleSuccessLayout?.visibility = View.GONE
    }

    private fun showSuccessState() {
        bubbleIcon?.visibility = View.GONE
        bubbleProgress?.visibility = View.GONE
        bubbleSuccessLayout?.visibility = View.VISIBLE
        showOverlayToastNextToBubble("Tap a form field — or open Autofill manually.")
        handler.postDelayed({ showIdleState() }, 4000)
    }

    private fun showOverlayToastNextToBubble(message: String, duration: Long = 3000L) {
        // This method is unchanged
        val bubbleParams = (bubbleView?.layoutParams as? WindowManager.LayoutParams) ?: return

        val toastView = TextView(this).apply {
            text = message
            setTextColor(Color.BLACK)
            setBackgroundResource(R.drawable.button_container_background)
            setPadding(24, 16, 24, 16)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER or Gravity.END
            x = bubbleParams.x - 200
            y = bubbleParams.y - 50
        }

        try {
            windowManager.addView(toastView, params)

            handler.postDelayed({
                try { windowManager.removeView(toastView) } catch (_: Exception) {}
            }, duration)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay toast next to bubble", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // --- KEY CHANGE 3: Report that the service has stopped ---
        _isServiceRunning.postValue(false)
        Log.d(TAG, "onDestroy: Service is stopping, posting false to LiveData.")
        unregisterReceiver(generationStateReceiver)
        if (bubbleView != null) {
            try { windowManager.removeView(bubbleView) } catch (e: Exception) { /* ignore */ }
        }
        if (selectionLayout != null) {
            try { windowManager.removeView(selectionLayout) } catch (e: Exception) { /* ignore */ }
        }
    }
}