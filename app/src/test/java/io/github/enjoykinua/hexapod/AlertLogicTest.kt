package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Test

/** Reine Alerts-Listen-Logik ([appendAlert]/[alertsToClipboard]) — neueste oben, Dedup, Cap. */
class AlertLogicTest {

    private fun alert(stamp: Double = 1.0, level: String = "WARN", name: String = "gait_node", msg: String = "m") =
        Alert(stamp, level, name, msg)

    @Test
    fun `appendAlert setzt neueste oben`() {
        val a1 = alert(1.0, msg = "erst")
        val a2 = alert(2.0, msg = "zweit")
        val list = appendAlert(appendAlert(emptyList(), a1), a2)
        assertEquals(listOf(a2, a1), list)
    }

    @Test
    fun `appendAlert dedupliziert gleiche Alerts`() {
        val a = alert(1.0)
        val list = appendAlert(appendAlert(emptyList(), a), a)
        assertEquals(1, list.size)
    }

    @Test
    fun `appendAlert deckelt auf cap und behaelt die neuesten`() {
        var list = emptyList<Alert>()
        for (i in 1..60) list = appendAlert(list, alert(i.toDouble(), msg = "m$i"), cap = 50)
        assertEquals(50, list.size)
        assertEquals("m60", list.first().msg)  // neueste oben
        assertEquals("m11", list.last().msg)   // m1..m10 rausgefallen
    }

    @Test
    fun `alertsToClipboard formatiert eine Zeile je Alert`() {
        val out = alertsToClipboard(listOf(alert(1.0, level = "ERROR", name = "n", msg = "boom")))
        assertEquals("[ERROR] n: boom", out)
    }
}
