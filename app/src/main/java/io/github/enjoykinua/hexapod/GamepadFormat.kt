package io.github.enjoykinua.hexapod

/**
 * Reine (Framework-freie) Hilfsfunktionen fuer die Kishi-Gamepad-Anzeige.
 * Bewusst ohne Android-Abhaengigkeit gehalten -> per JUnit ohne Geraet testbar.
 */

/** Erwartete USB-IDs des Razer Kishi V2 (aus Stufe A / CLAUDE.md). */
const val KISHI_VENDOR_ID = 0x1532
const val KISHI_PRODUCT_ID = 0x071b

/** Formatiert eine USB Vendor-/Product-ID als 4-stelligen Hex-String, z. B. 0x1532. */
fun hex4(id: Int): String = "0x%04x".format(id)

/**
 * Bildet [value] im Bereich [min]..[max] auf 0f..1f ab (geklemmt).
 * Liefert 0f, wenn der Bereich leer/ungueltig ist (max <= min).
 * Dient nur der Balken-Darstellung; der Rohwert wird separat angezeigt.
 */
fun normalize(value: Float, min: Float, max: Float): Float {
    if (max <= min) return 0f
    return ((value - min) / (max - min)).coerceIn(0f, 1f)
}

/** True, wenn Vendor/Product exakt dem erwarteten Kishi V2 entsprechen. */
fun matchesKishi(vendorId: Int, productId: Int): Boolean =
    vendorId == KISHI_VENDOR_ID && productId == KISHI_PRODUCT_ID
