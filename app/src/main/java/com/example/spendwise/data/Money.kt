package com.example.spendwise.data

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.text.NumberFormat

fun parseEgpToMinor(input: String): Long? = try {
    BigDecimal(input.trim())
        .movePointRight(2)
        .setScale(0, RoundingMode.UNNECESSARY)
        .longValueExact()
} catch (_: ArithmeticException) {
    null
} catch (_: NumberFormatException) {
    null
}

fun formatEgp(amountMinor: Long): String {
    val absolute = BigInteger.valueOf(amountMinor).abs()
    val parts = absolute.divideAndRemainder(BigInteger.valueOf(100))
    val sign = if (amountMinor < 0) "-" else ""
    val whole = NumberFormat.getIntegerInstance().format(parts[0])
    val fraction = parts[1].toInt()
    return if (fraction == 0) {
        "$sign$whole EGP"
    } else {
        "$sign$whole.${fraction.toString().padStart(2, '0')} EGP"
    }
}
