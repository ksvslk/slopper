package com.indrek.slopper.data

import android.content.Context
import android.content.SharedPreferences
import com.indrek.slopper.domain.model.PromptUIDefinition
import com.indrek.slopper.domain.model.defaultCategoryNames
import com.indrek.slopper.domain.model.getCustomActionsKey
import com.indrek.slopper.domain.model.getGenericTonesKey
import com.indrek.slopper.domain.model.getHiddenDefaultActionsKey
import com.indrek.slopper.domain.model.getHiddenDefaultTonesKey
import com.indrek.slopper.domain.model.getPromptTemplateKey
import com.indrek.slopper.domain.model.promptDefinitions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit
import com.indrek.slopper.C.PREFS_NAME

const val PREF_KEY_ACTIVE_CATEGORIES = "active_categories"

/**
 * Repository for prompt-related data operations.
 * Handles SharedPreferences access for prompt templates and configurations.
 */
@Singleton
class PromptRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences( PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets the active categories enabled by the user.
     */
    fun getActiveCategories(): List<String> {
        var categories = prefs.getStringSet(PREF_KEY_ACTIVE_CATEGORIES, null)
        if (categories == null) {
            categories = defaultCategoryNames.toSet()
            prefs.edit { putStringSet(PREF_KEY_ACTIVE_CATEGORIES, categories) }
        }
        return categories.toList()
    }

    /**
     * Gets all action labels for a specific category (including custom actions and tones).
     */
    fun getActionLabels(categoryName: String): List<String> {
        val definition = promptDefinitions[categoryName] ?: promptDefinitions["POST"]!!

        val hiddenDefaultActions = getHiddenDefaultActions(categoryName)
        val customActionLabels = getCustomActions(categoryName)
        val hiddenDefaultTones = getHiddenDefaultTones(categoryName)
        val customToneLabels = getCustomTones(categoryName)

        val visibleDefaultActions = definition.specificActions
            .map { it.label }
            .filterNot { it in hiddenDefaultActions }

        val visibleDefaultTones = definition.genericTones
            .filterNot { it in hiddenDefaultTones }

        return visibleDefaultActions + customActionLabels + visibleDefaultTones + customToneLabels
    }

    /**
     * Gets a prompt template for a specific key.
     * Returns null if not found (caller should use default).
     */
    fun getPromptTemplate(categoryName: String, templateKey: String): String? {
        val key = getPromptTemplateKey(categoryName, templateKey)
        return prefs.getString(key, null)
    }

    /**
     * Gets the prompt prefix for a category.
     */
    fun getPromptPrefix(categoryName: String): String? {
        val key = getPromptTemplateKey(categoryName, "prefix")
        return prefs.getString(key, null)
    }

    /**
     * Gets the generic tone template for a category.
     */
    fun getGenericToneTemplate(categoryName: String): String? {
        val key = getPromptTemplateKey(categoryName, "generic")
        return prefs.getString(key, null)
    }

    /**
     * Gets custom action labels for a category.
     */
    fun getCustomActions(categoryName: String): Set<String> {
        return prefs.getStringSet(getCustomActionsKey(categoryName), emptySet()) ?: emptySet()
    }

    /**
     * Gets hidden default action labels for a category.
     */
    fun getHiddenDefaultActions(categoryName: String): Set<String> {
        return prefs.getStringSet(getHiddenDefaultActionsKey(categoryName), emptySet()) ?: emptySet()
    }

    /**
     * Gets custom tone labels for a category.
     */
    fun getCustomTones(categoryName: String): Set<String> {
        return prefs.getStringSet(getGenericTonesKey(categoryName), emptySet()) ?: emptySet()
    }

    /**
     * Gets hidden default tone labels for a category.
     */
    fun getHiddenDefaultTones(categoryName: String): Set<String> {
        return prefs.getStringSet(getHiddenDefaultTonesKey(categoryName), emptySet()) ?: emptySet()
    }

    /**
     * Gets the prompt UI definition for a category.
     * Returns null if category doesn't have a definition.
     */
    fun getPromptDefinition(categoryName: String): PromptUIDefinition? {
        return promptDefinitions[categoryName]
    }

    /**
     * Gets all default prompt definitions.
     */
    fun getAllPromptDefinitions(): Map<String, PromptUIDefinition> {
        return promptDefinitions
    }
}