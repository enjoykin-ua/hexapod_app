package io.github.enjoykinua.hexapod

/**
 * Reine Alerts-Listen-Logik (Phase 5, P5.12) — per JUnit **ohne Gerät/Netz** testbar
 * ([AlertLogicTest]). Quelle = `/hexapod/alerts` (ein Alert je Nachricht, latched Historie 50).
 * Die App akkumuliert eine Liste (neueste oben), dedupliziert + deckelt; „Alles kopieren" formatiert.
 */

/** App-seitiger Deckel der Alert-Liste (= ROS-Historie, Contract §6a). */
const val ALERTS_CAP = 50

/** Dedup-Schlüssel eines Alerts (verhindert Doppel beim latched-Batch nach (Re-)Subscribe). */
fun alertKey(a: Alert): String = "${a.stamp}|${a.name}|${a.msg}"

/**
 * Einen Alert vorne einfügen (neueste oben), auf [cap] deckeln. Duplikate (gleicher [alertKey])
 * werden ignoriert → die latched Historie erzeugt keine Dopplungen.
 */
fun appendAlert(current: List<Alert>, alert: Alert, cap: Int = ALERTS_CAP): List<Alert> {
    if (current.any { alertKey(it) == alertKey(alert) }) return current
    return (listOf(alert) + current).take(cap)
}

/** Alle Alerts als kopierbarer Text (neueste oben, eine Zeile je Alert). */
fun alertsToClipboard(alerts: List<Alert>): String =
    alerts.joinToString("\n") { "[${it.level}] ${it.name}: ${it.msg}" }
