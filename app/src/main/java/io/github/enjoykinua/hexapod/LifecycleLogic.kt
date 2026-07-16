package io.github.enjoykinua.hexapod

/**
 * Reine, framework-freie Lifecycle-Logik (Phase 3, Option A) — per JUnit **ohne Gerät/Netz**
 * testbar ([LifecycleLogicTest]). Interface = interface_contract.md v0.6 §2a (Launcher-Services,
 * alle `std_srvs/Trigger`).
 *
 * Bewusst getrennt vom Compose-Halter [LifecycleState] und vom org.json-/Netz-Teil
 * ([RosbridgeProtocol]/[RosbridgeClient]) — **nur diese reine Schicht** wird unit-getestet
 * (org.json ist ein Android-SDK-Stub → in reinem JUnit nicht lauffähig, wie bei Phase 2).
 */

/** Zustand des schweren On-Demand-Stacks, wie ihn der Launcher meldet. */
enum class StackState { UNKNOWN, STOPPED, RUNNING }

/**
 * Die vom Nutzer auslösbaren Lifecycle-Aktionen → rosbridge-Service (Contract v0.6 §2a).
 * `label` = Button-/Meldungstext. Das **Status-Polling** ist keine Aktion hier → separat als
 * [BRINGUP_STATUS_SERVICE] (setzt kein `pendingAction`, sperrt nicht die ganze UI).
 */
enum class LifecycleAction(val service: String, val label: String) {
    START("/hexapod_bringup_start", "Hexapod starten"),
    STOP("/hexapod_bringup_stop", "Hexapod stoppen"),
    STAND_UP("/hexapod_stand_up", "Aufstehen"),
    SIT_DOWN("/hexapod_sit_down", "Hinsetzen"),
    PI_SHUTDOWN("/hexapod_pi_shutdown", "Pi ausschalten"),
}

/** Status-Service (Polling, Option A): `message` = "running (pid=…)" / "stopped". */
const val BRINGUP_STATUS_SERVICE = "/hexapod_bringup_status"

/**
 * `message` einer `/hexapod_bringup_status`-Antwort → [StackState]. Rein textbasiert
 * (case-insensitiv, substring): "running (pid=1234)" → RUNNING, "stopped" → STOPPED, sonst
 * UNKNOWN (inkl. `null` = keine/gescheiterte Antwort).
 */
fun interpretStatus(message: String?): StackState {
    if (message == null) return StackState.UNKNOWN
    val m = message.lowercase()
    return when {
        "running" in m -> StackState.RUNNING
        "stopped" in m -> StackState.STOPPED
        else -> StackState.UNKNOWN
    }
}

/**
 * Aktivierung der Aktions-Buttons aus (Verbindungs- × Stack-Zustand × laufender Call). Rein →
 * unit-getestet. Basis für alle: **verbunden** und **kein Call in Arbeit**.
 * - START: nur wenn der Stack nicht schon läuft (idempotent, aber UI-seitig sperren bei RUNNING).
 * - STOP/STAND_UP/SIT_DOWN: nur bei laufendem Stack.
 * - PI_SHUTDOWN: immer, solange verbunden & idle (2-stufiger Bestätigungsdialog davor).
 */
fun buttonEnablement(
    conn: ConnState,
    stack: StackState,
    pending: LifecycleAction?,
): Map<LifecycleAction, Boolean> {
    val base = conn == ConnState.CONNECTED && pending == null
    return mapOf(
        LifecycleAction.START to (base && stack != StackState.RUNNING),
        LifecycleAction.STOP to (base && stack == StackState.RUNNING),
        LifecycleAction.STAND_UP to (base && stack == StackState.RUNNING),
        LifecycleAction.SIT_DOWN to (base && stack == StackState.RUNNING),
        LifecycleAction.PI_SHUTDOWN to base,
    )
}
