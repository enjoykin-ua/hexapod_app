package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests der reinen Video-/Nav-Logik ([VideoLogic]) gegen interface_contract.md v0.8 §5.
 * Kein Gerät/Netz. Bewusst NICHT hier: der OkHttp/Bitmap-Glue ([MjpegStream]) — Integration.
 */
class VideoLogicTest {

    // --- videoStreamUrl (Contract §0/§5: gleiche IP, Port 8080, type je Modus) ---

    @Test fun mjpeg_url_has_bandwidth_tuning() {
        // mjpeg: Server re-encodet → Downscale/Quality anhängen (~¾ der Pixel, q70).
        assertEquals(
            "http://192.168.1.5:8080/stream?topic=/camera/image_raw&type=mjpeg&quality=70&width=1120&height=630",
            videoStreamUrl("192.168.1.5", "mjpeg"),
        )
    }

    @Test fun ros_compressed_url_stays_plain_and_trims_host() {
        // ros_compressed: Quell-JPEGs durchgereicht (kein Pi-Decode) → KEIN Downscale-Param.
        assertEquals(
            "http://10.0.0.9:8080/stream?topic=/camera/image_raw&type=ros_compressed",
            videoStreamUrl("  10.0.0.9  ", "ros_compressed"),
        )
    }

    // --- streamType / wantCameraEnable (Phase 7B Variante A) ---

    @Test fun stream_type_per_mode() {
        assertEquals("mjpeg", streamType(ConnMode.SIM))
        assertEquals("ros_compressed", streamType(ConnMode.HW))
    }

    @Test fun camera_enable_only_on_hw_and_when_stream_wanted() {
        assertTrue(wantCameraEnable(ConnMode.HW, streamWanted = true))
        assertFalse(wantCameraEnable(ConnMode.HW, streamWanted = false))
        assertFalse(wantCameraEnable(ConnMode.SIM, streamWanted = true))
        assertFalse(wantCameraEnable(ConnMode.SIM, streamWanted = false))
    }

    // --- toggleCam (Plan-[ADR-6]: centerView = einzige Wahrheit) ---

    @Test fun cam_toggles_nothing_and_kamera() {
        assertEquals(CenterView.KAMERA, toggleCam(CenterView.NOTHING))
        assertEquals(CenterView.NOTHING, toggleCam(CenterView.KAMERA))
        assertEquals(CenterView.KAMERA, toggleCam(CenterView.ROBOT3D))
    }

    // --- shouldStream (Gate: nur verbunden + Stack läuft + Drive + Kamera + Vordergrund) ---

    @Test fun stream_only_when_all_conditions_hold() {
        assertTrue(
            shouldStream(Screen.DRIVE, CenterView.KAMERA, connected = true, stackRunning = true, resumed = true)
        )
    }

    @Test fun no_stream_if_stack_not_running() {
        assertFalse(
            shouldStream(Screen.DRIVE, CenterView.KAMERA, connected = true, stackRunning = false, resumed = true)
        )
    }

    @Test fun no_stream_if_disconnected_or_backgrounded_or_wrong_view_or_screen() {
        assertFalse(shouldStream(Screen.DRIVE, CenterView.KAMERA, connected = false, stackRunning = true, resumed = true))
        assertFalse(shouldStream(Screen.DRIVE, CenterView.KAMERA, connected = true, stackRunning = true, resumed = false))
        assertFalse(shouldStream(Screen.DRIVE, CenterView.NOTHING, connected = true, stackRunning = true, resumed = true))
        assertFalse(shouldStream(Screen.LIFECYCLE, CenterView.KAMERA, connected = true, stackRunning = true, resumed = true))
    }

    // --- extractBoundary ---

    @Test fun boundary_parsed_from_content_type() {
        assertEquals("boundarydonotcross", extractBoundary("multipart/x-mixed-replace;boundary=boundarydonotcross"))
    }

    @Test fun boundary_handles_spaces_quotes_and_trailing_params() {
        assertEquals("xyz", extractBoundary("multipart/x-mixed-replace; boundary=\"xyz\"; charset=utf-8"))
    }

    @Test fun boundary_null_when_absent() {
        assertNull(extractBoundary("text/plain"))
        assertNull(extractBoundary(null))
    }
}
