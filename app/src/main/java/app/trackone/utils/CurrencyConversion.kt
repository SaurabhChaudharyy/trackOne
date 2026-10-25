package app.trackone.utils

/**
 * Pulled out of NetWorthRepository so this arithmetic is testable without mocking Retrofit/
 * Yahoo Finance responses — it's pure math over already-fetched numbers.
 */
object CurrencyConversion {

    /** Grams per troy ounce — gold/silver quotes come back priced per troy ounce. */
    private const val GRAMS_PER_TROY_OUNCE = 31.1035

    /**
     * Yahoo quotes some markets in a hundredth of the currency: US cents (USX), London pence
     * (GBp, also written GBX), South African cents (ZAc), Israeli agorot (ILA). Same price field,
     * a hundredth the unit, so it must become the major unit before any rate is applied or it
     * comes out 100x too high. Case matters: "GBP" is pounds, "GBp" is pence.
     */
    private val MINOR_UNITS = mapOf("USX" to "USD", "GBp" to "GBP", "GBX" to "GBP", "ZAc" to "ZAR", "ILA" to "ILS")

    /** The currency whose →INR rate converts a quote in [currency] (pence → GBP, cents → USD). */
    fun majorCurrency(currency: String): String = MINOR_UNITS[currency] ?: currency.uppercase()

    /** True when [currency] is a minor unit (pence, cents): a quote in it is 100x its major value. */
    fun isMinorUnit(currency: String): Boolean = currency in MINOR_UNITS

    /**
     * Converts a price quoted in [currency] into INR. [rateToInr] is the rate of
     * [majorCurrency] (USD→INR for a USX quote, GBP→INR for a GBp one); INR ignores it.
     */
    fun toInr(priceInNativeCurrency: Double, currency: String, rateToInr: Double): Double {
        val major = majorCurrency(currency)
        val inMajorUnit = if (isMinorUnit(currency)) priceInNativeCurrency / 100.0 else priceInNativeCurrency
        return if (major == "INR") inMajorUnit else inMajorUnit * rateToInr
    }

    /**
     * A quote as this app holds it: INR per unit, where the unit is a gram for gold/silver
     * ([isMetal]) and a share/coin for everything else. The one place that rule lives — the live
     * quote and the history chart both go through it, so they can't disagree on units.
     */
    fun perUnitInr(price: Double, currency: String, rateToInr: Double, isMetal: Boolean): Double {
        val inr = toInr(price, currency, rateToInr)
        return if (isMetal) troyOunceToGramPrice(inr) else inr
    }

    /** Gold/silver are quoted per troy ounce; net-worth tracks them per gram. */
    fun troyOunceToGramPrice(pricePerTroyOunceInr: Double): Double =
        pricePerTroyOunceInr / GRAMS_PER_TROY_OUNCE
}
