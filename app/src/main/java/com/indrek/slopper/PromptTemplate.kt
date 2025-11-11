package com.indrek.slopper

import android.content.Context
import androidx.core.content.edit
import com.indrek.slopper.SlopperActivity

// Data classes remain the same
data class SpecificAction(
    val label: String,
    val template: String
)

data class PromptUIDefinition(
    val promptPrefix: String,
    val genericToneTemplate: String,
    val specificActions: List<SpecificAction> = emptyList(),
    val genericTones: List<String> = emptyList()
)

// --- Constants for Preference Keys ---
const val PREF_KEY_ACTIVE_CATEGORIES = "active_categories"

// --- Helper Functions (Updated or New) ---

// Gets the preference key for a category's custom specific actions
fun getCustomActionsKey(categoryName: String) = "custom_actions_${categoryName}"

// Gets the preference key for a category's hidden default specific actions
fun getHiddenDefaultActionsKey(categoryName: String) = "hidden_default_actions_${categoryName}"

// Gets the preference key for a category's custom generic tones
fun getGenericTonesKey(categoryName: String) = "generic_tones_${categoryName}"

// Gets the preference key for a category's hidden default generic tones
fun getHiddenDefaultTonesKey(categoryName: String) = "hidden_default_tones_${categoryName}"

// Gets the preference key for a category's Top-K setting
fun getTopKKey(categoryName: String) = "llm_top_k_${categoryName}"

// Gets the preference key for a category's Temperature setting
fun getTemperatureKey(categoryName: String) = "llm_temperature_${categoryName}"

// Gets the preference key for ANY prompt template (prefix, generic, specific)
fun getPromptTemplateKey(categoryName: String, label: String) = "prompt_template_${categoryName}_${label}"

// Default category names (used for initialization)
val defaultCategoryNames = listOf("POST", "MAIL", "SOCIAL", "CHAT")

// This map holds the *default* definitions. Overrides are stored in SharedPreferences.
val promptDefinitions: Map<String, PromptUIDefinition> = mapOf(
    "POST" to PromptUIDefinition(
        promptPrefix = "Generate one short, tweet-like reply (5-100 characters). OUTPUT IS ONLY REPLY! DO NOT INCLUDE HASHTAGS OR @USER. ",
        genericToneTemplate = "Task: Reply to the following message with a {TONE} tone.\nMessage: {CONTEXT}",
        specificActions = listOf(
            SpecificAction("Answer", "Task: Directly answer the following text.\nText: {CONTEXT}"),
            SpecificAction("Question", "Task: Ask an insightful question about the following text.\nText: {CONTEXT}"),
            SpecificAction("Post", "Task: Write a standalone post inspired by the following idea.\nIdea: {CONTEXT}"),
            SpecificAction("Yes", "Task: Write an engaging, affirmative comment to the following.\nText: {CONTEXT}"),
            SpecificAction("No", "Task: Write an engaging, negative comment to the following.\nText: {CONTEXT}"),
            SpecificAction("Maybe", "Task: Write an engaging, non-committal comment to the following.\nText: {CONTEXT}")
        ),
        genericTones = listOf("Disagree", "Support", "Humor", "Crazy", "Smart", "Surprise")
    ),
    "MAIL" to PromptUIDefinition(
        promptPrefix = "Generate one short, email-like reply (5-100 characters). DO NOT INCLUDE INTRO AND OUTRO. ",
        genericToneTemplate = "Task: Reply to the following {MESSAGE_TYPE} with a {TONE} tone.\n{MESSAGE_TYPE_CAP}: {CONTEXT}",
        specificActions = listOf(
            SpecificAction("Acknowledge", "Task: Acknowledge the following email.\nEmail: {CONTEXT}"),
            SpecificAction("Decline", "Task: Politely decline the request in the following email.\nEmail: {CONTEXT}"),
            SpecificAction("Agree", "Task: Agree with the point made in the following email.\nEmail: {CONTEXT}"),
            SpecificAction("Disagree", "Task: Politely disagree with the point made in the following email.\nEmail: {CONTEXT}"),
            SpecificAction("Clarify", "Task: Ask for clarification regarding the following email.\nEmail: {CONTEXT}"),
            SpecificAction("Suggest", "Task: Offer a suggestion in response to the following email.\nEmail: {CONTEXT}"),
        )
    ),
    "SOCIAL" to PromptUIDefinition(
        promptPrefix = "Generate one short, facebook-like reply (5-100 characters). OUTPUT IS ONLY REPLY! ",
        genericToneTemplate = "Task: Reply to the following {MESSAGE_TYPE} with a {TONE} tone.\n{MESSAGE_TYPE_CAP}: {CONTEXT}",
        specificActions = listOf(
            SpecificAction("Engage", "Task: Write an engaging and positive comment on the following post.\nPost: {CONTEXT}"),
            SpecificAction("Question", "Task: Ask a friendly question to encourage discussion on the following post.\nPost: {CONTEXT}"),
            SpecificAction("Support", "Task: Write a supportive and encouraging comment on the following post.\nPost: {CONTEXT}"),
            SpecificAction("Story", "Task: Briefly share a related personal story in a comment on the following post.\nPost: {CONTEXT}"),
        ),
        genericTones = listOf("Funny")
    ),
    "CHAT" to PromptUIDefinition(
        promptPrefix = "Generate short chat-like reply (5-25). BE BRIEF.",
        genericToneTemplate = "Task: Reply to the following {MESSAGE_TYPE} with a {TONE} tone.\n{MESSAGE_TYPE_CAP}: {CONTEXT}",
        specificActions = listOf(
            SpecificAction("Confirm", "Task: Write a quick, informal confirmation for the following message.\nMessage: {CONTEXT}"),
            SpecificAction("Decline", "Task: Write a quick, informal message apologizing and declining the following.\nMessage: {CONTEXT}"),
            SpecificAction("Agree", "Task: Write a quick, informal message of agreement to the following.\nMessage: {CONTEXT}"),
            SpecificAction("Question", "Task: Write a quick, informal question about the following message.\nMessage: {CONTEXT}"),
            SpecificAction("Yes", "Task: Write a friendly, affirmative reply to the following message.\nMessage: {CONTEXT}"),
            SpecificAction("No", "Task: Write a friendly, negative reply to the following message.\nMessage: {CONTEXT}"),

            SpecificAction("Maybe", "Task: Write a friendly, non-committal reply to the following message.\nMessage: {CONTEXT}")
        ),
        genericTones = listOf("Laugh")
    )
)

// Gets the list of currently active categories
fun getActiveCategories(context: Context): List<String> {
    val prefs = context.getSharedPreferences(SlopperActivity.PREFS_NAME, Context.MODE_PRIVATE)
    // Read the set, if it doesn't exist, initialize with defaults
    var categories = prefs.getStringSet(PREF_KEY_ACTIVE_CATEGORIES, null)
    if (categories == null) {
        categories = defaultCategoryNames.toSet()
        prefs.edit { putStringSet(PREF_KEY_ACTIVE_CATEGORIES, categories) }
    }
    return categories.sorted() // Return sorted list
}

// Accepts String category name
fun getActionLabelsForCategory(context: Context, categoryName: String): List<String> {
    // Fallback to POST if category definition doesn't exist (e.g., deleted default)
    val definition = promptDefinitions[categoryName] ?: promptDefinitions["POST"]!!
    val prefs = context.getSharedPreferences(SlopperActivity.PREFS_NAME, Context.MODE_PRIVATE)

    val hiddenDefaultActions = prefs.getStringSet(getHiddenDefaultActionsKey(categoryName), emptySet()) ?: emptySet()
    val customActionLabels = prefs.getStringSet(getCustomActionsKey(categoryName), emptySet()) ?: emptySet()
    val hiddenDefaultTones = prefs.getStringSet(getHiddenDefaultTonesKey(categoryName), emptySet()) ?: emptySet()
    val customToneLabels = prefs.getStringSet(getGenericTonesKey(categoryName), emptySet()) ?: emptySet()

    val visibleDefaultActions = definition.specificActions.map { it.label }.filterNot { it in hiddenDefaultActions }
    val visibleDefaultTones = definition.genericTones.filterNot { it in hiddenDefaultTones }

    return visibleDefaultActions + customActionLabels + visibleDefaultTones + customToneLabels
}

// Accepts String category name
fun generatePrompt(
    context: Context,
    styleName: String,
    tone: String,
    contextText: String
): String {
    val prefs = context.getSharedPreferences(SlopperActivity.PREFS_NAME, Context.MODE_PRIVATE)
    val sanitizedContext = "\"${contextText.replace("\"", "\\\"")}\""
    // Fallback to POST if definition is missing
    val definition = promptDefinitions[styleName] ?: promptDefinitions["POST"]!!

    val body: String
    val specificAction = definition.specificActions.find { it.label.equals(tone, ignoreCase = true) }

    if (specificAction != null) {
        // Is it a default specific action?
        val key = getPromptTemplateKey(styleName, specificAction.label)
        val defaultTemplate = specificAction.template
        val template = prefs.getString(key, defaultTemplate) ?: defaultTemplate
        body = template.replace("{CONTEXT}", sanitizedContext, ignoreCase = true)
    } else {
        // Is it a custom specific action?
        val customActions = prefs.getStringSet(getCustomActionsKey(styleName), emptySet()) ?: emptySet()
        val isCustomAction = customActions.any { it.equals(tone, ignoreCase = true) }

        if (isCustomAction) {
            val key = getPromptTemplateKey(styleName, tone)
            val template = prefs.getString(key, "Custom prompt for {CONTEXT}") ?: "Custom prompt for {CONTEXT}"
            body = template.replace("{CONTEXT}", sanitizedContext, ignoreCase = true)
        } else {
            // Must be a generic tone
            val key = getPromptTemplateKey(styleName, "generic") // Generic tone template key
            val defaultTemplate = definition.genericToneTemplate
            val template = prefs.getString(key, defaultTemplate) ?: defaultTemplate

            val messageType = when (styleName) {
                "MAIL" -> "email"
                "SOCIAL" -> "post"
                else -> "message"
            }
            body = template
                .replace("{CONTEXT}", sanitizedContext, ignoreCase = true)
                .replace("{TONE}", tone.lowercase(), ignoreCase = true)
                .replace("{MESSAGE_TYPE}", messageType, ignoreCase = true)
                .replace("{MESSAGE_TYPE_CAP}", messageType.replaceFirstChar { it.uppercase() }, ignoreCase = true)
        }
    }

    val prefixKey = getPromptTemplateKey(styleName, "prefix")
    val defaultPrefix = definition.promptPrefix
    val prefix = prefs.getString(prefixKey, defaultPrefix) ?: defaultPrefix

    return prefix + body
}