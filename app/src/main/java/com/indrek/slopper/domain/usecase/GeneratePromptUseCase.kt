package com.indrek.slopper.domain.usecase

import com.indrek.slopper.data.PromptRepository
import com.indrek.slopper.domain.model.PromptUIDefinition
import javax.inject.Inject

/**
 * Use case for generating LLM prompts based on category, action/tone, and context.
 * Encapsulates the business logic for prompt construction with templates and placeholders.
 */
class GeneratePromptUseCase @Inject constructor(
    private val promptRepository: PromptRepository
) {

    /**
     * Generates a complete prompt for the LLM.
     *
     * @param categoryName The category (e.g., "POST", "CHAT", "MAIL")
     * @param actionOrTone The specific action label or tone (e.g., "Answer", "Question", "Humor")
     * @param contextText The user's selected text context
     * @return The complete formatted prompt ready for the LLM
     */
    operator fun invoke(
        categoryName: String,
        actionOrTone: String,
        contextText: String
    ): String {
        // Sanitize context text for safe inclusion in prompt
        val sanitizedContext = sanitizeContext(contextText)

        // Get the prompt definition for this category (with fallback to POST)
        val definition = promptRepository.getPromptDefinition(categoryName)
            ?: promptRepository.getPromptDefinition("POST")!!

        // Build the prompt body
        val body = buildPromptBody(
            categoryName = categoryName,
            actionOrTone = actionOrTone,
            sanitizedContext = sanitizedContext,
            definition = definition
        )

        // Get and apply the prefix
        val prefix = promptRepository.getPromptPrefix(categoryName)
            ?: definition.promptPrefix

        return prefix + body
    }

    private fun buildPromptBody(
        categoryName: String,
        actionOrTone: String,
        sanitizedContext: String,
        definition: PromptUIDefinition
    ): String {
        // Check if it's a default specific action
        val specificAction = definition.specificActions
            .find { it.label.equals(actionOrTone, ignoreCase = true) }

        if (specificAction != null) {
            return buildSpecificActionPrompt(
                categoryName = categoryName,
                actionLabel = specificAction.label,
                defaultTemplate = specificAction.template,
                sanitizedContext = sanitizedContext
            )
        }

        // Check if it's a custom specific action
        val customActions = promptRepository.getCustomActions(categoryName)
        val isCustomAction = customActions.any { it.equals(actionOrTone, ignoreCase = true) }

        if (isCustomAction) {
            return buildCustomActionPrompt(
                categoryName = categoryName,
                actionLabel = actionOrTone,
                sanitizedContext = sanitizedContext
            )
        }

        // Must be a generic tone
        return buildGenericTonePrompt(
            categoryName = categoryName,
            tone = actionOrTone,
            sanitizedContext = sanitizedContext,
            definition = definition
        )
    }

    private fun buildSpecificActionPrompt(
        categoryName: String,
        actionLabel: String,
        defaultTemplate: String,
        sanitizedContext: String
    ): String {
        val template = promptRepository.getPromptTemplate(categoryName, actionLabel)
            ?: defaultTemplate

        return template.replace("{CONTEXT}", sanitizedContext, ignoreCase = true)
    }

    private fun buildCustomActionPrompt(
        categoryName: String,
        actionLabel: String,
        sanitizedContext: String
    ): String {
        val template = promptRepository.getPromptTemplate(categoryName, actionLabel)
            ?: "Custom prompt for {CONTEXT}"

        return template.replace("{CONTEXT}", sanitizedContext, ignoreCase = true)
    }

    private fun buildGenericTonePrompt(
        categoryName: String,
        tone: String,
        sanitizedContext: String,
        definition: PromptUIDefinition
    ): String {
        val template = promptRepository.getGenericToneTemplate(categoryName)
            ?: definition.genericToneTemplate

        val messageType = when (categoryName) {
            "POST" -> "reply"
            "CHAT" -> "chat reply"
            else -> "message"
        }

        return template
            .replace("{CONTEXT}", sanitizedContext, ignoreCase = true)
            .replace("{TONE}", tone.lowercase(), ignoreCase = true)
            .replace("{MESSAGE_TYPE}", messageType, ignoreCase = true)
            .replace("{MESSAGE_TYPE_CAP}", messageType.replaceFirstChar { it.uppercase() }, ignoreCase = true)
    }

    private fun sanitizeContext(contextText: String): String {
        // Escape quotes to prevent prompt injection
        return "\"${contextText.replace("\"", "\\\"")}\""
    }
}