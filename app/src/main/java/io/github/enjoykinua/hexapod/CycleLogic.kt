package io.github.enjoykinua.hexapod

/**
 * Reine cycle-to-target-Logik (Phase 5, P5.12) — per JUnit **ohne Gerät/Netz** testbar
 * ([CycleLogicTest]). Stance + Tempo werden **index-basiert** zum Ziel gecyclet (symmetrisch,
 * Contract §6a): die App liest den Ist-Index (`stance_idx`/`tempo_idx`) und ruft den SetBool-Service
 * mit der Richtung so oft, bis der Ziel-Index erreicht ist. Die Orchestrierung (async, auf
 * Topic-Update warten) liegt in der Activity; **die Schritt-Entscheidung ist hier rein**.
 */

/** Die zwei index-basierten cycle-to-target-Arten + ihr SetBool-Service (Contract §2/§6a). */
enum class CycleKind(val service: String) {
    STANCE("/hexapod_cycle_stance"),
    TEMPO("/hexapod_cycle_tempo"),
}

/**
 * Richtung des nächsten Cycle-Schritts vom Ist- zum Ziel-Index: `true` = einen Schritt **hoch**
 * (höher/schneller, SetBool `data=true`), `false` = **runter**, `null` = **erreicht** (fertig).
 */
fun nextCycleStep(current: Int, target: Int): Boolean? = when {
    current < target -> true
    current > target -> false
    else -> null
}
