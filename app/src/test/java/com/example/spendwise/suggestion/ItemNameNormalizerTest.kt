package com.example.spendwise.suggestion

import org.junit.Assert.assertEquals
import org.junit.Test

class ItemNameNormalizerTest {
    @Test
    fun normalizesCaseWhitespaceAndPunctuationVariants() {
        assertEquals("panadol extra", ItemNameNormalizer.normalize("  Panadol   Extra "))
        assertEquals(
            ItemNameNormalizer.normalize("FOLIC ACID 600 MCG"),
            ItemNameNormalizer.normalize("FOLIC-ACID 600 MCG")
        )
        assertEquals("panadol", ItemNameNormalizer.normalize("P A N A D O L"))
    }

    @Test
    fun preservesArabicAndNormalizesArabicAndPersianDigits() {
        assertEquals("بانادول 600", ItemNameNormalizer.normalize("  بانادولـ، ٦٠٠  "))
        assertEquals("فيتامين 123", ItemNameNormalizer.normalize("فيتامين ۱۲۳"))
    }
}
