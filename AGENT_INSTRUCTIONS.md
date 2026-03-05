# trackOne (Finance Widget) - Project Context & Agent Instructions

Welcome, future AI Agent! If you are working on this project, please read this document carefully to understand the context, architecture, design choices, and historical decisions of this Android application.

## Project Overview

*   **App Name**: trackOne (Internal/package name: `com.saurabh.financewidget`)
*   **Purpose**: A finance application and home screen Android widget to track assets, net worth, stocks, and crypto.
*   **Platform**: Android (Target SDK 34, Min SDK 26)
*   **Language**: Kotlin
*   **Architecture Pattern**: MVVM (Model-View-ViewModel)

## Tech Stack & Libraries

*   **UI Framework**: Android XML Views with Material 3 components and app bars. (Not Compose).
*   **Dependency Injection**: Dagger Hilt (uses KSP).
*   **Database**: Room Database (uses KSP).
*   **Networking**: Retrofit, OkHttp, Gson.
*   **Asynchrony**: Kotlin Coroutines.
*   **Background Tasks**: WorkManager (with Hilt integration).
*   **Image Loading**: Glide.
*   **Charts**: MPAndroidChart. *(Note: We previously tried integrating `Vico` for charts, but reverted it completely due to build/KSP complexities. Do **NOT** use Vico without explicit permission. Stick to MPAndroidChart.)*

## App Structure & Features

1.  **Watchlist (Markets Tab)**
    *   Main list of tracked stocks/assets.
    *   Detailed view for individual stocks (`StockDetailActivity`), showing candlestick/line charts.
2.  **Net Worth Tab**
    *   Tracks multiple asset categories with collapsible sections:
        *   Indian Stocks
        *   US Stocks
        *   Mutual Funds
        *   Gold
        *   Crypto
        *   Cash
        *   Bank Balance
    *   Includes an "Add Asset" dialog that dynamically prompts for a Symbol and Quantity to automatically fetch current values (for Stocks, Gold, Crypto), while other types only require absolute amounts.
3.  **App Widget**
    *   `StockWidgetProvider` utilizing `RemoteViews`.
    *   **Crucial Context**: The widget is sensitive to standard Android `RemoteViews` limitations. We had previous issues ("Can't load widget") caused by unsupported XML tags and attributes in the widget layout. Any necessary tinting *must* be handled either programmatically or via pre-colored drawables. Do not use complex modern tags inside widget XMLs.
4.  **Settings Tab**
    *   Preferences and configuration.

## Design System & Aesthetics

*   **Color Theme**: Dark mode focus. High contrast overall with specific pure blacks/whites in icons/splashes.
*   **Typography**: *Geist Mono* is the global primary font for everything (headings, body, numbers, monospaced tech feel).
*   **Iconography**:
    *   The app icon uses a **High-Contrast Neumorphism** style. It depicts a three-bar chart rising up, where the last bar resembles the number "1" (for trackOne).
    *   The current icon is implemented via distinct pixel-density `.png` files inside `mipmap-[density]` folders, with an adaptive background set to `#F1F1F1`.
*   **Splash Screen**:
    *   Android 12+ requires a mandatory splash screen on cold start.
    *   To keep launches clean, we render an "invisible" splash screen icon using a valid but transparent vector (`transparent_splash.xml`) alongside the true dark background color, creating a seamless opening rather than a jarring default logo popup.
*   **Splash Animation**:
    *   The app implements an custom splash screen animation when it opens, featuring the main app icon (the neumorphism one) expanding outwards to reveal the main UI.

## Important Historical Context (Do Not Break)

1.  **Vico Library**: As mentioned, Vico was removed. Do not suggest adding it back unless requested.
2.  **Widget XMLs**: Never add standard `ConstraintLayout` padding or unsupported tags/tints to widget `RemoteViews` layouts. Stick to `LinearLayout`/`RelativeLayout` where applicable, and test the widget actively to ensure it doesn't crash to "Can't load widget".
3.  **App Icon Assets**: We use precise PNGs inside `mipmap` folders for the high-contrast Neumorphism logo because standard XML vectors failed to reproduce the intricate shadow detail accurately. Do not overwrite these with flat XML SVG files unless specifically instructed. The background of adaptive icons is `#F1F1F1`.
4.  **KSP vs KAPT**: The project uses **KSP** exclusively for Room and Hilt code generation (as seen in `build.gradle.kts`). Do not revert to `kapt`, as it introduces compatibility issues.

## Data Storage & Persistence

The app uses a **single Room database** for all persistent storage. There is **no cloud sync, no SharedPreferences, and no external backup mechanism** currently in place.

### Database Details

| Property | Value |
|---|---|
| **Library** | Room (via KSP) |
| **Database class** | `FinanceDatabase` |
| **Database file name** | `finance_widget_db` |
| **Current schema version** | 2 |
| **Migration policy** | `fallbackToDestructiveMigration()` — schema changes wipe and recreate the DB |
| **Physical location on device** | `/data/data/com.saurabh.financewidget/databases/finance_widget_db` (internal storage, not accessible without root) |

### Tables

| Table | Entity Class | Primary Key | Purpose |
|---|---|---|---|
| `stocks` | `StockEntity` | `symbol` (String) | Cached stock data (price, change %, market cap, etc.) fetched from Yahoo Finance |
| `watchlist` | `WatchlistEntity` | `symbol` (String) | User's watchlist — symbols the user has added, with display order (`position`) |
| `price_history` | `PriceHistoryEntity` | `id` (auto-increment Long) | OHLCV candlestick / line chart data per symbol and resolution |
| `networth_assets` | `NetWorthAssetEntity` | `id` (auto-increment Long) | All Net Worth entries across all asset types (Indian Stocks, US Stocks, MF, Gold, Crypto, Cash, Bank) |

### What Lives Where

*   **Watchlist** — stored in the `watchlist` table. The `stocks` table caches the latest fetched price data for those symbols.
*   **Net Worth assets** — stored in `networth_assets`. Fields include `name`, `assetType` (enum), `quantity`, `buyPrice`, `currentValue`, `currency`, and timestamps.
*   **Price history** — stored in `price_history` for charting in `StockDetailActivity`. Old records are periodically purged via `deleteOldHistory(cutoffTime)`.
*   **Widget configuration** — the widget reads from the same `watchlist` / `stocks` tables. There are **no separate SharedPreferences** for the widget; its data source is purely the Room DB.

### Data on Uninstall

> **⚠️ All user data is permanently erased when the app is uninstalled.**

Because the Room database resides in the app's **internal private storage** (`/data/data/com.saurabh.financewidget/`), Android automatically deletes the entire directory when the app is uninstalled. This means:

*   The watchlist is gone.
*   All Net Worth assets and their values are gone.
*   All cached stock prices and chart history are gone.
*   **Recovery is only possible if the user previously exported a backup** via the Settings → Export Data feature (see section below).

Android Auto Backup (`android:allowBackup`) is currently **disabled** in the manifest. If it is enabled in the future, add explicit `android:backupRules` to exclude the Room database file (to avoid restoring a stale schema that triggers `fallbackToDestructiveMigration`).

---

## Backup & Restore Feature

Implemented in **Settings tab**. Uses Android's **Storage Access Framework (SAF)** — no storage permissions are required on API 26+.

### Relevant Files

| File | Role |
|---|---|
| `data/repository/BackupRepository.kt` | Core engine — serialises/deserialises Room ↔ JSON, all file I/O via SAF URIs |
| `ui/settings/SettingsViewModel.kt` | HiltViewModel — exposes `StateFlow<BackupUiState>` to the fragment |
| `ui/settings/SettingsFragment.kt` | Registers SAF `ActivityResultContract` launchers, observes state, shows dialogs |
| `res/layout/fragment_settings.xml` | Settings UI — Export card, Import card, About card, loading row |

### What gets exported

| Included | Excluded |
|---|---|
| Watchlist symbols + display order | `stocks` price cache (re-fetched live after import) |
| All Net Worth assets (all types) | `price_history` chart cache |
| Timestamps for sorting/auditing | — |

### Export File Format

Files are saved as **pretty-printed JSON** with the default filename `trackone_backup_YYYYMMDD_HHmmss.json`.

```json
{
  "schema_version": 1,
  "exported_at_ms": 1741181454000,
  "app_package": "com.saurabh.financewidget",
  "watchlist": [
    {
      "symbol": "RELIANCE.NS",
      "display_name": "Reliance Industries Limited",
      "position": 0,
      "added_at_ms": 1740000000000
    }
  ],
  "networth_assets": [
    {
      "name": "Bitcoin",
      "asset_type": "CRYPTO",
      "quantity": 0.05,
      "buy_price": 3500000.0,
      "current_value": 420000.0,
      "currency": "INR",
      "notes": "Hardware wallet",
      "added_at_ms": 1740050000000,
      "updated_at_ms": 1741181454000
    }
  ]
}
```

**Key points about the format:**
- `schema_version` — integer; currently `1`. Bumping this in future lets the app refuse to import incompatible backups gracefully.
- `app_package` — presence of `"com.saurabh.financewidget"` is validated on import to prevent importing a backup from a different app.
- All timestamps are **Unix epoch milliseconds**.
- `asset_type` is the **enum name string** — valid values: `STOCK_IN`, `STOCK_US`, `MF`, `GOLD`, `CRYPTO`, `CASH`, `BANK`.

### Import Behaviour

Import is a **full destructive replace**:
1. Show a confirmation dialog before touching anything.
2. Validate the JSON (parse, package check, schema version, enum names) — abort on any failure.
3. Clear `watchlist` table → insert restored symbols.
4. Clear `networth_assets` table → insert restored assets.
5. Auto-generated `id` fields are reset to `0` so Room assigns fresh IDs (avoids PK conflicts).
6. Stock prices are **not restored** — they will refresh automatically on next app foreground.

### Edge Cases Handled

| Scenario | Behaviour |
|---|---|
| Empty watchlist & net worth on export | Error dialog: "Nothing to export" |
| User cancels the SAF file picker | Silent no-op (no dialog) |
| File cannot be opened / written | Error dialog with reason |
| File is empty | Error dialog |
| JSON parse failure | Error dialog — prompts to check the file |
| `app_package` mismatch | Error dialog — wrong app's backup |
| `schema_version` > current (1) | Error dialog — tells user to update the app |
| Unknown `asset_type` enum value | Error dialog — caught before any data is deleted |
| Operation in progress | Both cards are disabled + faded + loading spinner shown |
| Fragment detached before dialog | `isAdded` guard prevents window leak |

### Upgrading the Schema (Future Agents)

If you add new fields to `NetWorthAssetEntity` or `WatchlistEntity` that must be preserved in backups:
1. Add the new field to `NetWorthAssetBackup` / `WatchlistBackup` with a sensible default.
2. Bump `BACKUP_SCHEMA_VERSION` in `BackupRepository.kt`.
3. Add a migration branch in `importFromUri()` that handles the older schema version gracefully instead of rejecting it.

---

*When starting a new task, always refer back to these guidelines to maintain the established project structure and visual identity.*
