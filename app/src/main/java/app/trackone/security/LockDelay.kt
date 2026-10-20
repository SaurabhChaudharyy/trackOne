package app.trackone.security

/** The choices offered for how long the app may be left before it asks to be unlocked again. */
object LockDelay {

    class Option(val millis: Long, val label: String)

    /** Sorted by length, shortest first. */
    val options = listOf(
        Option(0L, "Immediately"),
        Option(60_000L, "After 1 minute"),
        Option(5 * 60_000L, "After 5 minutes"),
        Option(15 * 60_000L, "After 15 minutes"),
        Option(30 * 60_000L, "After 30 minutes")
    )

    /** What someone gets who never opens the setting: long enough to answer a message and come back. */
    const val DEFAULT_MS = 60_000L

    /** The option for [millis], or the default's if a stored value is no longer on offer. */
    fun optionFor(millis: Long): Option =
        options.firstOrNull { it.millis == millis } ?: options.first { it.millis == DEFAULT_MS }
}
