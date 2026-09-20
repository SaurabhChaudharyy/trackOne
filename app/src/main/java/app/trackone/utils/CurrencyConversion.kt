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
     * sometimes reports USD-denominated quotes as "USX" (cents) rather than "USD" — same
     * regularMarketPrice field, but a hundredth the unit, so it needs converting to dollars
     * before the USD→INR rate is applied or it comes out 100x too high.
     */
    fun toInr(priceInNativeCurrency: Double, currency: String, usdInrRate: Double): Double =
        when (currency) {
            "USD" -> priceInNativeCurrency * usdInrRate
            "USX" -> (priceInNativeCurrency / 100.0) * usdInrRate
            else -> priceInNativeCurrency
        }

    /**
     * A quote as this app holds it: INR per unit, where the unit is a gram for gold/silver
     * ([isMetal]) and a share/coin for everything else. The one place that rule lives — the live
     * quote and the history chart both go through it, so they can't disagree on units.
     */
    fun perUnitInr(price: Double, currency: String, usdInrRate: Double, isMetal: Boolean): Double {
        val inr = toInr(price, currency, usdInrRate)
        return if (isMetal) troyOunceToGramPrice(inr) else inr
    }

    /** Gold/silver are quoted per troy ounce; net-worth tracks them per gram. */
    fun troyOunceToGramPrice(pricePerTroyOunceInr: Double): Double =
        pricePerTroyOunceInr / GRAMS_PER_TROY_OUNCE
}
