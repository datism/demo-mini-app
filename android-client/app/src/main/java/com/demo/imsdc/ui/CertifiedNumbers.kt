package com.demo.imsdc.ui

/**
 * Numbers the carrier vouches for (brand name and icon shown with a "verified" badge on the call screen).
 * The network rings these a few seconds before answering (go-mf config.HotlineRingDelay), so the screen is seen.
 */
object CertifiedNumbers {
    data class Certified(val name: String, val icon: String)

    private val entries = mapOf(
        "1900" to Certified("SkyLotus Air", "✈️")
    )

    fun of(number: String): Certified? = entries[Phonebook.normalize(number)]
}
