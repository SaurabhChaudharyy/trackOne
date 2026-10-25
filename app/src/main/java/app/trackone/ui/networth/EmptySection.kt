package app.trackone.ui.networth

import androidx.annotation.DrawableRes
import app.trackone.R
import app.trackone.data.database.AssetType

/** What an empty category says when the user opens it. */
internal data class EmptySectionCopy(val title: String, val body: String, val action: String)

/**
 * An empty category stays a compact row until it is opened; only then does it explain itself.
 * Collapsed, or holding something, it never shows.
 */
internal fun showEmptyCard(expanded: Boolean, holdings: Int): Boolean = expanded && holdings == 0

internal fun emptySectionCopy(type: AssetType): EmptySectionCopy = when (type) {
    AssetType.STOCK_IN -> EmptySectionCopy("No Indian stocks yet", "Add an NSE or BSE stock to count it in your net worth.", "Add stock")
    AssetType.STOCK_US -> EmptySectionCopy("No US stocks yet", "Add a NYSE or Nasdaq stock or ETF to count it in your net worth.", "Add stock")
    AssetType.MF       -> EmptySectionCopy("No mutual funds yet", "Add a fund to count it in your net worth.", "Add fund")
    AssetType.GOLD     -> EmptySectionCopy("No gold yet", "Add a coin, bar or Gold ETF to count it in your net worth.", "Add gold")
    AssetType.SILVER   -> EmptySectionCopy("No silver yet", "Add a coin, bar or Silver ETF to count it in your net worth.", "Add silver")
    AssetType.CRYPTO   -> EmptySectionCopy("No crypto yet", "Add a coin or token to count it in your net worth.", "Add crypto")
    AssetType.CASH     -> EmptySectionCopy("No cash yet", "Add the cash you keep on hand to count it in your net worth.", "Add cash")
    AssetType.BANK     -> EmptySectionCopy("No bank balance yet", "Add an account balance to count it in your net worth.", "Add balance")
}

/** Each category has its own picture: bars for gold, an "Ag" coin for silver, and so on. */
@DrawableRes
internal fun emptySectionArt(type: AssetType): Int = when (type) {
    AssetType.STOCK_IN -> R.drawable.ill_empty_stock_in
    AssetType.STOCK_US -> R.drawable.ill_empty_stock_us
    AssetType.MF       -> R.drawable.ill_empty_mf
    AssetType.GOLD     -> R.drawable.ill_empty_gold
    AssetType.SILVER   -> R.drawable.ill_empty_silver
    AssetType.CRYPTO   -> R.drawable.ill_empty_crypto
    AssetType.CASH     -> R.drawable.ill_empty_cash
    AssetType.BANK     -> R.drawable.ill_empty_bank
}
