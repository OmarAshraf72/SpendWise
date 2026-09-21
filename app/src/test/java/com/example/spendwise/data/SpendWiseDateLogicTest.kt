package com.example.spendwise.data

import java.time.LocalDate
import java.time.Month
import org.junit.Assert.assertEquals
import org.junit.Test

class SpendWiseDateLogicTest {
    @Test fun changingYearPreservesMonthAndDayWhenValid() {
        assertEquals(LocalDate.parse("2030-09-21"), SpendWiseDateLogic.changeYear(LocalDate.parse("2027-09-21"), 2030))
    }

    @Test fun changingMonthPreservesYearAndClampsJanuary31ToFebruary() {
        assertEquals(LocalDate.parse("2026-02-28"), SpendWiseDateLogic.changeMonth(LocalDate.parse("2026-01-31"), Month.FEBRUARY))
        assertEquals(LocalDate.parse("2026-03-21"), SpendWiseDateLogic.changeMonth(LocalDate.parse("2026-09-21"), Month.MARCH))
    }

    @Test fun leapYearFebruaryAndFarFutureYearAreSafe() {
        assertEquals(LocalDate.parse("2028-02-29"), SpendWiseDateLogic.changeMonth(LocalDate.parse("2028-01-31"), Month.FEBRUARY))
        assertEquals(LocalDate.parse("2056-02-29"), SpendWiseDateLogic.changeYear(LocalDate.parse("2028-02-29"), 2056))
        assertEquals(LocalDate.parse("2030-03-15"), SpendWiseDateLogic.changeYear(LocalDate.parse("2026-03-15"), 2030))
    }
}
