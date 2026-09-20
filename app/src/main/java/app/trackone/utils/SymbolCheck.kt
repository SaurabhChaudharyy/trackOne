package app.trackone.utils

/**
 * Tells "this symbol does not exist" apart from "the check could not be made". The edit dialog blocks
 * a typed symbol only for the first — an offline phone or a rate-limited API must never stop someone
 * saving a perfectly valid holding.
 */
object SymbolCheck {
    fun isNotFound(errorMessage: String): Boolean =
        errorMessage.startsWith("HTTP 404") ||
            errorMessage.startsWith("No data for") ||
            errorMessage.startsWith("Invalid price")
}
