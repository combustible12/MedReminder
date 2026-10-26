package com.combustible12.medreminder

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class RulesTest {
    @Test fun useDurationCountsBothStartAndEndDates() {
        assertEquals(1L, UsePeriod(medicineId = 1, start = "2026-10-26", end = "2026-10-26", active = false).durationDays(LocalDate.parse("2026-11-01")))
        assertEquals(3L, UsePeriod(medicineId = 1, start = "2026-10-24").durationDays(LocalDate.parse("2026-10-26")))
        assertNull(UsePeriod(medicineId = 1).durationDays(LocalDate.parse("2026-10-26")))
    }
    @Test fun optionalFrequencyUsesDigitsAndRejectsInvalidCounts() {
        assertEquals("", normalizeDailyFrequency(""))
        assertEquals("2", normalizeDailyFrequency("02"))
        assertEquals("1天3次", Medicine(name = "药", dailyFrequency = "3").frequencyLabel())
        assertEquals("", Medicine(name = "药").frequencyLabel())
        listOf("0", "-1", "1.5", "两次").forEach { assertTrue(runCatching { normalizeDailyFrequency(it) }.isFailure) }
    }

    @Test fun usageLineKeepsSpecificationDoseFrequencyAndMealTimingTogether() {
        assertEquals("20mg · 1天3次 · 每次1片 · 餐后", Medicine(name = "药", specification = "20mg", defaultDose = "1片", dailyFrequency = "3", mealTiming = "餐后").usageLine())
        assertEquals("20mg", Medicine(name = "药", specification = "20mg").usageLine())
        assertEquals("餐前", normalizeMealTiming(" 餐前 "))
        assertTrue(runCatching { normalizeMealTiming("睡前") }.isFailure)
    }

    @Test fun threeDoseCardsMatchMorningNoonAndEveningInOrder() {
        val cards = listOf(NoteCard(9, "晚间", position = 0), NoteCard(8, "早晨", position = 1), NoteCard(7, "中午", position = 2))
        assertEquals(listOf(8L, 7L, 9L), automaticThreeDoseCards(cards))
        assertNull(automaticThreeDoseCards(cards.dropLast(1)))
    }

    @Test fun blankTimeDoesNotDisplay() { assertNull(Reminder(1).time()); assertEquals("08:05", Reminder(1, 8, 5).time()) }
    @Test fun identityUsesNameAndSpecificationOnly() { assertEquals(identityOf(" 药品 A ", "20mg"), identityOf("药品 A", "20mg ")); assertNotEquals(identityOf("药品 A", "20mg"), identityOf("药品 A", "40mg")) }
    @Test fun nextDailyAlarmUsesTomorrowWhenTimePassed() {
        val now = ZonedDateTime.parse("2026-10-20T08:00:00+08:00[Asia/Shanghai]")
        assertEquals("2026-10-21T08:00+08:00[Asia/Shanghai]", nextReminder(8, 0, now).toString())
        assertEquals("2026-10-20T08:30+08:00[Asia/Shanghai]", nextReminder(8, 30, now).toString())
    }
    @Test fun dailyAlarmKeepsWallClockAcrossDST() {
        val now = ZonedDateTime.parse("2026-10-31T09:00:00-04:00[America/New_York]")
        val next = nextReminder(8, 0, now)
        assertEquals(8, next.hour); assertEquals(1, next.dayOfMonth); assertEquals(-5 * 3600, next.offset.totalSeconds)
    }
    @Test fun optionalDatesAndMultiplePeriods() { validateDates(null, null); validateDates("2026-09-01", "2026-09-15") }
    @Test(expected = IllegalArgumentException::class) fun reversedDatesRejected() { validateDates("2026-10-20", "2026-10-19") }
}
