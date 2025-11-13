package com.indrek.slopper.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.edit
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.slider.Slider
import com.indrek.slopper.C.PREFS_NAME
import com.indrek.slopper.C.PREMIUM_ENTITLEMENT_ID
import com.indrek.slopper.R
import com.indrek.slopper.data.PromptRepository
import com.indrek.slopper.data.ServiceStateRepository
import com.indrek.slopper.data.SessionStateRepository
import com.indrek.slopper.data.UserLimitRepository
import com.indrek.slopper.domain.model.getTemperatureKey
import com.indrek.slopper.domain.model.getTopKKey
import com.indrek.slopper.ui.SelectionView
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.VerificationResult
import com.revenuecat.purchases.getCustomerInfoWith
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class FloatingToolService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var selectionLayout: ViewGroup? = null
    private var resultLayout: View? = null
    private var selectionView: SelectionView? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var themedContext: Context
    private lateinit var inflater: LayoutInflater

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private var bubbleIcon: ImageView? = null
    private var bubbleProgress: CircularProgressIndicator? = null

    private var lastUsedReplyType: String? = null

    private var isBubbleVisible = false
    private var isSelectionVisible = false
    private var isResultVisible = false
    private var lastResultReply: String? = null

    private var activeCategories: List<String> = emptyList()
    private var currentCategoryIndex: Int = 0
    private var categoryAdapter: CategoryAdapter? = null
    private var categoryLayoutManager: LinearLayoutManager? = null
    private val categorySnapHelper = PagerSnapHelper()

    private var currentUiMode: Int = 0

    @Inject
    lateinit var userLimitRepo: UserLimitRepository
    @Inject
    lateinit var serviceStateRepo: ServiceStateRepository

    @Inject
    lateinit var sessionStateRepo: SessionStateRepository

    @Inject
    lateinit var promptRepository: PromptRepository

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val generationStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_GENERATION_STARTED -> {
                    showLoadingState()
                }
                ACTION_GENERATION_COMPLETE -> {
                    showIdleState()
                    val reply = intent.getStringExtra(ScreenCaptureService.EXTRA_SINGLE_REPLY)
                    if (!reply.isNullOrBlank()) {
                        showResultOverlay(reply)
                    } else {
                        val error = intent.getStringExtra(ScreenCaptureService.EXTRA_ERROR_MESSAGE)
                        val toastMessage = error ?: "Failed to generate reply."
                        Toast.makeText(applicationContext, toastMessage, Toast.LENGTH_SHORT).show()

                        resultLayout?.let {
                            val shimmerLayout = it.findViewById<ShimmerFrameLayout>(R.id.shimmer_layout)
                            val replyTextView = it.findViewById<TextView>(R.id.text_reply)
                            val btnRegenerate = it.findViewById<ImageButton>(R.id.btn_regenerate)
                            val btnCopy = it.findViewById<ImageButton>(R.id.btn_copy)

                            shimmerLayout.stopShimmer()
                            shimmerLayout.hideShimmer()
                            replyTextView.alpha = 1f
                            replyTextView.text = "(Generation failed)"
                            btnRegenerate.isEnabled = true
                            btnCopy.isEnabled = false
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val TAG = "FloatingToolService"
        const val EXTRA_CAPTURE_RECT = "capture_rect"
        const val ACTION_GENERATION_STARTED = "com.indrek.slopper.GENERATION_STARTED"
        const val ACTION_GENERATION_COMPLETE = "com.indrek.slopper.GENERATION_COMPLETE"
        const val KEY_RESULT_Y_POS = "result_y_pos"
        const val KEY_SELECTED_STYLE = "selected_style"
        const val KEY_LAST_REPLY_TYPE = "last_reply_type"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        serviceStateRepo.setFloatingToolState(true)

        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "FloatingToolService started without Overlay permission! Stopping self.")
            stopSelf()
            return
        }

        serviceStateRepo.isCaptureServiceRunning
            .onEach { isCaptureRunning ->
                if (!isCaptureRunning) {
                    Log.w(TAG, "ScreenCaptureService stopped. Stopping FloatingToolService.")
                    stopSelf()
                }
            }
            .launchIn(serviceScope)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createThemedContext(resources.configuration)
        currentUiMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        val intentFilter = IntentFilter().apply {
            addAction(ACTION_GENERATION_STARTED)
            addAction(ACTION_GENERATION_COMPLETE)
        }
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        try {
            registerReceiver(generationStateReceiver, intentFilter, null, handler, receiverFlags)
            Log.d(TAG, "Generation state receiver registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering generation state receiver", e)
            stopSelf()
            return
        }

        createBubbleView()
    }

    private fun createThemedContext(config: Configuration) {
        val baseContextWithConfig = createConfigurationContext(config)
        themedContext = ContextThemeWrapper(baseContextWithConfig, R.style.Theme_Slopper)
        inflater = LayoutInflater.from(themedContext)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed.")
        val newUiMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (currentUiMode != newUiMode) {
            Log.d(TAG, "Dark mode changed. Re-creating views...")
            currentUiMode = newUiMode

            val wasSelectionVisible = isSelectionVisible
            val wasResultVisible = isResultVisible
            val currentLastReply = lastResultReply

            removeAllViews()
            createThemedContext(newConfig)

            if (wasSelectionVisible) {
                expandToSelectionView()
            } else if (wasResultVisible && currentLastReply != null) {
                showResultOverlay(currentLastReply)
            } else {
                createBubbleView()
            }
        }
    }

    private fun removeAllViews() {
        Handler(Looper.getMainLooper()).post {
            if (bubbleView != null) {
                try { windowManager.removeViewImmediate(bubbleView) } catch (e: Exception) { Log.w(TAG, "Error removing bubble view", e) }
                bubbleView = null
            }
            if (selectionLayout != null) {
                try { windowManager.removeViewImmediate(selectionLayout) } catch (e: Exception) { Log.w(TAG, "Error removing selection view", e)}
                selectionLayout = null
                selectionView = null
            }
            if (resultLayout != null) {
                try { windowManager.removeViewImmediate(resultLayout) } catch (e: Exception) { Log.w(TAG, "Error removing result view", e) }
                resultLayout = null
            }
            isBubbleVisible = false
            isSelectionVisible = false
            isResultVisible = false
            lastResultReply = null
            Log.d(TAG, "All views removed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received")
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "Overlay permission revoked! Stopping service.")
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
        Handler(Looper.getMainLooper()).post {
            if (bubbleView != null || !Settings.canDrawOverlays(this)) {
                if (bubbleView != null) Log.w(TAG, "createBubbleView called but view already exists.")
                if (!Settings.canDrawOverlays(this)) Log.e(TAG, "createBubbleView called but Overlay permission missing.")
                return@post
            }

            bubbleView = inflater.inflate(R.layout.floating_bubble, null)
            Log.d(TAG, "Bubble view created")
            isBubbleVisible = true

            bubbleIcon = bubbleView?.findViewById(R.id.bubble_icon)
            bubbleProgress = bubbleView?.findViewById(R.id.bubble_progress)

            showIdleState()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER or Gravity.END; x = 0; y = 0 }

            bubbleView?.setOnTouchListener(object : View.OnTouchListener {
                private var isClick = true; private val clickThreshold = 10f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    val currentParams = bubbleView?.layoutParams as? WindowManager.LayoutParams ?: return false

                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = currentParams.x; initialY = currentParams.y; initialTouchX = event.rawX; initialTouchY = event.rawY; isClick = true; return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (isClick && (kotlin.math.abs(event.rawX - initialTouchX) > clickThreshold || kotlin.math.abs(event.rawY - initialTouchY) > clickThreshold)) isClick = false
                            currentParams.x = initialX + (event.rawX - initialTouchX).toInt(); currentParams.y = initialY + (event.rawY - initialTouchY).toInt()
                            try{ windowManager.updateViewLayout(bubbleView, currentParams) }
                            catch (e: IllegalArgumentException) { Log.e(TAG, "Error updating bubble layout (move), view might be gone.", e); return false }
                            catch (e: Exception) { Log.e(TAG, "Unexpected error updating bubble layout", e) }
                            return true
                        }
                        MotionEvent.ACTION_UP -> { if (isClick) v.performClick(); return true }
                    }
                    return false
                }
            })
            bubbleView?.setOnClickListener { expandToSelectionView() }
            try {
                windowManager.addView(bubbleView, params)
                Log.d(TAG, "Bubble view added to window manager")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to add bubble view: View already added?", e)
                if (bubbleView != null && !isBubbleVisible) {
                    bubbleView?.visibility = View.VISIBLE
                    isBubbleVisible = true
                }
            }
            catch (e: Exception) {
                Log.e(TAG, "Failed to add bubble view", e)
                bubbleView = null
                isBubbleVisible = false
                stopSelf()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun expandToSelectionView() {
        Handler(Looper.getMainLooper()).post {
            showIdleState()
            bubbleView?.visibility = View.GONE
            isBubbleVisible = false
            Log.d(TAG, "Bubble view hidden for selection")

            if (selectionLayout != null) {
                Log.w(TAG, "Attempted to expand when selection layout already exists.")
                return@post
            }

            try {
                selectionLayout = inflater.inflate(R.layout.selection_overlay, null) as ViewGroup
                isSelectionVisible = true
                Log.d(TAG, "Selection layout created")

                selectionView = selectionLayout!!.findViewById(R.id.selection_view)
                selectionLayout!!.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener { collapseToBubble() }

                // --- SESSION SWITCH LOGIC REMOVED ---

                // --- LIMIT STATUS LOGIC REMOVED ---

                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                val labelTemp = selectionLayout!!.findViewById<TextView>(R.id.label_temperature_overlay)
                val sliderTemp = selectionLayout!!.findViewById<Slider>(R.id.slider_temperature_overlay)
                val labelTopK = selectionLayout!!.findViewById<TextView>(R.id.label_top_k_overlay)
                val sliderTopK = selectionLayout!!.findViewById<Slider>(R.id.slider_top_k_overlay)

                val updateCategoryView = { styleName: String ->
                    updateActionButtons(styleName)

                    val topK = prefs.getInt(getTopKKey(styleName), 65)
                    val temp = prefs.getFloat(getTemperatureKey(styleName), 1.0f)

                    sliderTemp.value = temp
                    sliderTopK.value = topK.toFloat()
                    labelTemp.text = String.format(Locale.US, "Creativity: %.1f", temp)
                    labelTopK.text = "Options: $topK"

                    prefs.edit { putString(KEY_SELECTED_STYLE, styleName) }
                }

                val rvCategoryReel = selectionLayout!!.findViewById<RecyclerView>(R.id.rv_category_reel)

                activeCategories = promptRepository.getActiveCategories()
                if (activeCategories.isEmpty()) {
                    Log.e(TAG, "No active categories found!")
                    activeCategories = listOf("POST") // Fallback
                }

                val savedStyleName = prefs.getString(KEY_SELECTED_STYLE, activeCategories.first()) ?: activeCategories.first()
                currentCategoryIndex = activeCategories.indexOf(savedStyleName)
                if (currentCategoryIndex == -1) {
                    currentCategoryIndex = 0
                }

                categoryAdapter = CategoryAdapter(activeCategories)
                categoryLayoutManager = LinearLayoutManager(themedContext, LinearLayoutManager.HORIZONTAL, false)
                rvCategoryReel.layoutManager = categoryLayoutManager
                rvCategoryReel.adapter = categoryAdapter

                categorySnapHelper.attachToRecyclerView(null)
                categorySnapHelper.attachToRecyclerView(rvCategoryReel)

                rvCategoryReel.scrollToPosition(currentCategoryIndex)
                updateCategoryView(activeCategories[currentCategoryIndex])

                rvCategoryReel.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        super.onScrollStateChanged(recyclerView, newState)
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            val centerView = categorySnapHelper.findSnapView(categoryLayoutManager)
                            val newIndex = centerView?.let { categoryLayoutManager?.getPosition(it) } ?: currentCategoryIndex

                            if (newIndex != currentCategoryIndex) {
                                Log.d(TAG, "Category changed to index $newIndex")
                                currentCategoryIndex = newIndex
                                val newCategory = activeCategories[currentCategoryIndex]
                                updateCategoryView(newCategory)
                            }
                        }
                    }
                })

                sliderTemp.addOnChangeListener { _, value, _ ->
                    labelTemp.text = String.format(Locale.US, "Creativity: %.1f", value)
                }
                sliderTopK.addOnChangeListener { _, value, _ ->
                    labelTopK.text = "Options: ${value.toInt()}"
                }

                sliderTemp.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {}
                    override fun onStopTrackingTouch(slider: Slider) {
                        val styleName = activeCategories[currentCategoryIndex]
                        prefs.edit { putFloat(getTemperatureKey(styleName), slider.value) }
                    }
                })
                sliderTopK.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {}
                    override fun onStopTrackingTouch(slider: Slider) {
                        val styleName = activeCategories[currentCategoryIndex]
                        prefs.edit { putInt(getTopKKey(styleName), slider.value.toInt()) }
                    }
                })

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )

                windowManager.addView(selectionLayout, params)
                Log.d(TAG, "Selection layout added to window manager")

            } catch (e: Exception) {
                Log.e(TAG, "Failed during expandToSelectionView setup or addView", e);
                if (selectionLayout != null) {
                    try { windowManager.removeViewImmediate(selectionLayout)} catch (removeEx: Exception) {/* ignore */}
                    selectionLayout = null
                }
                isSelectionVisible = false
                selectionView = null
                collapseToBubble()
            }
        }
    }

    private inner class CategoryAdapter(private val categories: List<String>) :
        RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder>() {

        inner class CategoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.tv_category_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryViewHolder {
            val view = inflater.inflate(R.layout.item_category_reel, parent, false)
            return CategoryViewHolder(view)
        }

        override fun onBindViewHolder(holder: CategoryViewHolder, position: Int) {
            holder.textView.text = categories[position].uppercase()
        }

        override fun getItemCount() = categories.size
    }

    private fun updateActionButtons(styleName: String) {
        Handler(Looper.getMainLooper()).post {
            selectionLayout?.let { layout ->
                val buttonContainer = layout.findViewById<LinearLayout>(R.id.action_buttons_container)
                buttonContainer.removeAllViews()

                val allButtonLabels = promptRepository.getActionLabels(styleName)

                for (label in allButtonLabels) {
                    try {
                        val button = inflater.inflate(R.layout.floating_action_button, buttonContainer, false) as MaterialButton
                        button.text = label
                        button.setOnClickListener {
                            triggerCapture(label)
                        }
                        buttonContainer.addView(button)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error adding action button for label: $label", e)
                    }
                }
            }
        }
    }

    private fun collapseToBubble() {
        Handler(Looper.getMainLooper()).post {
            if (selectionLayout != null) {
                val rv = selectionLayout?.findViewById<RecyclerView>(R.id.rv_category_reel)
                rv?.adapter = null
                categoryAdapter = null
                categoryLayoutManager = null

                try { windowManager.removeViewImmediate(selectionLayout) } catch (e: Exception) { Log.w(TAG, "Error removing selection view on collapse", e) }
                selectionLayout = null
                selectionView = null
                isSelectionVisible = false
                Log.d(TAG, "Selection layout removed")
            }
            hideResultOverlay()

            if (serviceStateRepo.isFloatingToolRunning.value) {
                if (bubbleView == null) {
                    createBubbleView()
                } else {
                    if (!isBubbleVisible) {
                        bubbleView?.visibility = View.VISIBLE
                        isBubbleVisible = true
                        Log.d(TAG, "Bubble view made visible on collapse")
                    }
                }
            } else {
                Log.w(TAG, "Collapse called but service is stopped, not showing bubble.")
                if (bubbleView != null) {
                    try { windowManager.removeViewImmediate(bubbleView) } catch (e: Exception) {}
                    bubbleView = null
                    isBubbleVisible = false
                }
            }
        }
    }

    private fun triggerCapture(replyType: String) {
        Purchases.sharedInstance.getCustomerInfoWith(
            onSuccess = { customerInfo ->
                val entitlement = customerInfo.entitlements[PREMIUM_ENTITLEMENT_ID]
                val isActive = entitlement?.isActive == true
                val isVerified = customerInfo.entitlements.verification != VerificationResult.FAILED
                val isPremium = isActive && isVerified

                val limitData = userLimitRepo.getLimitData()
                if (!isPremium && (!limitData.isReady || limitData.remaining <= 0)) {
                    handler.post {
                        val msg = if (!limitData.isReady) "Checking limit, please wait..." else "Daily free limit reached."
                        Toast.makeText(themedContext, msg, Toast.LENGTH_LONG).show()
                    }
                    return@getCustomerInfoWith
                }
                proceedWithCapture(replyType)
            },
            onError = {
                Log.w(TAG, "Could not get CustomerInfo for quick check. Proceeding...${it.message}")
                proceedWithCapture(replyType)
            }
        )
    }

    private fun proceedWithCapture(replyType: String) {
        val currentSelectionView = selectionView
        if (currentSelectionView == null) {
            Log.e(TAG, "triggerCapture called but selectionView is null!")
            collapseToBubble()
            return
        }

        val captureRect = currentSelectionView.getSelectionRectOnScreen()
        lastUsedReplyType = replyType

        val selectedStyleName = if (activeCategories.isNotEmpty()) {
            activeCategories[currentCategoryIndex]
        } else {
            "POST" // Fallback
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
            putString(KEY_SELECTED_STYLE, selectedStyleName)
            putString(KEY_LAST_REPLY_TYPE, replyType)
        }

        Log.d(TAG, "Triggering capture for STYLE: $selectedStyleName, REPLY_TYPE: $replyType, RECT: $captureRect")

        Handler(Looper.getMainLooper()).post {
            if (selectionLayout != null) {
                selectionLayout?.visibility = View.GONE
                isSelectionVisible = false
                Log.d(TAG, "Selection layout hidden for capture trigger")
            }
        }

        // --- This is the fix to show the loading spinner immediately ---
        collapseToBubble()
        showLoadingState()
        // --- End fix ---

        val intent = Intent(ScreenCaptureService.ACTION_CAPTURE_AND_RECOGNIZE).apply {
            putExtra(EXTRA_CAPTURE_RECT, captureRect)
            putExtra(ScreenCaptureService.EXTRA_IS_REGENERATION, false)
        }
        sendBroadcast(intent)
        // handler.postDelayed({ collapseToBubble() }, 100) // <-- This is now removed
    }

    private fun showIdleState() {
        Handler(Looper.getMainLooper()).post {
            bubbleView?.let {
                bubbleIcon?.visibility = View.VISIBLE
                bubbleProgress?.visibility = View.GONE
            }
        }
    }

    private fun showLoadingState() {
        Handler(Looper.getMainLooper()).post {
            if (bubbleView != null && isBubbleVisible) {
                bubbleIcon?.visibility = View.GONE
                bubbleProgress?.visibility = View.VISIBLE
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showResultOverlay(reply: String) {
        Handler(Looper.getMainLooper()).post {
            bubbleView?.visibility = View.GONE
            isBubbleVisible = false
            Log.d(TAG, "Bubble view hidden for result")

            isResultVisible = true
            lastResultReply = reply

            if (resultLayout == null) {
                try {
                    resultLayout = inflater.inflate(R.layout.results_overlay, null)
                    Log.d(TAG, "Result layout created")

                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val savedY = prefs.getInt(KEY_RESULT_Y_POS, 0)

                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.BOTTOM
                        y = savedY
                    }

                    windowManager.addView(resultLayout, params)
                    Log.d(TAG, "Result layout added to window manager")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create or add result overlay", e);
                    resultLayout = null
                    isResultVisible = false
                    lastResultReply = null
                    collapseToBubble()
                    return@post
                }
            } else {
                Log.d(TAG, "Result layout already exists, updating content")
            }

            val shimmerLayout = resultLayout?.findViewById<ShimmerFrameLayout>(R.id.shimmer_layout)
            val replyTextView = resultLayout?.findViewById<TextView>(R.id.text_reply)
            val toneLabel = resultLayout?.findViewById<TextView>(R.id.text_tone_label)
            val btnRegenerate = resultLayout?.findViewById<ImageButton>(R.id.btn_regenerate)
            val btnCopy = resultLayout?.findViewById<ImageButton>(R.id.btn_copy)
            val btnClose = resultLayout?.findViewById<ImageButton>(R.id.btn_close)
            val dragHandle = resultLayout?.findViewById<View>(R.id.result_drag_handle)

            val labelTempResult = resultLayout?.findViewById<TextView>(R.id.label_temperature_result)
            val sliderTempResult = resultLayout?.findViewById<Slider>(R.id.slider_temperature_result)
            val labelTopKResult = resultLayout?.findViewById<TextView>(R.id.label_top_k_result)
            val sliderTopKResult = resultLayout?.findViewById<Slider>(R.id.slider_top_k_result)
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val styleName = prefs.getString(KEY_SELECTED_STYLE, "POST")!!

            val topK = prefs.getInt(getTopKKey(styleName), 65)
            val temp = prefs.getFloat(getTemperatureKey(styleName), 1.0f)

            sliderTempResult?.value = temp
            sliderTopKResult?.value = topK.toFloat()
            labelTempResult?.text = String.format(Locale.US, "Creativity: %.1f", temp)
            labelTopKResult?.text = "Options: $topK"

            sliderTempResult?.addOnChangeListener { _, value, _ -> labelTempResult?.text = String.format(Locale.US, "Creativity: %.1f", value) }
            sliderTopKResult?.addOnChangeListener { _, value, _ -> labelTopKResult?.text = "Options: ${value.toInt()}" }

            sliderTempResult?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    prefs.edit { putFloat(getTemperatureKey(styleName), slider.value) }
                }
            })
            sliderTopKResult?.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) {
                    prefs.edit { putInt(getTopKKey(styleName), slider.value.toInt()) }
                }
            })

            shimmerLayout?.stopShimmer()
            shimmerLayout?.hideShimmer()
            replyTextView?.alpha = 1f
            replyTextView?.text = reply
            btnRegenerate?.isEnabled = true
            btnCopy?.isEnabled = true
            btnClose?.isEnabled = true

            lastUsedReplyType?.let { replyType -> toneLabel?.text = "Slopper - $replyType" }

            dragHandle?.setOnTouchListener { v, event ->
                val params = resultLayout?.layoutParams as? WindowManager.LayoutParams ?: return@setOnTouchListener false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialY = params.y
                        initialTouchY = event.rawY
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.y = initialY - (event.rawY - initialTouchY).toInt()
                        try { windowManager.updateViewLayout(resultLayout, params) }
                        catch(e: Exception) { Log.e(TAG, "Error updating result layout position", e); return@setOnTouchListener false }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP -> {
                        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit { putInt(KEY_RESULT_Y_POS, params.y) }
                        return@setOnTouchListener true
                    }
                }
                return@setOnTouchListener false
            }

            btnRegenerate?.setOnClickListener {
                Purchases.sharedInstance.getCustomerInfoWith(
                    onSuccess = { customerInfo ->
                        val entitlement = customerInfo.entitlements[PREMIUM_ENTITLEMENT_ID]
                        val isActive = entitlement?.isActive == true
                        val isVerified = customerInfo.entitlements.verification != VerificationResult.FAILED
                        val isPremium = isActive && isVerified

                        val limitData = userLimitRepo.getLimitData()
                        if (!isPremium && (!limitData.isReady || limitData.remaining <= 0)) {
                            val msg = if (!limitData.isReady) "Checking limit..." else "Daily free limit reached."
                            Toast.makeText(themedContext, msg, Toast.LENGTH_LONG).show()
                            return@getCustomerInfoWith
                        }

                        replyTextView?.alpha = 0.3f
                        shimmerLayout?.showShimmer(true)
                        btnRegenerate.isEnabled = false
                        btnCopy?.isEnabled = false

                        val intent = Intent(ScreenCaptureService.ACTION_CAPTURE_AND_RECOGNIZE).apply {
                            putExtra(ScreenCaptureService.EXTRA_IS_REGENERATION, true)
                        }
                        sendBroadcast(intent)
                    },
                    onError = {
                        replyTextView?.alpha = 0.3f
                        shimmerLayout?.showShimmer(true)
                        btnRegenerate.isEnabled = false
                        btnCopy?.isEnabled = false

                        val intent = Intent(ScreenCaptureService.ACTION_CAPTURE_AND_RECOGNIZE).apply {
                            putExtra(ScreenCaptureService.EXTRA_IS_REGENERATION, true)
                        }
                        sendBroadcast(intent)
                    }
                )
            }

            val copyAndCloseAction = {
                if (btnCopy?.isEnabled == true) {
                    copyToClipboard(reply)
                    Toast.makeText(themedContext, "Copied!", Toast.LENGTH_SHORT).show()
                    hideResultOverlay()
                    showIdleState()
                    collapseToBubble()
                }
            }

            btnCopy?.setOnClickListener { copyAndCloseAction() }
            replyTextView?.setOnClickListener { copyAndCloseAction() }

            btnClose?.setOnClickListener {
                hideResultOverlay()
                showIdleState()
                collapseToBubble()
            }
        }
    }

    private fun hideResultOverlay() {
        Handler(Looper.getMainLooper()).post {
            if (resultLayout != null) {
                try { windowManager.removeViewImmediate(resultLayout) } catch (e: Exception) { Log.w(TAG, "Error removing result view", e) }
                resultLayout = null
                Log.d(TAG, "Result layout removed")
            }
            isResultVisible = false
            lastResultReply = null
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Generated Reply", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(themedContext, "Failed to copy text.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        serviceStateRepo.setFloatingToolState(false)
        serviceScope.cancel()

        try {
            unregisterReceiver(generationStateReceiver)
            Log.d(TAG, "Generation state receiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Generation state receiver not registered or already unregistered?")
        }
        removeAllViews()
        Log.d(TAG, "FloatingToolService destroyed")
    }
}