package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Reine Fuß-Kontakt-Logik ([footContacts]) — ohne Gerät/Netz. Prüft Schwelle + Robustheit gegen
 * abweichende Array-Längen (Contract §3: 6× 0/1).
 */
class FootLogicTest {

    @Test
    fun `sechs Werte ergeben Kontakt per Schwelle`() {
        assertEquals(
            listOf(false, true, false, true, true, false),
            footContacts(listOf(0.0, 1.0, 0.0, 1.0, 1.0, 0.0)),
        )
    }

    @Test
    fun `Schwelle bei genau 0_5`() {
        // 0.49 unter 0.5 = kein Kontakt; 0.5 und 1.0 = Kontakt
        assertEquals(
            listOf(false, true, true, false, false, false),
            footContacts(listOf(0.49, 0.5, 1.0, 0.0, 0.0, 0.0)),
        )
    }

    @Test
    fun `leeres Array ergibt sechs mal kein Kontakt`() {
        assertEquals(List(FOOT_COUNT) { false }, footContacts(emptyList()))
    }

    @Test
    fun `kuerzeres Array fuellt Rest mit false ohne Index-Fehler`() {
        assertEquals(
            listOf(true, true, false, false, false, false),
            footContacts(listOf(1.0, 1.0)),
        )
    }

    @Test
    fun `laengeres Array wird auf sechs beschnitten`() {
        val result = footContacts(listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0))
        assertEquals(FOOT_COUNT, result.size)
        assertEquals(List(FOOT_COUNT) { true }, result)
    }
}
