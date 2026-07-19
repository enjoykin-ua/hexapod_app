package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-Tests der reinen Safety-Logik ([SafetyLogic]) gegen interface_contract.md v0.10 §2/§6a.
 * Kein Gerät/Netz. Bewusst NICHT hier: der echte `call_service` ([RosbridgeClient]) + das
 * Composable-Rendering ([DriveScreen]) — Sim-E2E.
 */
class SafetyLogicTest {

    // --- safetyMode: FROZEN hat Vorrang (frozen NUR aus status.safety_frozen) ---

    @Test fun frozen_wins_regardless_of_state_or_recover() {
        assertEquals(SafetyMode.FROZEN, safetyMode(frozen = true, state = "WALKING", recoverRequested = false))
        assertEquals(SafetyMode.FROZEN, safetyMode(frozen = true, state = "STARTUP_RAMP", recoverRequested = true))
        assertEquals(SafetyMode.FROZEN, safetyMode(frozen = true, state = "STANDING", recoverRequested = true))
    }

    // --- RECOVERING: nur wenn Recover getappt UND noch nicht STANDING ---

    @Test fun recovering_while_ramping_after_recover_tap() {
        assertEquals(SafetyMode.RECOVERING, safetyMode(frozen = false, state = "STARTUP_RAMP", recoverRequested = true))
        assertEquals(SafetyMode.RECOVERING, safetyMode(frozen = false, state = "CARTESIAN_STANDUP", recoverRequested = true))
    }

    @Test fun standing_reached_after_recover_is_normal() {
        assertEquals(SafetyMode.NORMAL, safetyMode(frozen = false, state = "STANDING", recoverRequested = true))
    }

    // --- NORMAL: normaler Stand-up (ohne Recover-Tap) ist KEIN recovering ---

    @Test fun normal_standup_ramp_is_not_recovering() {
        assertEquals(SafetyMode.NORMAL, safetyMode(frozen = false, state = "STARTUP_RAMP", recoverRequested = false))
    }

    @Test fun no_status_is_normal() {
        assertEquals(SafetyMode.NORMAL, safetyMode(frozen = false, state = null, recoverRequested = true))
    }

    // --- Service-Namen exakt gegen Contract §2 gepinnt (Tippfehler-Regression) ---

    @Test fun service_names_match_contract() {
        assertEquals("/hexapod_estop", ESTOP_SERVICE)
        assertEquals("/hexapod_recover", RECOVER_SERVICE)
    }
}
