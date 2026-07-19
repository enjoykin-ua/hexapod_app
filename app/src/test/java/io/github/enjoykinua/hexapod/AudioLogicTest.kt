package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-Tests der reinen Audio-Konstanten ([AudioLogic]) gegen interface_contract.md v0.11/§6b.
 * Kein Gerät/Netz. Bewusst NICHT hier: der echte advertise/publish/set_parameters ([RosbridgeClient])
 * + Compose-Rendering ([DriveScreen]) — Sim-E2E.
 */
class AudioLogicTest {

    @Test fun interface_names_match_contract() {
        assertEquals("/hexapod/play_sound", PLAY_SOUND_TOPIC)
        assertEquals("/hexapod/sound_enabled", SOUND_ENABLED_TOPIC)
        assertEquals("/hexapod_audio", AUDIO_NODE)
        assertEquals("sound_enable", SOUND_ENABLE_PARAM)
    }

    @Test fun soundboard_has_three_contract_keys() {
        assertEquals(3, SOUNDBOARD.size)
        assertEquals(listOf("sound_01", "sound_02", "sound_03"), SOUNDBOARD.map { it.key })
        assertEquals(listOf("Sound 1", "Sound 2", "Sound 3"), SOUNDBOARD.map { it.label })
    }
}
