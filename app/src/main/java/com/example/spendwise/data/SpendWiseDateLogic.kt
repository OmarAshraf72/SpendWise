package com.example.spendwise.data

import java.time.LocalDate
import java.time.Month

object SpendWiseDateLogic {
    fun changeYear(date: LocalDate, year: Int): LocalDate = clamp(year, date.month, date.dayOfMonth)
    fun changeMonth(date: LocalDate, month: Month): LocalDate = clamp(date.year, month, date.dayOfMonth)

    private fun clamp(year: Int, month: Month, day: Int): LocalDate {
        val first = LocalDate.of(year, month, 1)
        return first.withDayOfMonth(day.coerceAtMost(first.lengthOfMonth()))
    }
}
