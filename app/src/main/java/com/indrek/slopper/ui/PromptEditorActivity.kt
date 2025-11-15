package com.indrek.slopper.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ImageSpan
import android.util.Log
// import android.util.TypedValue // No longer needed
import android.view.LayoutInflater
import android.view.View
// import android.view.ViewGroup // No longer needed
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
// import androidx.annotation.AttrRes // No longer needed
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.children
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.indrek.slopper.R
import com.indrek.slopper.data.PREF_KEY_ACTIVE_CATEGORIES
import com.indrek.slopper.data.PromptRepository
import com.indrek.slopper.databinding.ActivityPromptEditorBinding
import com.indrek.slopper.domain.model.defaultCategoryNames
import com.indrek.slopper.domain.model.getCustomActionsKey
import com.indrek.slopper.domain.model.getGenericTonesKey
import com.indrek.slopper.domain.model.getHiddenDefaultActionsKey
import com.indrek.slopper.domain.model.getHiddenDefaultTonesKey
import com.indrek.slopper.domain.model.getPromptTemplateKey
import com.indrek.slopper.domain.model.getTemperatureKey
import com.indrek.slopper.domain.model.getTopKKey
import com.revenuecat.purchases.ui.revenuecatui.ExperimentalPreviewRevenueCatUIPurchasesAPI
import com.revenuecat.purchases.ui.revenuecatui.activity.PaywallActivityLauncher
import com.revenuecat.purchases.ui.revenuecatui.activity.PaywallResult
import com.revenuecat.purchases.ui.revenuecatui.activity.PaywallResultHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import kotlin.collections.forEach


@OptIn(ExperimentalPreviewRevenueCatUIPurchasesAPI::class)
@AndroidEntryPoint
class PromptEditorActivity : AppCompatActivity(), PaywallResultHandler {

    private lateinit var binding: ActivityPromptEditorBinding

    private val viewModel: PromptEditorViewModel by viewModels()
    private lateinit var paywallActivityLauncher: PaywallActivityLauncher

    @Inject
    lateinit var prefs: SharedPreferences
    private var toolbarTitlesInitialized = false
    @Inject
    lateinit var promptRepository: PromptRepository

    private var currentCategoryName: String = "POST" // Default
    private var isPremium: Boolean = false

    private val KEY_CURRENT_CATEGORY = "KEY_CURRENT_CATEGORY"

    // --- NEW: Map to store original titles ---
    private val originalMenuItemTitles = mutableMapOf<Int, CharSequence>()
    // --- END NEW ---

    // --- NEW: Centralized overlay color ---
    /** Helper property to get the semi-transparent overlay color from one place */
    private val semiTransparentOverlayColor: Int by lazy {
        val overlayColor = ContextCompat.getColor(this, R.color.colorPrimaryContainer)
        // Apply 50% alpha (0x80)
        (overlayColor and 0x00FFFFFF) or 0x80000000.toInt()
    }
    // --- END NEW ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPromptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        paywallActivityLauncher = PaywallActivityLauncher(this, this)

        // Observe premium status
        lifecycleScope.launch {
            viewModel.isPremium.collect { premium ->
                isPremium = premium
                updatePremiumOverlays()
                updateToolbarMenuLocks(premium) // Update toolbar locks
            }
        }

        // Restore the saved category name after a theme change
        if (savedInstanceState != null) {
            currentCategoryName = savedInstanceState.getString(KEY_CURRENT_CATEGORY, "POST")
        }

        // Initialize categories if needed (via repository)
        promptRepository.getActiveCategories()

        setupToolbar()
        setupCategorySpinner()

        binding.fabAddAction.setOnClickListener {
            if (isPremium) {
                showAddActionDialog()
            } else {
                launchPaywallActivity()
            }
        }

        // Add lock overlay to FAB if not premium
        updateFabOverlay()

        setupLlmSliders()
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CURRENT_CATEGORY, currentCategoryName)
    }

    override fun onResume() {
        super.onResume()
        // Refresh premium status when returning from purchase flow
        viewModel.refreshPremiumStatus()
    }

    private fun setupLlmSliders() {
        // Temperature Slider
        binding.sliderTemperature.addOnChangeListener { _, value, _ ->
            binding.labelTemperature.text = String.format(Locale.US, "Creativity: %.1f", value)
        }
        binding.sliderTemperature.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                prefs.edit {
                    putFloat(getTemperatureKey(currentCategoryName), slider.value)
                }
                Toast.makeText(this@PromptEditorActivity, "Creativity saved", Toast.LENGTH_SHORT).show()
            }
        })

        // Top-K Slider
        binding.sliderTopK.addOnChangeListener { _, value, _ ->
            binding.labelTopK.text = "Options: ${value.toInt()}"
        }
        binding.sliderTopK.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                prefs.edit {
                    putInt(getTopKKey(currentCategoryName), slider.value.toInt())
                }
                Toast.makeText(this@PromptEditorActivity, "Options saved", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadCategoryLlmSettings() {
        val topK = prefs.getInt(getTopKKey(currentCategoryName), 65)
        val temp = prefs.getFloat(getTemperatureKey(currentCategoryName), 1.0f)

        binding.sliderTopK.value = topK.toFloat()
        binding.sliderTemperature.value = temp
        binding.labelTopK.text = "Options: $topK"
        binding.labelTemperature.text = String.format(Locale.US, "Creativity: %.1f", temp)
    }

    // --- MODIFIED: setupToolbar with premium gating ---
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        // --- Map population logic removed. It's now in updateToolbarMenuLocks ---

        binding.toolbar.setOnMenuItemClickListener { item ->
            // Check premium for all toolbar actions
            if (!isPremium) {
                launchPaywallActivity()
                return@setOnMenuItemClickListener true
            }

            when (item.itemId) {
                R.id.action_reset -> {
                    resetCurrentCategory()
                    true
                }
                R.id.action_add_category -> {
                    showAddCategoryDialog()
                    true
                }
                R.id.action_delete_category -> {
                    showDeleteCategoryDialog()
                    true
                }
                R.id.action_restore_defaults -> {
                    showRestoreDefaultsDialog()
                    true
                }
                else -> false
            }
        }
    }


    private fun setupCategorySpinner() {
        // 1. Get the fresh list of categories from repository
        val categories = promptRepository.getActiveCategories()

        // 2. Create the new adapter
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)

        // 3. Validate the restored currentCategoryName.
        if (currentCategoryName !in categories) {
            currentCategoryName = categories.first()
        }

        val autoCompleteTextView = (binding.spinnerCategorySelection as? AutoCompleteTextView)

        // 4. Clear the text that the view auto-restored
        autoCompleteTextView?.setText(null, false)

        // 5. Set the FULL adapter.
        autoCompleteTextView?.setAdapter(adapter)

        // 6. Set the correct text back.
        autoCompleteTextView?.setText(currentCategoryName, false)


        // 8. Force the adapter to clear its filter
        autoCompleteTextView?.post {
            adapter.filter.filter(null)
        }

        // 9. Set listener
        autoCompleteTextView?.onItemClickListener = null // Clear old listener
        autoCompleteTextView?.setOnItemClickListener { parent, _, position, _ ->
            val selectedItem = parent.getItemAtPosition(position) as? String ?: return@setOnItemClickListener
            currentCategoryName = selectedItem
            loadCategoryLlmSettings() // Load settings for new category
            populateEditors()
        }

        // 10. Load editors for the restored category
        loadCategoryLlmSettings()
        populateEditors()
    }

    private fun populateEditors() {
        binding.promptEditorContainer.removeAllViews()

        val definition = promptRepository.getPromptDefinition(currentCategoryName)
            ?: promptRepository.getPromptDefinition("POST")!!
        val inflater = LayoutInflater.from(this)

        val prefixKey = getPromptTemplateKey(currentCategoryName, "prefix")
        val defaultValuePrefix = definition.promptPrefix
        val savedValuePrefix = prefs.getString(prefixKey, defaultValuePrefix)
        addEditor(inflater, "Prompt Prefix", prefixKey, savedValuePrefix, defaultValuePrefix, isDeletable = false, specialColor = R.color.colorPrimaryContainer)

        val genericKey = getPromptTemplateKey(currentCategoryName, "generic")
        val defaultValueGeneric = definition.genericToneTemplate
        val savedValueGeneric = prefs.getString(genericKey, defaultValueGeneric)
        addEditor(inflater, "Generic Tone Template", genericKey, savedValueGeneric, defaultValueGeneric, isDeletable = false, specialColor = R.color.colorTertiaryContainer, isGenericToneEditor = true)

        val hiddenActionsKey = getHiddenDefaultActionsKey(currentCategoryName)
        val hiddenActions = prefs.getStringSet(hiddenActionsKey, emptySet()) ?: emptySet()
        definition.specificActions.forEach { action ->
            if (action.label in hiddenActions) return@forEach
            val key = getPromptTemplateKey(currentCategoryName, action.label)
            val defaultValue = action.template
            val savedValue = prefs.getString(key, defaultValue)
            addEditor(inflater, "Action: ${action.label}", key, savedValue, defaultValue, isDeletable = false)
        }

        val customActionsKey = getCustomActionsKey(currentCategoryName)
        val customActions = prefs.getStringSet(customActionsKey, emptySet()) ?: emptySet()
        customActions.sorted().forEach { actionLabel ->
            val key = getPromptTemplateKey(currentCategoryName, actionLabel)
            val savedValue = prefs.getString(key, "")
            addEditor(inflater, "Action: $actionLabel", key, savedValue, null, isDeletable = true)
        }
    }

    // --- addEditor MODIFIED ---
    private fun addEditor(
        inflater: LayoutInflater,
        baseLabel: String,
        key: String,
        savedValue: String?,
        defaultValue: String?,
        isDeletable: Boolean,
        specialColor: Int? = null,
        isGenericToneEditor: Boolean = false
    ) {
        val editorView = inflater.inflate(R.layout.editor_row, binding.promptEditorContainer, false)

        val cardView = editorView as MaterialCardView
        if (specialColor != null) {
            cardView.setCardBackgroundColor(ContextCompat.getColor(this, specialColor))
        }

        val headerBar = editorView.findViewById<LinearLayout>(R.id.header_bar)
        val titleText = editorView.findViewById<TextView>(R.id.prompt_title)
        val expandButton = editorView.findViewById<ImageButton>(R.id.btn_expand_toggle)
        val expandableArea = editorView.findViewById<LinearLayout>(R.id.expandable_content_area)

        val editText = editorView.findViewById<TextInputEditText>(R.id.text_input_edit_text)
        val saveButton = editorView.findViewById<ImageButton>(R.id.btn_save_prompt)
        val resetButton = editorView.findViewById<ImageButton>(R.id.btn_reset_prompt)
        val deleteButton = editorView.findViewById<ImageButton>(R.id.btn_delete_prompt)

        expandableArea.visibility = View.GONE
        expandButton.visibility = View.VISIBLE
        saveButton.visibility = View.GONE

        editText.isFocusable = false
        editText.isFocusableInTouchMode = false
        editText.setOnClickListener {
            if (isPremium) {
                showEditDialog(titleText, editText, key, baseLabel, defaultValue, isDeletable, specialColor)
            } else {
                showPremiumRequiredDialog()
            }
        }

        val originalText = savedValue ?: ""
        editText.setText(originalText)

        var finalLabel = baseLabel
        if (isDeletable) {
            finalLabel = "$baseLabel (Custom)"
        } else if (savedValue != defaultValue) {
            finalLabel = "$baseLabel (Edited)"
        } else {
            if (specialColor == null) {
                finalLabel = "$baseLabel (Default)"
            }
        }
        titleText.text = finalLabel

        if (isDeletable) {
            deleteButton.visibility = View.VISIBLE
            resetButton.visibility = View.GONE
            deleteButton.setOnClickListener {
                if (isPremium) {
                    handleDeleteCustomAction(key, baseLabel)
                } else {
                    showPremiumRequiredDialog()
                }
            }
        } else {
            deleteButton.visibility = View.VISIBLE
            resetButton.visibility = View.VISIBLE
            deleteButton.setOnClickListener {
                if (isPremium) {
                    if (specialColor != null) {
                        Toast.makeText(this, "Cannot hide this special item", Toast.LENGTH_SHORT).show()
                    } else {
                        handleHideDefaultAction(baseLabel)
                    }
                } else {
                    showPremiumRequiredDialog()
                }
            }
            resetButton.setOnClickListener {
                if (isPremium) {
                    handleResetDefaultAction(key, defaultValue, editText, titleText, baseLabel, specialColor)
                } else {
                    showPremiumRequiredDialog()
                }
            }
        }

        // --- MODIFIED toggleExpand logic ---
        val toggleExpand = {
            val existingOverlay = editorView.findViewWithTag<View>("premium_full_overlay")

            if (expandableArea.visibility == View.GONE) {
                // --- EXPANDING ---
                expandableArea.visibility = View.VISIBLE
                expandButton.animate().rotation(180f).setDuration(200).start()

                if (!isPremium && existingOverlay == null) {
                    // --- MODIFIED: Create ONE overlay ---
                    headerBar.post { // Wait for header height
                        val overlay = createFullLockOverlay(headerBar.height)
                        overlay.tag = "premium_full_overlay"
                        overlay.layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        editorView.addView(overlay)
                    }
                    // --- END MODIFIED ---
                }

                editorView.post {
                    val scrollViewHeight = binding.scrollView.height
                    val scrollY = binding.scrollView.scrollY
                    val editorTop = editorView.top
                    val editorBottom = editorView.bottom

                    val isTopVisible = editorTop >= scrollY
                    val isBottomVisible = editorBottom <= (scrollY + scrollViewHeight)

                    if (!isTopVisible || !isBottomVisible) {
                        binding.scrollView.smoothScrollTo(0, editorView.top)
                    }
                }
            } else {
                // --- COLLAPSING ---
                expandableArea.visibility = View.GONE
                expandButton.animate().rotation(0f).setDuration(200).start()

                // Remove the one overlay
                existingOverlay?.let { editorView.removeView(it) }
            }
        }

        headerBar.setOnClickListener { toggleExpand() }
        expandButton.setOnClickListener { toggleExpand() }
        // --- END MODIFIED ---


        if (isGenericToneEditor) {
            val managerArea = editorView.findViewById<LinearLayout>(R.id.generic_tones_manager_area)
            val chipContainer = editorView.findViewById<LinearLayout>(R.id.generic_tones_chip_container)

            managerArea.visibility = View.VISIBLE
            populateGenericTonesList(chipContainer)
        }

        binding.promptEditorContainer.addView(editorView)
    }

    private fun showEditDialog(
        titleText: TextView,
        rowEditText: TextInputEditText,
        key: String,
        baseLabel: String,
        defaultValue: String?,
        isDeletable: Boolean,
        specialColor: Int?
    ) {
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_edit_prompt, null)
        val dialogEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_prompt_content)

        dialogEditText.setText(rowEditText.text.toString())
        dialogEditText.requestFocus()

        MaterialAlertDialogBuilder(this)
            .setTitle("Edit: $baseLabel")
            .setView(dialogView)
            .setPositiveButton("Save") { dialog, _ ->
                val newText = dialogEditText.text.toString()

                prefs.edit { putString(key, newText) }
                rowEditText.setText(newText)

                if (isDeletable) {
                    titleText.text = "$baseLabel (Custom)"
                } else if (newText == defaultValue) {
                    titleText.text = if (specialColor != null) baseLabel else "$baseLabel (Default)"
                } else {
                    titleText.text = "$baseLabel (Edited)"
                }

                Toast.makeText(this, "Prompt saved!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private fun populateGenericTonesList(chipContainer: LinearLayout) {
        chipContainer.removeAllViews()
        val definition = promptRepository.getPromptDefinition(currentCategoryName)
            ?: promptRepository.getPromptDefinition("POST")!!
        val inflater = LayoutInflater.from(this)

        val addChip = inflater.inflate(R.layout.chip_add, chipContainer, false) as Chip
        addChip.setOnClickListener { showAddToneDialog(chipContainer) }
        chipContainer.addView(addChip)

        val hiddenTonesKey = getHiddenDefaultTonesKey(currentCategoryName)
        val hiddenTones = prefs.getStringSet(hiddenTonesKey, emptySet()) ?: emptySet()

        val defaultTones = definition.genericTones.sorted()
        for (tone in defaultTones) {
            if (tone in hiddenTones) continue

            val chip = inflater.inflate(R.layout.chip_editable, chipContainer, false) as Chip
            chip.text = tone
            chip.isCloseIconVisible = true
            chip.setOnCloseIconClickListener {
                handleDeleteTone(tone, chipContainer, isDefault = true)
            }
            chipContainer.addView(chip)
        }

        val customTonesKey = getGenericTonesKey(currentCategoryName)
        val customTones = prefs.getStringSet(customTonesKey, emptySet())?.sorted() ?: emptyList()
        for (tone in customTones) {
            val chip = inflater.inflate(R.layout.chip_editable, chipContainer, false) as Chip
            chip.text = tone
            chip.isCloseIconVisible = true
            chip.setOnCloseIconClickListener {
                handleDeleteTone(tone, chipContainer, isDefault = false)
            }
            chipContainer.addView(chip)
        }
    }

    private fun showAddToneDialog(chipContainer: LinearLayout) {
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_tone, null)
        val nameEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_tone_name)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Custom Tone")
            .setView(dialogView)
            .setPositiveButton("Add") { dialog, _ ->
                val label = nameEditText.text.toString().trim()
                if (label.isNotEmpty()) {
                    val key = getGenericTonesKey(currentCategoryName)
                    val customTones = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()

                    if (customTones.add(label)) {
                        prefs.edit { putStringSet(key, customTones) }
                        populateGenericTonesList(chipContainer)
                    } else {
                        Toast.makeText(this, "Tone '$label' already exists", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleDeleteTone(toneLabel: String, chipContainer: LinearLayout, isDefault: Boolean) {
        if (isDefault) {
            val key = getHiddenDefaultTonesKey(currentCategoryName)
            val hiddenTones = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (hiddenTones.add(toneLabel)) {
                prefs.edit { putStringSet(key, hiddenTones) }
                populateGenericTonesList(chipContainer)
                Toast.makeText(this, "Hid default tone '$toneLabel'", Toast.LENGTH_SHORT).show()
            }
        } else {
            val key = getGenericTonesKey(currentCategoryName)
            val customTones = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (customTones.remove(toneLabel)) {
                prefs.edit { putStringSet(key, customTones) }
                populateGenericTonesList(chipContainer)
                Toast.makeText(this, "Deleted custom tone '$toneLabel'", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleDeleteCustomAction(key: String, label: String) {
        val actionLabel = label.removePrefix("Action: ").substringBefore(" (Custom)")

        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Custom Action?")
            .setMessage("Are you sure you want to delete the action '$actionLabel'? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                performDeleteAction(key, actionLabel)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDeleteAction(key: String, actionLabel: String) {
        val customActionsKey = getCustomActionsKey(currentCategoryName)
        val customActions = prefs.getStringSet(customActionsKey, emptySet())?.toMutableSet() ?: mutableSetOf()

        if (customActions.remove(actionLabel)) {
            prefs.edit {
                remove(key)
                putStringSet(customActionsKey, customActions)
            }
            populateEditors()
            Toast.makeText(this, "Deleted '$actionLabel'", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleHideDefaultAction(baseLabel: String) {
        val actionLabel = baseLabel.removePrefix("Action: ")

        MaterialAlertDialogBuilder(this)
            .setTitle("Hide Default Action?")
            .setMessage("Are you sure you want to hide the default action '$actionLabel'? You can restore it by resetting the category.")
            .setPositiveButton("Hide") { _, _ ->
                val key = getHiddenDefaultActionsKey(currentCategoryName)
                val hiddenActions = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
                if (hiddenActions.add(actionLabel)) {
                    prefs.edit { putStringSet(key, hiddenActions) }
                    populateEditors() // Refresh the list
                    Toast.makeText(this, "Hid default action '$actionLabel'", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleResetDefaultAction(
        key: String,
        defaultValue: String?,
        editText: TextInputEditText,
        titleText: TextView,
        baseLabel: String,
        specialColor: Int?
    ) {
        prefs.edit {
            remove(key)
        }

        val defaultText = defaultValue ?: ""
        editText.setText(defaultText)

        titleText.text = if (specialColor != null) baseLabel else "$baseLabel (Default)"

        Toast.makeText(this, "Prompt reset to default", Toast.LENGTH_SHORT).show()
    }

    private fun showAddActionDialog() {
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_action, null)
        val labelEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_action_label)
        val templateEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_action_template)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Custom Action")
            .setView(dialogView)
            .setPositiveButton("Add") { dialog, _ ->
                val label = labelEditText.text.toString().trim()
                val template = templateEditText.text.toString()

                if (label.isEmpty()) {
                    Toast.makeText(this, "Action label cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (template.isEmpty()) {
                    Toast.makeText(this, "Action template cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }


                val customActionsKey = getCustomActionsKey(currentCategoryName)
                val customActions = prefs.getStringSet(customActionsKey, emptySet())?.toMutableSet() ?: mutableSetOf()

                val definition = promptRepository.getPromptDefinition(currentCategoryName)
                    ?: promptRepository.getPromptDefinition("POST")!!
                val isConflict = definition.specificActions.any { it.label.equals(label, true) } ||
                        definition.genericTones.any { it.equals(label, true) }

                if (isConflict) {
                    Toast.makeText(this, "Action '$label' conflicts with a default action/tone", Toast.LENGTH_SHORT).show()
                } else if (customActions.add(label)) {
                    prefs.edit {
                        putStringSet(customActionsKey, customActions)
                        putString(getPromptTemplateKey(currentCategoryName, label), template)
                    }
                    populateEditors()
                    Toast.makeText(this, "Added action '$label'", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Action '$label' already exists", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetCurrentCategory() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset All Prompts?")
            .setMessage("Are you sure you want to reset all prompts in the '$currentCategoryName' category?")
            .setPositiveButton("Reset All") { _, _ ->
                performResetCategory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performResetCategory() {
        prefs.edit {
            val definition = promptRepository.getPromptDefinition(currentCategoryName)
                ?: promptRepository.getPromptDefinition("POST")!!
            val keysToClear = mutableListOf<String>()

            keysToClear.add(getPromptTemplateKey(currentCategoryName, "prefix"))
            // --- TYPO FIX ---
            keysToClear.add(getPromptTemplateKey(currentCategoryName, "generic"))
            // --- END TYPO FIX ---

            definition.specificActions.forEach {
                keysToClear.add(getPromptTemplateKey(currentCategoryName, it.label))
            }

            val customActionsKey = getCustomActionsKey(currentCategoryName)
            val customActions = prefs.getStringSet(customActionsKey, emptySet()) ?: emptySet()
            customActions.forEach {
                keysToClear.add(getPromptTemplateKey(currentCategoryName, it))
            }

            val customTonesKey = getGenericTonesKey(currentCategoryName)
            val hiddenTonesKey = getHiddenDefaultTonesKey(currentCategoryName)
            val hiddenActionsKey = getHiddenDefaultActionsKey(currentCategoryName)

            val topKKey = getTopKKey(currentCategoryName)
            val tempKey = getTemperatureKey(currentCategoryName)

            keysToClear.forEach { remove(it) }
            remove(customActionsKey)
            remove(customTonesKey)
            remove(hiddenTonesKey)
            remove(hiddenActionsKey)
        }

        populateEditors()
        // Also reset the sliders in the UI
        prefs.edit { remove(getTopKKey(currentCategoryName)) }
        prefs.edit { remove(getTemperatureKey(currentCategoryName)) }
        loadCategoryLlmSettings()
        Toast.makeText(this, "$currentCategoryName category reset to default", Toast.LENGTH_SHORT).show()
    }

    private fun showAddCategoryDialog() {
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_category, null)
        val nameEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_category_name)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add New Category")
            .setView(dialogView)
            .setPositiveButton("Add") { dialog, _ ->
                val newName = nameEditText.text.toString().trim().uppercase()

                if (newName.isEmpty()) {
                    Toast.makeText(this, "Category name cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val currentCategories = prefs.getStringSet(PREF_KEY_ACTIVE_CATEGORIES, emptySet())?.toMutableSet() ?: mutableSetOf()

                if (currentCategories.add(newName)) {
                    val defaultPrefix = "Generated reply:" // Simple default
                    val defaultGenericTemplate = "Task: Reply to the following with a {TONE} tone.\nText: {CONTEXT}" // Simple default
                    val defaultActionLabel = "Respond" // Simple default
                    val defaultActionTemplate = "Task: Write a response to the following.\nText: {CONTEXT}" // Simple default

                    prefs.edit {
                        putStringSet(PREF_KEY_ACTIVE_CATEGORIES, currentCategories)
                        putString(getPromptTemplateKey(newName, "prefix"), defaultPrefix)
                        putString(getPromptTemplateKey(newName, "generic"), defaultGenericTemplate)
                        putString(getPromptTemplateKey(newName, defaultActionLabel), defaultActionTemplate)
                        putInt(getTopKKey(newName), 64)
                        putFloat(getTemperatureKey(newName), 1.0f)
                        putStringSet(getCustomActionsKey(newName), setOf(defaultActionLabel)) // Add the first action
                        putStringSet(getHiddenDefaultActionsKey(newName), emptySet())
                        putStringSet(getGenericTonesKey(newName), emptySet())
                        putStringSet(getHiddenDefaultTonesKey(newName), emptySet())
                    }
                    currentCategoryName = newName
                    setupCategorySpinner()
                    Toast.makeText(this, "Added category '$newName'", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Category '$newName' already exists", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteCategoryDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Category '$currentCategoryName'?")
            .setMessage("Are you sure? All custom prompts, actions, and tones for this category will be permanently lost.")
            .setPositiveButton("Delete") { _, _ ->
                performDeleteCategory()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDeleteCategory() {
        val categoryToDelete = currentCategoryName
        val currentCategories = prefs.getStringSet(PREF_KEY_ACTIVE_CATEGORIES, emptySet())?.toMutableSet() ?: mutableSetOf()

        if (currentCategories.remove(categoryToDelete)) {
            if (currentCategories.isEmpty()) {
                currentCategories.add("POST")
                Toast.makeText(this, "Last category deleted. Restoring default 'POST'.", Toast.LENGTH_LONG).show()
            }

            prefs.edit {
                putStringSet(PREF_KEY_ACTIVE_CATEGORIES, currentCategories)

                val definition = promptRepository.getPromptDefinition(categoryToDelete)
                val customActionsKey = getCustomActionsKey(categoryToDelete)
                val customActions = prefs.getStringSet(customActionsKey, emptySet()) ?: emptySet()

                remove(getPromptTemplateKey(categoryToDelete, "prefix"))
                remove(getPromptTemplateKey(categoryToDelete, "generic"))
                definition?.specificActions?.forEach { remove(
                    getPromptTemplateKey(
                        categoryToDelete,
                        it.label
                    )
                ) }
                customActions.forEach { remove(getPromptTemplateKey(categoryToDelete, it)) }

                remove(customActionsKey)
                remove(getHiddenDefaultActionsKey(categoryToDelete))
                remove(getGenericTonesKey(categoryToDelete))
                remove(getHiddenDefaultTonesKey(categoryToDelete))
                remove(getTopKKey(categoryToDelete))
                remove(getTemperatureKey(categoryToDelete))
            }

            currentCategoryName = currentCategories.firstOrNull() ?: "POST"
            setupCategorySpinner()

            Toast.makeText(this, "Deleted category '$categoryToDelete'", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRestoreDefaultsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restore Defaults?")
            .setMessage("This will restore the original default categories and make all hidden default actions and tones visible again. It will NOT affect your custom categories, actions, or edits to default prompts.")
            .setPositiveButton("Restore") { _, _ ->
                performRestoreDefaults()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performRestoreDefaults() {
        prefs.edit {
            putStringSet(PREF_KEY_ACTIVE_CATEGORIES, defaultCategoryNames.toSet())

            val allPossibleCategories = (promptRepository.getActiveCategories() + defaultCategoryNames).toSet()
            allPossibleCategories.forEach { categoryName ->
                remove(getHiddenDefaultActionsKey(categoryName))
                remove(getHiddenDefaultTonesKey(categoryName))
            }
        }

        currentCategoryName = "POST"
        setupCategorySpinner()
        Toast.makeText(this, "Default categories restored", Toast.LENGTH_SHORT).show()
    }

    // --- MODIFIED FUNCTION ---
    /**
     * Adds or removes lock icons from the toolbar menu based on premium status.
     * Uses an ImageSpan to force the icon to appear in the overflow menu.
     */
    private fun updateToolbarMenuLocks(isPremium: Boolean) {
        val menu = binding.toolbar.menu ?: return
        val itemIds = listOf(
            R.id.action_reset,
            R.id.action_add_category,
            R.id.action_delete_category,
            R.id.action_restore_defaults
        )

        // --- NEW INITIALIZATION LOGIC ---
        // If we haven't saved the original titles yet, do it now.
        // This runs only once, when the menu is guaranteed to be ready.
        if (!toolbarTitlesInitialized) {
            itemIds.forEach { id ->
                val item = menu.findItem(id)
                if (item != null && item.title != null) {
                    originalMenuItemTitles[id] = item.title.toString()
                }
            }
            // After this, the map is populated with the clean, original titles.
            toolbarTitlesInitialized = true
        }
        // --- END NEW INITIALIZATION LOGIC ---


        // --- STATELESS UPDATE LOGIC ---
        // This runs every time the premium status changes
        itemIds.forEach { id ->
            val item = menu.findItem(id) ?: return@forEach
            // Get the *guaranteed clean* title from our map
            val originalTitle = originalMenuItemTitles[id]

            // If the map failed for some reason, just stop.
            if (originalTitle == null) {
                // Log.w("ToolbarLock", "Could not find original title for $id")
                return@forEach
            }

            if (isPremium) {
                // --- 1. PREMIUM USER ---
                // Always set the clean title
                item.title = originalTitle

            } else {
                // --- 2. NON-PREMIUM USER ---

                // Only add a lock if it's not already a SpannableString
                if (item.title?.javaClass != SpannableString::class) {
                    val premiumColor = ContextCompat.getColor(this, R.color.colorPrimary)
                    val lockDrawable = ContextCompat.getDrawable(this, R.drawable.ic_lock_premium)?.mutate()

                    lockDrawable?.let {
                        DrawableCompat.setTint(it, premiumColor)
                        val size = (18 * resources.displayMetrics.density).toInt()
                        it.setBounds(0, 0, size, size)

                        val spannableTitle = SpannableString("   $originalTitle")
                        val imageSpan = ImageSpan(it, 2 /* DynamicDrawableSpan.ALIGN_CENTER */)

                        spannableTitle.setSpan(imageSpan, 0, 1, 0)
                        item.title = spannableTitle
                    } ?: run {
                        item.title = originalTitle // Fallback
                    }
                }
            }
        }
    }
    // --- END MODIFIED FUNCTION ---

    /**
     * Shows a dialog informing the user that premium is required for this action.
     */
    private fun showPremiumRequiredDialog() {
        launchPaywallActivity()
    }

    /**
     * Launch the RevenueCat paywall
     */
    private fun launchPaywallActivity() {
        paywallActivityLauncher.launchIfNeeded(requiredEntitlementIdentifier = "")
    }

    // --- REMOVED FUNCTION ---
    // private fun createHeaderOverlay(): View { ... }
    // --- END REMOVED FUNCTION ---

    // --- MODIFIED FUNCTION ---
    /**
     * Creates a single, seamless lock overlay for an expanded card.
     * The overlay is visual-only at the top (over the header) but clickable
     * over the content area.
     * @param headerHeight The height of the header view, to offset the clickable area.
     */
    private fun createFullLockOverlay(headerHeight: Int): FrameLayout {
        val dpToPx = { dp: Int -> (dp * resources.displayMetrics.density).toInt() }
        val premiumColor = ContextCompat.getColor(this, R.color.colorPrimary)

        // 1. The Root Overlay (visual background, passes clicks)
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(semiTransparentOverlayColor)
            elevation = dpToPx(4).toFloat()
            // Let clicks pass through to children
            isClickable = false
            isFocusable = false
        }

        // 2. The Lock Icon (centered)
        val lockIcon = ImageView(this).apply {
            val size = dpToPx(48)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.CENTER
            }
            setImageResource(R.drawable.ic_lock_premium)
            setColorFilter(premiumColor)
        }

        // 3. The Click Blocker (covers content area only)
        val clickableBlocker = View(this).apply {
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            params.topMargin = headerHeight // <-- The magic!
            layoutParams = params

            isClickable = true // Intercepts clicks
            isFocusable = true
            setOnClickListener {
                launchPaywallActivity() // Launch paywall on click
            }
        }

        // Add children to the root overlay
        overlay.addView(lockIcon)
        overlay.addView(clickableBlocker) // Add blocker on top of lock icon

        return overlay
    }
    // --- END MODIFIED FUNCTION ---

    /**
     * Update lock overlays based on premium status (FAB only)
     * Editor overlays are now handled by toggleExpand
     */
    private fun updatePremiumOverlays() {
        updateFabOverlay()
    }

    /**
     * Update the FAB overlay based on premium status
     */
    private fun updateFabOverlay() {
        val premiumColor = ContextCompat.getColor(this, R.color.colorOnPrimary)
        if (!isPremium) {
            binding.fabAddAction.alpha = 1.0f
            binding.fabAddAction.setImageResource(R.drawable.ic_lock_premium)
            binding.fabAddAction.imageTintList = android.content.res.ColorStateList.valueOf(premiumColor)
        } else {
            binding.fabAddAction.alpha = 1.0f
            binding.fabAddAction.setImageResource(android.R.drawable.ic_input_add)
            binding.fabAddAction.imageTintList = android.content.res.ColorStateList.valueOf(premiumColor)
        }
    }

    override fun onActivityResult(result: PaywallResult) {
        when (result) {
            is PaywallResult.Purchased,
            is PaywallResult.Restored -> {
                viewModel.refreshPremiumStatus()
                Toast.makeText(this, "Premium activated! Enjoy full access.", Toast.LENGTH_SHORT).show()
            }
            is PaywallResult.Cancelled -> {
                // User cancelled, do nothing
            }
            is PaywallResult.Error -> {
                Toast.makeText(this, "Error: ${result.error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}