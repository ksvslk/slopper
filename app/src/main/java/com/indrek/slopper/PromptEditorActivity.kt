package com.indrek.slopper

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
// import android.widget.EditText // No longer needed directly
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.slider.Slider
import java.util.Locale
import com.indrek.slopper.databinding.ActivityPromptEditorBinding

class PromptEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPromptEditorBinding
    private lateinit var prefs: SharedPreferences
    private var currentCategoryName: String = "POST" // Default

    private val editorStateMap = mutableMapOf<TextInputEditText, Pair<ImageButton, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPromptEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(SlopperActivity.PREFS_NAME, Context.MODE_PRIVATE)

        getActiveCategories(this) // Initialize if needed

        setupToolbar()
        setupCategorySpinner()

        binding.fabAddAction.setOnClickListener {
            showAddActionDialog() // This now calls the updated function
        }

        setupLlmSliders()
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
            binding.labelTopK.text = "Focus: ${value.toInt()}"
        }
        binding.sliderTopK.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                prefs.edit {
                    putInt(getTopKKey(currentCategoryName), slider.value.toInt())
                }
                Toast.makeText(this@PromptEditorActivity, "Focus saved", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadCategoryLlmSettings() {
        val topK = prefs.getInt(getTopKKey(currentCategoryName), 65)
        val temp = prefs.getFloat(getTemperatureKey(currentCategoryName), 1.0f)

        binding.sliderTopK.value = topK.toFloat()
        binding.sliderTemperature.value = temp
        binding.labelTopK.text = "Focus: $topK"
        binding.labelTemperature.text = String.format(Locale.US, "Creativity: %.1f", temp)
    }

    // --- setupToolbar unchanged ---
    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
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


    // --- setupCategorySpinner unchanged ---
    private fun setupCategorySpinner() {
        var categories = getActiveCategories(this) // Use var to allow modification
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)
        (binding.spinnerCategorySelection as? AutoCompleteTextView)?.setAdapter(adapter)

        if (currentCategoryName !in categories) {
            currentCategoryName = categories.firstOrNull() ?: "POST" // Fallback
            if ("POST" !in categories) {
                val currentCategoriesSet = prefs.getStringSet(PREF_KEY_ACTIVE_CATEGORIES, emptySet())?.toMutableSet() ?: mutableSetOf()
                currentCategoriesSet.add("POST")
                prefs.edit{ putStringSet(PREF_KEY_ACTIVE_CATEGORIES, currentCategoriesSet)}
                categories = getActiveCategories(this)
                val newAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, categories)
                (binding.spinnerCategorySelection as? AutoCompleteTextView)?.setAdapter(newAdapter)
                currentCategoryName = "POST"
            }
        }

        binding.spinnerCategorySelection.setOnItemClickListener { _, _, position, _ ->
            currentCategoryName = categories[position]
            loadCategoryLlmSettings() // Load settings for new category
            populateEditors()
        }

        binding.spinnerCategorySelection.setText(currentCategoryName, false)
        loadCategoryLlmSettings() // Load settings for initial category
        populateEditors() // This must come *after* loading settings
    }

    // --- populateEditors unchanged ---
    private fun populateEditors() {
        binding.promptEditorContainer.removeAllViews()
        editorStateMap.clear()

        val definition = promptDefinitions[currentCategoryName] ?: promptDefinitions["POST"]!!
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

    // --- addEditor unchanged ---
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
                handleDeleteCustomAction(key, baseLabel)
            }
        } else {
            deleteButton.visibility = View.VISIBLE
            resetButton.visibility = View.VISIBLE
            deleteButton.setOnClickListener {
                if (specialColor != null) {
                    Toast.makeText(this, "Cannot hide this special item", Toast.LENGTH_SHORT).show()
                } else {
                    handleHideDefaultAction(baseLabel)
                }
            }
            resetButton.setOnClickListener {
                handleResetDefaultAction(key, defaultValue, editText, saveButton)
            }
        }

        saveButton.setOnClickListener {
            handleSaveSinglePrompt(key, editText, saveButton)
            if (isDeletable) {
                titleText.text = "$baseLabel (Custom)"
            } else if (editText.text.toString() == defaultValue) {
                titleText.text = if (specialColor != null) baseLabel else "$baseLabel (Default)"
            } else {
                titleText.text = "$baseLabel (Edited)"
            }
        }

        editorStateMap[editText] = Pair(saveButton, originalText)
        editText.addTextChangedListener(object : TextWatcher { /* ... (unchanged text watcher) ... */
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val state = editorStateMap[editText]
                val currentSaveButton = state?.first
                val currentOriginalText = state?.second

                if (s.toString() == currentOriginalText) {
                    currentSaveButton?.isEnabled = false
                    currentSaveButton?.alpha = 0.4f
                } else {
                    currentSaveButton?.isEnabled = true
                    currentSaveButton?.alpha = 1.0f
                }
            }
        })

        val toggleExpand = { /* ... (unchanged expand logic) ... */
            if (expandableArea.visibility == View.GONE) {
                expandableArea.visibility = View.VISIBLE
                expandButton.animate().rotation(180f).setDuration(200).start()

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
                expandableArea.visibility = View.GONE
                expandButton.animate().rotation(0f).setDuration(200).start()
            }
        }

        headerBar.setOnClickListener { toggleExpand() }
        expandButton.setOnClickListener { toggleExpand() }

        if (isGenericToneEditor) {
            val managerArea = editorView.findViewById<LinearLayout>(R.id.generic_tones_manager_area)
            val chipContainer = editorView.findViewById<LinearLayout>(R.id.generic_tones_chip_container)

            managerArea.visibility = View.VISIBLE
            populateGenericTonesList(chipContainer)
        }

        binding.promptEditorContainer.addView(editorView)
    }

    // --- populateGenericTonesList unchanged ---
    private fun populateGenericTonesList(chipContainer: LinearLayout) {
        chipContainer.removeAllViews()
        val definition = promptDefinitions[currentCategoryName] ?: promptDefinitions["POST"]!!
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

    // --- showAddToneDialog unchanged ---
    private fun showAddToneDialog(chipContainer: LinearLayout) {
        // 1. Inflate the new layout
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_tone, null)
        val nameEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_tone_name) // Find the new component

        // 2. Use the Material builder
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Custom Tone")
            .setView(dialogView) // Set the new layout
            .setPositiveButton("Add") { dialog, _ ->
                val label = nameEditText.text.toString().trim() // Get text from new component
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

    // --- handleDeleteTone unchanged ---
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

    // --- handleSaveSinglePrompt unchanged ---
    private fun handleSaveSinglePrompt(key: String, editText: TextInputEditText, saveButton: ImageButton) {
        val newText = editText.text.toString()
        prefs.edit {
            putString(key, newText)
        }

        saveButton.isEnabled = false
        saveButton.alpha = 0.4f
        editorStateMap[editText] = Pair(saveButton, newText)

        Toast.makeText(this, "Prompt saved!", Toast.LENGTH_SHORT).show()
    }

    // --- handleDeleteCustomAction unchanged ---
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

    // --- performDeleteAction unchanged ---
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

    // --- handleHideDefaultAction unchanged ---
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

    // --- handleResetDefaultAction unchanged ---
    private fun handleResetDefaultAction(
        key: String,
        defaultValue: String?,
        editText: TextInputEditText,
        saveButton: ImageButton
    ) {
        prefs.edit {
            remove(key)
        }

        val defaultText = defaultValue ?: ""
        editText.setText(defaultText)

        saveButton.isEnabled = false
        saveButton.alpha = 0.4f
        editorStateMap[editText] = Pair(saveButton, defaultText)

        populateEditors()
        Toast.makeText(this, "Prompt reset to default", Toast.LENGTH_SHORT).show()
    }

    // ### MODIFIED: Uses new dialog layout and saves template ###
    private fun showAddActionDialog() { // For Specific Actions (Called by FAB)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_action, null) // Use new layout
        val labelEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_action_label)
        val templateEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_action_template)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add Custom Action")
            .setView(dialogView) // Set new layout
            .setPositiveButton("Add") { dialog, _ ->
                val label = labelEditText.text.toString().trim()
                val template = templateEditText.text.toString() // Get template text

                if (label.isEmpty()) {
                    Toast.makeText(this, "Action label cannot be empty", Toast.LENGTH_SHORT).show()
                    // Need to prevent dismiss here if possible, or handle re-showing dialog
                    return@setPositiveButton
                }
                if (template.isEmpty()) { // Basic check for template
                    Toast.makeText(this, "Action template cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }


                val customActionsKey = getCustomActionsKey(currentCategoryName)
                val customActions = prefs.getStringSet(customActionsKey, emptySet())?.toMutableSet() ?: mutableSetOf()

                val definition = promptDefinitions[currentCategoryName] ?: promptDefinitions["POST"]!!
                val isConflict = definition.specificActions.any { it.label.equals(label, true) } ||
                        definition.genericTones.any { it.equals(label, true) }

                if (isConflict) {
                    Toast.makeText(this, "Action '$label' conflicts with a default action/tone", Toast.LENGTH_SHORT).show()
                } else if (customActions.add(label)) {
                    prefs.edit {
                        putStringSet(customActionsKey, customActions)
                        // ### MODIFIED: Save the template too ###
                        putString(getPromptTemplateKey(currentCategoryName, label), template)
                    }
                    populateEditors()
                    Toast.makeText(this, "Added action '$label'", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Action '$label' already exists", Toast.LENGTH_SHORT).show()
                }
                // Dialog dismisses automatically
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- resetCurrentCategory unchanged ---
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

    // --- performResetCategory unchanged ---
    private fun performResetCategory() {
        prefs.edit {
            val definition = promptDefinitions[currentCategoryName] ?: promptDefinitions["POST"]!!
            val keysToClear = mutableListOf<String>()

            keysToClear.add(getPromptTemplateKey(currentCategoryName, "prefix"))
            keysToClear.add(getPromptTemplateKey(currentCategoryName, "generic"))

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

    // ### MODIFIED: Uses simplified dialog and adds default prompts ###
    private fun showAddCategoryDialog() { // For Categories (Called by Menu)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_category, null) // Use simplified layout
        val nameEditText = dialogView.findViewById<TextInputEditText>(R.id.edit_category_name)

        MaterialAlertDialogBuilder(this)
            .setTitle("Add New Category")
            .setView(dialogView)
            .setPositiveButton("Add") { dialog, _ ->
                val newName = nameEditText.text.toString().trim().uppercase()

                if (newName.isEmpty()) {
                    Toast.makeText(this, "Category name cannot be empty", Toast.LENGTH_SHORT).show()
                    // Re-show dialog or add better validation? For now, just return.
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
                        // ### ADDED: Set default LLM params ###
                        putInt(getTopKKey(newName), 64)
                        putFloat(getTemperatureKey(newName), 1.0f)
                        // ### END ADDED ###
                        putStringSet(getCustomActionsKey(newName), setOf(defaultActionLabel)) // Add the first action
                        // Initialize other lists as empty
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

    // --- showDeleteCategoryDialog unchanged ---
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

    // --- performDeleteCategory unchanged ---
    private fun performDeleteCategory() {
        val categoryToDelete = currentCategoryName
        val currentCategories = prefs.getStringSet(PREF_KEY_ACTIVE_CATEGORIES, emptySet())?.toMutableSet() ?: return

        if (currentCategories.remove(categoryToDelete)) {
            if (currentCategories.isEmpty()) {
                currentCategories.add("POST")
                Toast.makeText(this, "Last category deleted. Restoring default 'POST'.", Toast.LENGTH_LONG).show()
            }

            prefs.edit {
                putStringSet(PREF_KEY_ACTIVE_CATEGORIES, currentCategories)

                // Remove all associated preferences
                val definition = promptDefinitions[categoryToDelete] // Get defaults (might be null)
                val customActionsKey = getCustomActionsKey(categoryToDelete)
                val customActions = prefs.getStringSet(customActionsKey, emptySet()) ?: emptySet()

                remove(getPromptTemplateKey(categoryToDelete, "prefix"))
                remove(getPromptTemplateKey(categoryToDelete, "generic"))
                definition?.specificActions?.forEach { remove(getPromptTemplateKey(categoryToDelete, it.label)) }
                customActions.forEach { remove(getPromptTemplateKey(categoryToDelete, it)) }

                remove(customActionsKey)
                remove(getHiddenDefaultActionsKey(categoryToDelete))
                remove(getGenericTonesKey(categoryToDelete))
                remove(getHiddenDefaultTonesKey(categoryToDelete))
                // ### ADDED: Remove LLM params ###
                remove(getTopKKey(categoryToDelete))
                remove(getTemperatureKey(categoryToDelete))
            }

            currentCategoryName = currentCategories.firstOrNull() ?: "POST"
            setupCategorySpinner()

            Toast.makeText(this, "Deleted category '$categoryToDelete'", Toast.LENGTH_SHORT).show()
        }
    }

    // --- showRestoreDefaultsDialog unchanged ---
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

    // --- performRestoreDefaults unchanged ---
    private fun performRestoreDefaults() {
        prefs.edit {
            putStringSet(PREF_KEY_ACTIVE_CATEGORIES, defaultCategoryNames.toSet())

            val allPossibleCategories = (getActiveCategories(this@PromptEditorActivity) + defaultCategoryNames).toSet()
            allPossibleCategories.forEach { categoryName ->
                remove(getHiddenDefaultActionsKey(categoryName))
                remove(getHiddenDefaultTonesKey(categoryName))
            }
        }

        currentCategoryName = "POST"
        setupCategorySpinner()
        Toast.makeText(this, "Default categories restored", Toast.LENGTH_SHORT).show()
    }
}