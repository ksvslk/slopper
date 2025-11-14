package com.indrek.slopper

import android.content.SharedPreferences
import com.indrek.slopper.C.FREE_TIER_LIMIT
import com.indrek.slopper.domain.LimitCheckResult
import com.indrek.slopper.data.UserLimitRepository
import com.indrek.slopper.ui.SlopperActivity.Companion.KEY_DAILY_LIMIT_COUNT
import com.indrek.slopper.ui.SlopperActivity.Companion.KEY_DAILY_LIMIT_DAY
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests specifically for verifying that users get fresh generations the next day.
 * This ensures the daily limit resets properly.
 */
class UserLimitRepositoryNextDayTest {

    private lateinit var repository: UserLimitRepository
    private lateinit var mockApp: SlopperApplication
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

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

    private fun setupTime(millis: Long?) {
        if (millis == null) {
            every { mockApp.trustedTimeClient } returns null
        } else {
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

    private fun getDayOfYear(millis: Long): Int {
        return Calendar.getInstance().apply {
            timeInMillis = millis
        }.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * CRITICAL TEST: User exhausts limit on Day 1, but gets fresh generations on Day 2
     */
    @Test
    fun `user who exhausted limit yesterday gets fresh generations today`() {
        // Day 1: November 14, 2025 at 11:59 PM
        val day1Time = Calendar.getInstance().apply {
            set(2025, Calendar.NOVEMBER, 14, 23, 59, 59)
        }.timeInMillis
        val day1 = getDayOfYear(day1Time)

        // Day 2: November 15, 2025 at 12:01 AM (next day)
        val day2Time = Calendar.getInstance().apply {
            set(2025, Calendar.NOVEMBER, 15, 0, 1, 0)
        }.timeInMillis
        val day2 = getDayOfYear(day2Time)

        // Simulate: User exhausted all generations on Day 1
        setupTime(day1Time)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns day1
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns FREE_TIER_LIMIT

        // Day 1: Try to generate - should be blocked
        val day1Result = repository.checkAndIncrementLimit()
        assertTrue(day1Result is LimitCheckResult.LimitReached, "Should be blocked on Day 1")

        // Day 1: Check limit shows 0 remaining
        val day1LimitData = repository.getLimitData()
        assertEquals(0, day1LimitData.remaining, "Should have 0 remaining on Day 1")

        // === MIDNIGHT PASSES ===
        // Day 2: Next day arrives
        setupTime(day2Time)

        // Day 2: Check limit data - should show full limit available
        val day2LimitData = repository.getLimitData()
        assertEquals(FREE_TIER_LIMIT, day2LimitData.remaining,
            "Should have FULL limit on Day 2")
        assertTrue(day2LimitData.isReady, "Should be ready on Day 2")

        // Day 2: Try to generate - should succeed!
        val day2Result = repository.checkAndIncrementLimit()
        assertTrue(day2Result is LimitCheckResult.Success,
            "Should allow generation on Day 2")

        // Verify the counter was reset to 1 (not incremented from FREE_TIER_LIMIT)
        verify { mockEditor.putInt(KEY_DAILY_LIMIT_COUNT, 1) }
        verify { mockEditor.putInt(KEY_DAILY_LIMIT_DAY, day2) }
    }

    /**
     * Test that partial usage on Day 1 still gives full limit on Day 2
     */
    @Test
    fun `user with partial usage on day 1 gets full limit on day 2`() {
        val day1Time = 1700000000000L
        val day1 = getDayOfYear(day1Time)

        val day2Time = day1Time + (24 * 60 * 60 * 1000) // +24 hours
        val day2 = getDayOfYear(day2Time)

        // Day 1: User used 3 out of FREE_TIER_LIMIT
        setupTime(day1Time)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns day1
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns 3

        val day1LimitData = repository.getLimitData()
        assertEquals(FREE_TIER_LIMIT - 3, day1LimitData.remaining)

        // Day 2: Next day
        setupTime(day2Time)

        val day2LimitData = repository.getLimitData()
        assertEquals(FREE_TIER_LIMIT, day2LimitData.remaining,
            "Should have FULL limit on Day 2, not (LIMIT - 3)")
    }

    /**
     * Test year rollover: Dec 31 -> Jan 1 should reset
     */
    @Test
    fun `limit resets when year changes from Dec 31 to Jan 1`() {
        // December 31, 2025
        val dec31Time = Calendar.getInstance().apply {
            set(2025, Calendar.DECEMBER, 31, 23, 59, 0)
        }.timeInMillis
        val dec31Day = getDayOfYear(dec31Time)

        // January 1, 2026
        val jan1Time = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 1, 0, 1, 0)
        }.timeInMillis
        val jan1Day = getDayOfYear(jan1Time)

        // Dec 31: Exhausted limit
        setupTime(dec31Time)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns dec31Day
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns FREE_TIER_LIMIT

        val dec31Result = repository.checkAndIncrementLimit()
        assertTrue(dec31Result is LimitCheckResult.LimitReached)

        // Jan 1: New year, new day
        setupTime(jan1Time)

        val jan1Result = repository.checkAndIncrementLimit()
        assertTrue(jan1Result is LimitCheckResult.Success,
            "Should allow generation on Jan 1 of new year")

        verify { mockEditor.putInt(KEY_DAILY_LIMIT_COUNT, 1) }
        verify { mockEditor.putInt(KEY_DAILY_LIMIT_DAY, jan1Day) }
    }

    /**
     * Test multiple generation attempts across day boundary
     */
    @Test
    fun `user can make multiple generations after day reset`() {
        val day1Time = 1700000000000L
        val day1 = getDayOfYear(day1Time)

        val day2Time = day1Time + (25 * 60 * 60 * 1000) // +25 hours
        val day2 = getDayOfYear(day2Time)

        // Day 1: Exhaust limit
        setupTime(day1Time)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns day1
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns FREE_TIER_LIMIT

        val day1Result = repository.checkAndIncrementLimit()
        assertTrue(day1Result is LimitCheckResult.LimitReached)

        // Day 2: Make multiple generations
        setupTime(day2Time)

        // Track the count as it increments
        var currentCount = 0
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns day2
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } answers { currentCount }
        every { mockEditor.putInt(KEY_DAILY_LIMIT_COUNT, any<Int>()) } answers {
            currentCount = secondArg()
            mockEditor
        }
        every { mockEditor.putInt(KEY_DAILY_LIMIT_DAY, any<Int>()) } returns mockEditor

        // First generation on Day 2 - should reset to 1
        val gen1 = repository.checkAndIncrementLimit()
        assertTrue(gen1 is LimitCheckResult.Success)
        assertEquals(1, currentCount)

        // Second generation on Day 2 - should increment to 2
        val gen2 = repository.checkAndIncrementLimit()
        assertTrue(gen2 is LimitCheckResult.Success)
        assertEquals(2, currentCount)

        // Third generation on Day 2 - should increment to 3
        val gen3 = repository.checkAndIncrementLimit()
        assertTrue(gen3 is LimitCheckResult.Success)
        assertEquals(3, currentCount)
    }

    /**
     * EDGE CASE: Ensure same day (different hours) doesn't reset
     */
    @Test
    fun `limit does NOT reset when time advances within same calendar day`() {
        // November 14, 2025 at 1:00 AM
        val morning = Calendar.getInstance().apply {
            set(2025, Calendar.NOVEMBER, 14, 1, 0, 0)
        }.timeInMillis

        // November 14, 2025 at 11:00 PM (same day, 22 hours later)
        val night = Calendar.getInstance().apply {
            set(2025, Calendar.NOVEMBER, 14, 23, 0, 0)
        }.timeInMillis

        val sameDay = getDayOfYear(morning)

        // Morning: Use 5 generations
        setupTime(morning)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns sameDay
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns 5

        val morningData = repository.getLimitData()
        assertEquals(FREE_TIER_LIMIT - 5, morningData.remaining)

        // Night: Same day, should still have (LIMIT - 5) not full LIMIT
        setupTime(night)
        // Note: Still returning same day and count

        val nightData = repository.getLimitData()
        assertEquals(FREE_TIER_LIMIT - 5, nightData.remaining,
            "Count should NOT reset within same calendar day")
    }

    /**
     * Test that getLimitData correctly shows available count after reset
     */
    @Test
    fun `getLimitData shows full limit available after day change`() {
        val yesterday = 1700000000000L
        val yesterdayDay = getDayOfYear(yesterday)

        val today = yesterday + (24 * 60 * 60 * 1000)

        // Yesterday: exhausted
        setupTime(yesterday)
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_DAY, -1) } returns yesterdayDay
        every { mockPrefs.getInt(KEY_DAILY_LIMIT_COUNT, 0) } returns FREE_TIER_LIMIT

        // Today: check BEFORE making any generations
        setupTime(today)

        val todayData = repository.getLimitData()

        assertEquals(FREE_TIER_LIMIT, todayData.remaining,
            "getLimitData should show full limit on new day")
        assertEquals(FREE_TIER_LIMIT, todayData.total)
        assertTrue(todayData.isReady)
    }
}