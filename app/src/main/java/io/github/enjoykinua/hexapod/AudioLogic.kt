package io.github.enjoykinua.hexapod

/**
 * Reine, framework-freie Audio-Konstanten (Phase 7A) — Contract §3/§4/§6b, hier **referenziert**,
 * nicht kopiert ([D10]). Wie [SafetyLogic]: die Interface-Strings an **einer** Stelle, per JUnit
 * gepinnt ([AudioLogicTest]), damit ein Tippfehler an den Publish-/Set-Stellen sofort auffällt.
 *
 * Sound spielt **nur** am Roboter-Speaker ([D5]); die App ist Auslöser/Umschalter. `sound_enable`
 * mutet **nur** die Auto-Bewegungs-Sounds — die Soundboard-Keys spielen **immer**.
 */

/** `std_msgs/String`, App → Roboter: Soundboard-Key publishen (spielt sofort, immer). §6b */
const val PLAY_SOUND_TOPIC = "/hexapod/play_sound"

/** `std_msgs/Bool`, latched, Roboter → App: aktueller Auto-Sound-Mute-Zustand (Toggle-Anzeige). §6b */
const val SOUND_ENABLED_TOPIC = "/hexapod/sound_enabled"

/** Node, auf dem der BOOL-Param [SOUND_ENABLE_PARAM] via `set_parameters` gesetzt wird. §4/§6b */
const val AUDIO_NODE = "/hexapod_audio"

/** BOOL-Param: Auto-Bewegungs-Sounds an/aus (Soundboard bleibt unberührt). §4/§6b */
const val SOUND_ENABLE_PARAM = "sound_enable"

/** Ein Soundboard-Button: [label] = Anzeige (rein app-seitig), [key] = Contract-Key fürs Publish. */
data class SoundButton(val label: String, val key: String)

/** Die drei Soundboard-Buttons (v1). Keys aus Contract §6b; Labels app-seitig gewählt. */
val SOUNDBOARD = listOf(
    SoundButton("Sound 1", "sound_01"),
    SoundButton("Sound 2", "sound_02"),
    SoundButton("Sound 3", "sound_03"),
)
