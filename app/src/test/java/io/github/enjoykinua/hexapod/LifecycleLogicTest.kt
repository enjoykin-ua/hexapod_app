package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests der reinen Lifecycle-Logik ([LifecycleLogic]) gegen interface_contract.md v0.6 §2a.
 * Kein Gerät/Netz. Bewusst NICHT hier: der org.json-`call_service`-Rahmen + Parser
 * ([RosbridgeProtocol]) und der WebSocket-Transport ([RosbridgeClient]) — über die Integration.
 */
class LifecycleLogicTest {

    // --- U1: interpretStatus ---

    @Test fun status_running_with_pid() {
        assertEquals(StackState.RUNNING, interpretStatus("running (pid=1234)"))
    }

    @Test fun status_stopped() {
        assertEquals(StackState.STOPPED, interpretStatus("stopped"))
    }

    @Test fun status_is_case_insensitive() {
        assertEquals(StackState.RUNNING, interpretStatus("RUNNING (pid=1)"))
    }

    @Test fun status_null_empty_and_garbage_are_unknown() {
        assertEquals(StackState.UNKNOWN, interpretStatus(null))
        assertEquals(StackState.UNKNOWN, interpretStatus(""))
        assertEquals(StackState.UNKNOWN, interpretStatus("starting…"))
    }

    // --- U2: buttonEnablement ---

    @Test fun disconnected_disables_everything() {
        val e = buttonEnablement(ConnState.DISCONNECTED, StackState.RUNNING, null)
        assertTrue(e.values.all { !it })
    }

    @Test fun connecting_disables_everything() {
        val e = buttonEnablement(ConnState.CONNECTING, StackState.RUNNING, null)
        assertTrue(e.values.all { !it })
    }

    @Test fun a_pending_call_disables_everything() {
        val e = buttonEnablement(ConnState.CONNECTED, StackState.RUNNING, LifecycleAction.STAND_UP)
        assertTrue(e.values.all { !it })
    }

    @Test fun connected_stopped_allows_only_start_and_shutdown() {
        val e = buttonEnablement(ConnState.CONNECTED, StackState.STOPPED, null)
        assertTrue(e.getValue(LifecycleAction.START))
        assertTrue(e.getValue(LifecycleAction.PI_SHUTDOWN))
        assertFalse(e.getValue(LifecycleAction.STOP))
        assertFalse(e.getValue(LifecycleAction.STAND_UP))
        assertFalse(e.getValue(LifecycleAction.SIT_DOWN))
    }

    @Test fun connected_running_allows_stop_stand_sit_shutdown_but_not_start() {
        val e = buttonEnablement(ConnState.CONNECTED, StackState.RUNNING, null)
        assertFalse(e.getValue(LifecycleAction.START))
        assertTrue(e.getValue(LifecycleAction.STOP))
        assertTrue(e.getValue(LifecycleAction.STAND_UP))
        assertTrue(e.getValue(LifecycleAction.SIT_DOWN))
        assertTrue(e.getValue(LifecycleAction.PI_SHUTDOWN))
    }

    @Test fun connected_unknown_allows_start_and_shutdown() {
        val e = buttonEnablement(ConnState.CONNECTED, StackState.UNKNOWN, null)
        assertTrue(e.getValue(LifecycleAction.START))
        assertTrue(e.getValue(LifecycleAction.PI_SHUTDOWN))
        assertFalse(e.getValue(LifecycleAction.STOP))
    }
}
