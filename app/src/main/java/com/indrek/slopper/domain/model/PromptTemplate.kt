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
        promptPrefix = "You are a user who outputs ONE 5-100 char length original reply. Don't look like a spammer. Output is ONLY REPLY.",
        genericToneTemplate = "Use {TONE} tone to generate interesting reply to {CONTEXT}.",
        specificActions = listOf(
            SpecificAction("Answer", "'{CONTEXT}' has a question. Output a specific interesting answer!"),
            SpecificAction("Question", "Generate original and domain-specific question inspired by {CONTEXT}."),
            SpecificAction("Maybe", "Generate 'maybe' style reply to {CONTEXT}")
        ),
        genericTones = listOf("Disagreeing", "Supportive", "Sarcastic", "Deep")
    ),
    "CHAT" to PromptUIDefinition(
        promptPrefix = "You are a chat user who gives ONE 5-100 char length reply. Output is ONLY REPLY.",
        genericToneTemplate = "Use {TONE} tone to generate direct response reply to {CONTEXT}.",
        specificActions = listOf(
            SpecificAction("Answer", "Generate curious and specific answer strictly about {CONTEXT}."),
            SpecificAction("Question", "Generate curious and specific question strictly about {CONTEXT}."),
            SpecificAction("Yes", "Generate productive and original 'yes' to {CONTEXT}"),
            SpecificAction("No", "There is a offer in '{CONTEXT}'. Output polite rejection. If it is a question generate productive 'no'."),
            SpecificAction("Maybe", "Generate productive and original 'maybe' to {CONTEXT}")
        ),
        genericTones = listOf("Loving", "Neutral", "Cranky")
    )
)