package io.github.enjoykinua.hexapod

/**
 * Reine, framework-freie Safety-Logik (Phase 6, E-Stop + Recover) — per JUnit **ohne Gerät/Netz**
 * testbar ([SafetyLogicTest]). Interface = interface_contract.md **v0.10 §2** (`/hexapod_estop`,
 * `/hexapod_recover`, beide `std_srvs/srv/Trigger`) + **§6a** (`/hexapod/status.safety_frozen`) —
 * hier **referenziert**, nicht kopiert ([D10]). Grenze: [decisions.md D6].
 *
 * Bewusst getrennt vom Compose-UI ([DriveScreen]) und vom Netz-Teil ([RosbridgeClient]): **nur diese
 * reine Schicht** wird unit-getestet (der echte `call_service` ist org.json/Netz → Sim-E2E, wie
 * Phase 3/5). Das „Recover getappt?"-Flag lebt als dünne Compose-Glue im Screen; die **Entscheidung**
 * (welcher Anzeige-Modus) bleibt hier rein.
 */

/** App-Ziel des Not-Halts (gait_node, wirkt Sim UND HW; triggert intern den Plugin-Freeze). */
const val ESTOP_SERVICE = "/hexapod_estop"

/** App-Ziel der Ein-Klick-Recovery (gait_node, Joint-Space-Ramp in den Stand, [D6]). */
const val RECOVER_SERVICE = "/hexapod_recover"

/**
 * Anzeige-Modus der Safety-Overlays im Fahr-Screen, abgeleitet aus `/hexapod/status`
 * (+ „Recover bewusst getappt?"). [NORMAL] = kein Banner; [FROZEN] = E-Stop aktiv (Banner +
 * Recover-Button); [RECOVERING] = Recover läuft (Banner „recovering …", kein Button).
 */
enum class SafetyMode { NORMAL, FROZEN, RECOVERING }

/**
 * Anzeige-Modus bestimmen. [frozen] = `status.safety_frozen` (**einzige** Quelle des Freeze —
 * nie die Service-Response, Contract §6a). [state] = `status.state` (`null` = kein Status / Stack
 * aus). [recoverRequested] = wurde der Recover-Button seit dem letzten Freeze getappt?
 *
 * - [frozen] → [FROZEN] (Vorrang: ein neuer E-Stop während Recovery zeigt sofort wieder frozen).
 * - sonst Recover getappt und noch nicht `STANDING` → [RECOVERING]. Das `recoverRequested`-Gate
 *   verhindert, dass ein **normaler** Stand-up (der ebenfalls `STARTUP_RAMP` durchläuft) fälschlich
 *   als „recovering" erscheint.
 * - sonst (inkl. `state == null`) → [NORMAL].
 */
fun safetyMode(frozen: Boolean, state: String?, recoverRequested: Boolean): SafetyMode = when {
    frozen -> SafetyMode.FROZEN
    recoverRequested && state != null && state != StatusSnapshot.STATE_STANDING -> SafetyMode.RECOVERING
    else -> SafetyMode.NORMAL
}
