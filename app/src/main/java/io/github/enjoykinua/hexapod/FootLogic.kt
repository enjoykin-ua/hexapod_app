package io.github.enjoykinua.hexapod

/**
 * Reine Fuß-Kontakt-Logik (Phase 5, P5.10) — per JUnit **ohne Gerät/Netz** testbar
 * ([FootLogicTest]). Quelle = `/foot_contacts` (`std_msgs/Float64MultiArray`, 6× 0/1, Contract §3).
 *
 * Bewusst getrennt vom org.json-Glue ([parseFootContactsRaw] in [HmiProtocol]): **nur diese reine
 * Schicht** wird unit-getestet; der Rohwert-Zug aus dem JSON ist Integration.
 */

/** Anzahl Beine (2×3-Raster im Overlay). */
const val FOOT_COUNT = 6

/** Kontakt-Schwelle: `data[i]` ist 0.0/1.0, aber robust gegen Float-Rauschen (`≥ 0.5`). */
const val FOOT_CONTACT_THRESHOLD = 0.5

/**
 * Roh-Array (`data`) → genau [FOOT_COUNT] Kontakt-Booleans (`true` = Bodenkontakt). **Tolerant:**
 * fehlende Werte (kürzeres/leeres Array) → `false` (kein Kontakt / noch keine Daten); überzählige
 * Werte werden ignoriert. So gibt es nie einen Index-Fehler im Overlay.
 */
fun footContacts(data: List<Double>): List<Boolean> =
    List(FOOT_COUNT) { i -> (data.getOrNull(i) ?: 0.0) >= FOOT_CONTACT_THRESHOLD }
