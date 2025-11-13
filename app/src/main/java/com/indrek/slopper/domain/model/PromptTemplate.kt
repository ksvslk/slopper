package com.indrek.slopper.domain.model

/**
 * Data model for a specific action with its label and template.
 */
data class SpecificAction(
    val label: String,
    val template: String
)

/**
 * Data model defining the UI and prompt structure for a category.
 */
data class PromptUIDefinition(
    val promptPrefix: String,
    val genericToneTemplate: String,
    val specificActions: List<SpecificAction> = emptyList(),
    val genericTones: List<String> = emptyList()
)

// SharedPreferences key generation functions
fun getCustomActionsKey(categoryName: String) = "custom_actions_${categoryName}"
fun getHiddenDefaultActionsKey(categoryName: String) = "hidden_default_actions_${categoryName}"
fun getGenericTonesKey(categoryName: String) = "generic_tones_${categoryName}"
fun getHiddenDefaultTonesKey(categoryName: String) = "hidden_default_tones_${categoryName}"
fun getTopKKey(categoryName: String) = "llm_top_k_${categoryName}"
fun getTemperatureKey(categoryName: String) = "llm_temperature_${categoryName}"
fun getPromptTemplateKey(categoryName: String, label: String) = "prompt_template_${categoryName}_${label}"

/**
 * Default category names available in the application.
 */
val defaultCategoryNames = listOf("POST", "CHAT")

/**
 * Default prompt definitions for each category.
 * These define the structure and templates for prompt generation.
 */
val promptDefinitions: Map<String, PromptUIDefinition> = mapOf(
    "POST" to PromptUIDefinition(
        promptPrefix = "Reply in 5-100 chars. No hashtags/@. Only reply text.",
        genericToneTemplate = "Output {TONE}-style reply RESPONDING TO '{CONTEXT}'",
        specificActions = listOf(
            SpecificAction("Answer", "Helpfully but directly respond to {CONTEXT}"),
            SpecificAction("Question", "Curious direct question about {CONTEXT}"),
            SpecificAction("Yes", "Positive, agreeing direct reply to {CONTEXT}"),
            SpecificAction("No", "Negative, disagreeing direct reply to {CONTEXT}"),
            SpecificAction("Maybe", "Thoughtful, uncertain direct reply to {CONTEXT}")
        ),
        genericTones = listOf("Disagreeing", "Supportive", "Satirical", "Deep")
    ),
    "CHAT" to PromptUIDefinition(
        promptPrefix = "Chat reply in 5-100 chars. Only reply text.",
        genericToneTemplate = "Output {TONE}-style reply RESPONDING TO '{CONTEXT}'",
        specificActions = listOf(
            SpecificAction("Question", "Friendly question about {CONTEXT}"),
            SpecificAction("Answer", "Quick answer to {CONTEXT}"),
            SpecificAction("Yes", "Enthusiastic positive to {CONTEXT}"),
            SpecificAction("No", "Polite negative to {CONTEXT}"),
            SpecificAction("Maybe", "Casual maybe to {CONTEXT}")
        ),
        genericTones = listOf("Happy", "Loving", "Neutral")
    )
)