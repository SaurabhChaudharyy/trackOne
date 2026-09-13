package app.trackone.utils

/**
 * Pulled out of NetWorthRepository so this arithmetic is testable without mocking Retrofit/
 * Yahoo Finance responses — it's pure math over already-fetched numbers.
 */
object CurrencyConversion {

    /** Grams per troy ounce — gold/silver quotes come back priced per troy ounce. */
    private const val GRAMS_PER_TROY_OUNCE = 31.1035

    /**
     * Converts a price quoted in [currency] into INR using [usdInrRate]. Yahoo Finance
     * sometimes reports USD-denominated quotes as "USX" (cents) rather than "USD" — both are
     * treated as USD here since [priceInNativeCurrency] is already the same regularMarketPrice
     * field NetWorthRepository reads regardless of which of the two the API returned.
     */
    fun toInr(priceInNativeCurrency: Double, currency: String, usdInrRate: Double): Double =
        if (currency == "USD" || currency == "USX") {
            priceInNativeCurrency * usdInrRate
        } else {
            priceInNativeCurrency
        }

    /** Gold/silver are quoted per troy ounce; net-worth tracks them per gram. */
    fun troyOunceToGramPrice(pricePerTroyOunceInr: Double): Double =
        pricePerTroyOunceInr / GRAMS_PER_TROY_OUNCE
}
