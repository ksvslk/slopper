package com.indrek.slopper

import android.content.SharedPreferences
import com.indrek.slopper.C.FREE_TIER_LIMIT
import com.indrek.slopper.data.UserLimitRepository
import com.indrek.slopper.domain.LimitCheckResult
import com.indrek.slopper.ui.SlopperActivity.Companion.KEY_DAILY_LIMIT_COUNT
import com.indrek.slopper.ui.SlopperActivity.Companion.KEY_DAILY_LIMIT_DAY
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SIMPLEST APPROACH: Tests that work with your existing UserLimitRepository
 *
 * You only need to add two files to make this work:
 * 1. UserLimitData.kt (data class)
 * 2. LimitCheckResult.kt (sealed class)
 *
 * No other production code changes needed!
 */
class UserLimitRepositoryTest {

    private lateinit var repository: UserLimitRepository
    private lateinit var mockApp: SlopperApplication
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    private val testTime = 1700000000000L
    private val testDay = Calendar.getInstance().apply {
        timeInMillis = testTime
    }.get(Calendar.DAY_OF_YEAR)

    @Before
    fun setup() {
        mockPrefs = mockk<SharedPreferences>()
        mockEditor = mockk<SharedPreferences.Editor>()
        mockApp = mockk<SlopperApplication>()

        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putInt(any<String>(), any<Int>()) } returns mockEditor
        every { mockEditor.apply() } just Runs

        repository = UserLimitRepository(mockApp, mockPrefs)
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // Helper function to setup the time client mock
    private fun setupTime(millis: Long?) {
        if (millis == null) {
            every { mockApp.trustedTimeClient } returns null
        } else {
            // Just mock that the property returns something
            // The actual library object doesn't matter for unit tests
            every { mockApp.trustedTimeClient } returns mockk(relaxed = true) {
                every {
                    this@mockk.javaClass
                        .getDeclaredMethod("computeCurrentUnixEpochMillis")
                        .apply { isAccessible = true }
                        .invoke(this@mockk) as? Long
                } returns millis
            }
        }
    }

    @Test
    fun `time not ready returns isReady false`() {
        every { mockApp.trustedTimeClient } returns null

        val result = repository.getLimitData()

        assertFalse(result.isReady)
    }

    @Test
    fun `time not ready returns TimeNotReady result`() {
        every { mockApp.trustedTimeClient } returns null

        val result = repository.checkAndIncrementLimit()

        assertTrue(result is LimitCheckResult.TimeNotReady)
    }

    @Test
    fun `new day resets counter to 1`() {
        setupTime(testTime)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns testDay - 1
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns 5

        val result = repository.checkAndIncrementLimit()

        assertTrue(result is LimitCheckResult.Success)
        verify { mockEditor.putInt(KEY_DAILY_LIMIT_COUNT, 1) }
        verify { mockEditor.putInt(KEY_DAILY_LIMIT_DAY, testDay) }
    }

    @Test
    fun `same day increments counter`() {
        setupTime(testTime)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns testDay
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns 2

        val result = repository.checkAndIncrementLimit()

        assertTrue(result is LimitCheckResult.Success)
        verify { mockEditor.putInt(KEY_DAILY_LIMIT_COUNT, 3) }
    }

    @Test
    fun `at limit returns LimitReached`() {
        setupTime(testTime)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns testDay
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns FREE_TIER_LIMIT

        val result = repository.checkAndIncrementLimit()

        assertTrue(result is LimitCheckResult.LimitReached)
    }

    @Test
    fun `getLimitData shows correct remaining`() {
        setupTime(testTime)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns testDay
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns 3

        val result = repository.getLimitData()

        assertEquals(FREE_TIER_LIMIT - 3, result.remaining)
        assertEquals(FREE_TIER_LIMIT, result.total)
        assertTrue(result.isReady)
    }
}