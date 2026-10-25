package app.trackone.palette

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * WCAG contrast and colour distance, matching the dataviz validator used in the 2026-09 palette
 * review: OKLab ΔE×100, with colour blindness simulated by Machado, Oliveira & Fernandes (2009)
 * at severity 1.0 in linear RGB.
 */
object ColorMath {

    fun contrast(a: String, b: String): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /** Distance for normal colour vision. */
    fun deltaE(a: String, b: String): Double = distance(oklab(linear(a)), oklab(linear(b)))

    /** The worse of protanopia and deuteranopia, the two common forms of colour blindness. */
    fun colourBlindDeltaE(a: String, b: String): Double = listOf(PROTAN, DEUTAN).minOf { m ->
        distance(oklab(simulate(linear(a), m)), oklab(simulate(linear(b), m)))
    }

    private fun linear(hex: String) = DoubleArray(3) { i ->
        val c = hex.substring(1 + 2 * i, 3 + 2 * i).toInt(16) / 255.0
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(hex: String) = linear(hex).let { 0.2126 * it[0] + 0.7152 * it[1] + 0.0722 * it[2] }

    private fun simulate(rgb: DoubleArray, m: Array<DoubleArray>) = DoubleArray(3) { row ->
        (m[row][0] * rgb[0] + m[row][1] * rgb[1] + m[row][2] * rgb[2]).coerceIn(0.0, 1.0)
    }

    private fun oklab(rgb: DoubleArray): DoubleArray {
        val l = Math.cbrt(0.4122214708 * rgb[0] + 0.5363325363 * rgb[1] + 0.0514459929 * rgb[2])
        val m = Math.cbrt(0.2119034982 * rgb[0] + 0.6806995451 * rgb[1] + 0.1073969566 * rgb[2])
        val s = Math.cbrt(0.0883024619 * rgb[0] + 0.2817188376 * rgb[1] + 0.6299787005 * rgb[2])
        return doubleArrayOf(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        )
    }

    private fun distance(p: DoubleArray, q: DoubleArray) =
        100 * sqrt((p[0] - q[0]).pow(2) + (p[1] - q[1]).pow(2) + (p[2] - q[2]).pow(2))

    private val PROTAN = arrayOf(
        doubleArrayOf(0.152286, 1.052583, -0.204868),
        doubleArrayOf(0.114503, 0.786281, 0.099216),
        doubleArrayOf(-0.003882, -0.048116, 1.051998)
    )
    private val DEUTAN = arrayOf(
        doubleArrayOf(0.367322, 0.860646, -0.227968),
        doubleArrayOf(0.280085, 0.672501, 0.047413),
        doubleArrayOf(-0.011820, 0.042940, 0.968881)
    )
}
