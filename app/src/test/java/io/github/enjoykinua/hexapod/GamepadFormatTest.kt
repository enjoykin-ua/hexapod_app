package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit-Tests fuer die reinen Helfer aus GamepadFormat.kt (kein Geraet noetig). */
class GamepadFormatTest {

    @Test
    fun hex4_padsToFourLowercaseDigits() {
        assertEquals("0x1532", hex4(KISHI_VENDOR_ID))
        assertEquals("0x071b", hex4(KISHI_PRODUCT_ID))
        assertEquals("0x0000", hex4(0))
    }

    @Test
    fun normalize_mapsBipolarStickAxis() {
        assertEquals(0.5f, normalize(0f, -1f, 1f), 1e-6f)
        assertEquals(1f, normalize(1f, -1f, 1f), 1e-6f)
        assertEquals(0f, normalize(-1f, -1f, 1f), 1e-6f)
    }

    @Test
    fun normalize_mapsUnipolarTriggerAxis() {
        assertEquals(0f, normalize(0f, 0f, 1f), 1e-6f)
        assertEquals(1f, normalize(1f, 0f, 1f), 1e-6f)
        assertEquals(0.25f, normalize(0.25f, 0f, 1f), 1e-6f)
    }

    @Test
    fun normalize_clampsOutOfRange() {
        assertEquals(1f, normalize(2f, -1f, 1f), 1e-6f)
        assertEquals(0f, normalize(-2f, -1f, 1f), 1e-6f)
    }

    @Test
    fun normalize_returnsZeroForEmptyRange() {
        assertEquals(0f, normalize(5f, 1f, 1f), 1e-6f)
    }

    @Test
    fun matchesKishi_trueOnlyForExactIds() {
        assertTrue(matchesKishi(KISHI_VENDOR_ID, KISHI_PRODUCT_ID))
        assertFalse(matchesKishi(KISHI_VENDOR_ID, 0x0001))
        assertFalse(matchesKishi(0x0000, KISHI_PRODUCT_ID))
    }
}
