package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reine cycle-to-target-Schrittlogik ([nextCycleStep]) + Service-Zuordnung ([CycleKind]). */
class CycleLogicTest {

    @Test
    fun `nextCycleStep hoch runter und fertig`() {
        assertEquals(true, nextCycleStep(0, 2))   // hoch
        assertEquals(false, nextCycleStep(2, 0))  // runter
        assertNull(nextCycleStep(1, 1))           // erreicht
    }

    @Test
    fun `nextCycleStep ein Schritt`() {
        assertEquals(true, nextCycleStep(0, 1))
        assertEquals(false, nextCycleStep(1, 0))
    }

    @Test
    fun `CycleKind zeigt auf die richtigen SetBool-Services`() {
        assertEquals("/hexapod_cycle_stance", CycleKind.STANCE.service)
        assertEquals("/hexapod_cycle_tempo", CycleKind.TEMPO.service)
    }
}
