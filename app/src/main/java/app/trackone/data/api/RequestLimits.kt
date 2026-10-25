package app.trackone.data.api

/** Limits on how hard the app leans on Yahoo, shared by every place that fetches in bulk. */
object RequestLimits {
    /**
     * The most quotes or history downloads in flight at once: holdings, watchlist and chart history all
     * use it, so a big portfolio never opens dozens of connections together (which gets rate-limited).
     */
    const val MAX_PARALLEL = 4
}
