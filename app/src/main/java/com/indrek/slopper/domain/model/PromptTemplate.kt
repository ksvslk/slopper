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
        promptPrefix = "5-100 chars. Tweet-like reply only without #-s and @-s.",
        genericToneTemplate = "{TONE} tone. Context: {CONTEXT}",
        specificActions = listOf(
            SpecificAction("Answer", "Answer directly to {CONTEXT}"),
            SpecificAction("Question", "Ask question about {CONTEXT}"),
            SpecificAction("Yes", "Agree. Context: {CONTEXT}"),
            SpecificAction("No", "Disagree. Context: {CONTEXT}"),
            SpecificAction("Maybe", "Be uncertain. Context: {CONTEXT}")
        ),
        genericTones = listOf("Disagreeing", "Supportive", "Satirical", "Deep")
    ),
    "CHAT" to PromptUIDefinition(
        promptPrefix = "5-100 chars. Chat-like output only.",
        genericToneTemplate = "{TONE} tone. Context: {CONTEXT}",
        specificActions = listOf(
            SpecificAction("Question", "Ask question about {CONTEXT}"),
            SpecificAction("Answer", "Give answer to {CONTEXT}"),
            SpecificAction("Yes", "Positive reply. Context: {CONTEXT}"),
            SpecificAction("No", "Negative reply. Context: {CONTEXT}"),
            SpecificAction("Maybe", "Uncertain reply. Context: {CONTEXT}")
        ),
        genericTones = listOf("Happy", "Loving", "Neutral")
    )
)