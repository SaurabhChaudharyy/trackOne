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

0.  **Home (Welcome Screen Tab)**
    *   First tab; shown on cold launch.
    *   Content: time-based greeting, date (uppercase), live clock (updates every minute), market Open/Closed status chips for NSE and NYSE, 4 market index cards (NIFTY 50 `^NSEI`, SENSEX `^BSESN`, S&P 500 `^GSPC`, NASDAQ `^IXIC`), and Top Mover (largest absolute % change in the watchlist).
    *   Fragment: `HomeFragment` · ViewModel: `HomeViewModel` · Layout: `fragment_home.xml`.
    *   Market status chips are clickable → opens `dialog_market_hours.xml` (same dialog previously used in WatchlistFragment).

1.  **Watchlist Tab**
    *   Nav label: **"Watchlist"** (previously called "Markets" — renamed in this session).
    *   Main list of tracked stocks/assets.
    *   Detailed view for individual stocks (`StockDetailActivity`), showing candlestick/line charts.
    *   The market Open/Closed status bar that previously lived at the bottom of this screen has been **removed** — it now lives exclusively on the Home tab.
2.  **Net Worth Tab**
    *   Tracks multiple asset categories with collapsible sections:
        *   Indian Stocks
        *   US Stocks
        *   Mutual Funds
        *   Gold
        *   Silver
        *   Crypto
        *   Cash
        *   Bank Balance
    *   Includes an "Add Asset" dialog that dynamically prompts for a Symbol and Quantity to automatically fetch current values (for Stocks, Gold, Silver, Crypto), while other types only require absolute amounts.

    ### Add Asset Dialog — Behaviour by Type

    | Asset Type | Symbol field | Price source | Unit label | Name stored |
    |---|---|---|---|---|
    | `STOCK_IN` | Shown — autocomplete with `.NS` suffix list; bare tickers (e.g. `TRENT`) are auto-normalised to `TRENT.NS` before the API call | Yahoo Finance ticker (e.g. `TRENT.NS`) | `/share` | Typed symbol (uppercased) |
    | `STOCK_US` | Shown — autocomplete with US tickers | Yahoo Finance ticker (e.g. `AAPL`) | `/share` | Typed symbol (uppercased) |
    | `GOLD` | **Hidden** — always uses `GC=F` (Gold Futures) | Auto-fetched on dialog open | `/gram` | Always `"GOLD"` |
    | `SILVER` | **Hidden** — always uses `SI=F` (Silver Futures) | Auto-fetched on dialog open | `/gram` | Always `"SILVER"` |
    | `CRYPTO` | Shown — user types pair e.g. `BTC-INR`; fetch triggers on focus-loss OR Enter key | Yahoo Finance ticker as typed | `/coin` | Typed symbol (uppercased) |
    | `MF`, `CASH`, `BANK` | Hidden — manual value entry only | n/a | n/a | User-entered label |

    ### Yahoo Finance Ticker Mapping (Precious Metals)
    *   **Gold**: `GC=F` — Gold Futures, priced in USD per troy ounce. Converted: USD → INR via `USDINR=X`, then ÷ 31.1035 = **INR per gram**.
    *   **Silver**: `SI=F` — Silver Futures, priced in USD per troy ounce. Same conversion pipeline.

    ### NSE Symbol Normalisation
    Indian stocks require the `.NS` suffix on Yahoo Finance (e.g. `TRENT.NS`). The `normaliseSymbol()` helper in `NetWorthFragment` automatically appends `.NS` to any bare ticker (no `.` in the string) for `STOCK_IN` type before making the API call.

3.  **App Widget**
    *   `StockWidgetProvider` utilizing `RemoteViews`.
    *   **Crucial Context**: The widget is sensitive to standard Android `RemoteViews` limitations. We had previous issues ("Can't load widget") caused by unsupported XML tags and attributes in the widget layout. Any necessary tinting *must* be handled either programmatically or via pre-colored drawables. Do not use complex modern tags inside widget XMLs.
4.  **Settings Tab**
    *   Preferences and configuration.

## Design System & Aesthetics

*   **Color Theme**: Light theme focus (inspired by Minna Bank). Monochrome black & white core, utilizing pure white `#FFFFFF` backgrounds and black `#09090B` text.
*   **Accent Color**: Vibrant neon yellow (`#D4E510`) is exclusively used as an accent for active states, positive changes, and count badges.
*   **Typography**: 
    *   **Inter**: Global primary font for all UI text elements (headers, labels, body text, buttons). Gives a clean, modern aesthetic.
    *   **Geist Mono**: Reserved *strictly* for numeric data display (prices, percentages, amounts, dates) to retain a precise, financial tech feel.
*   **Iconography**:
    *   The app icon uses a **High-Contrast Neumorphism** style. It depicts a three-bar chart rising up, where the last bar resembles the number "1" (for trackOne).
    *   The current icon is implemented via distinct pixel-density `.png` files inside `mipmap-[density]` folders, with an adaptive background set to `#F1F1F1`.
*   **Splash Screen (OS layer)**:
    *   Android 12+ mandates a system splash screen on cold start.
    *   We suppress the OS icon with `transparent_splash.xml` (1 dp fully transparent vector) so only our custom `SplashActivity` is visible.
*   **Custom Flash Screen (`SplashActivity`)**:
    *   The real launcher entry point is `SplashActivity` (see § Session Log 2026-03-19). `MainActivity` is **not** the launcher — do not restore its `<intent-filter>` unless explicitly asked.
    *   Design: **purely typographic** — app name (Inter SemiBold, 36sp) + tagline (Geist Mono, 13sp) centred on white. No icon is shown on the splash. The neon accent bar (`#D4E510`, 3dp × 40dp) fades in near the bottom.
    *   Animation sequence: brand group scales from 78 % → 100 % with `OvershootInterpolator(1.2f)` (480 ms), accent bar fades in (320 ms), loading label fades in (280 ms).
    *   Data pre-fetch: `SplashViewModel` concurrently fetches all 4 indexes (`^NSEI`, `^BSESN`, `^GSPC`, `^IXIC`) and calls `refreshWatchlistStocks()` so `HomeFragment` renders instantly with no spinner.
    *   Timing: minimum 1 400 ms display time; navigates only after **both** the minimum time has elapsed AND `SplashViewModel.isReady` emits `true`.
    *   Exit: 220 ms alpha fade-out → `MainActivity` with `overridePendingTransition(0, 0)` (no OS slide).

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
| `networth_assets` | `NetWorthAssetEntity` | `id` (auto-increment Long) | All Net Worth entries across all asset types (Indian Stocks, US Stocks, MF, Gold, Silver, Crypto, Cash, Bank) |

### What Lives Where

*   **Watchlist** — stored in the `watchlist` table. The `stocks` table caches the latest fetched price data for those symbols.
*   **Net Worth assets** — stored in `networth_assets`. Fields include `name`, `assetType` (enum), `quantity`, `buyPrice`, `currentValue`, `currency`, and timestamps.
        *   `assetType` valid values: `STOCK_IN`, `STOCK_US`, `MF`, `GOLD`, `SILVER`, `CRYPTO`, `CASH`, `BANK`.
        *   Precious metals (`GOLD`, `SILVER`) store quantity in **grams** and always use a fixed Yahoo Finance futures ticker — no user-entered symbol.
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
- `asset_type` is the **enum name string** — valid values: `STOCK_IN`, `STOCK_US`, `MF`, `GOLD`, `SILVER`, `CRYPTO`, `CASH`, `BANK`.

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

### Adding a New Asset Type (Future Agents)

When adding a new `AssetType` enum value (e.g. a new asset class), you must touch **all** of the following — missing any one will cause a compile error or silent data gap:

| File | What to add |
|---|---|
| `NetWorthEntities.kt` | Add the enum value to `AssetType` |
| `fragment_networth.xml` | Add header `LinearLayout`, `RecyclerView`, divider, and `+` `ImageButton` with unique IDs |
| `NetWorthFragment.kt` — `sectionExpanded` | Add `AssetType.NEW_TYPE to true` |
| `NetWorthFragment.kt` — `isFetchable` | Add to the set **only** if price can be auto-fetched from Yahoo Finance |
| `NetWorthFragment.kt` — `setupRecyclerViews()` | Add `AssetType.NEW_TYPE to binding.rvNewType` |
| `NetWorthFragment.kt` — `setupHeaders()` | Add `wireHeader(binding.headerNewType, binding.rvNewType, AssetType.NEW_TYPE)` |
| `NetWorthFragment.kt` — `setupAddButtons()` | Add `binding.btnAddNewType.setOnClickListener { showAddDialog(...) }` |
| `NetWorthFragment.kt` — `observeViewModel()` | Add `binding.tvTotalNewType.text = ...` |
| `NetWorthFragment.kt` — `showAddDialog()` | Add `AssetType.NEW_TYPE -> { ... }` case in the fetchable `when` block |
| `NetWorthFragment.kt` — `showAddDialog()` name `when` | Add `type == AssetType.NEW_TYPE -> "FIXED_NAME"` if name is fixed (like Gold/Silver) |
| `NetWorthFragment.kt` — blank-name guard | Add `&& type != AssetType.NEW_TYPE` if name is auto-assigned |
| `NetWorthFragment.kt` — `triggerFetch()` `unitLabel` | Add the correct unit string (`/gram`, `/coin`, `/share`, etc.) |
| `NetWorthFragment.kt` — `rvForType()` | Add `AssetType.NEW_TYPE -> binding.rvNewType` (**exhaustive `when` — compile error if missed**) |
| `NetWorthRepository.kt` — `fetchLivePrice()` | Add the Yahoo Finance ticker mapping in the `fetchSymbol when` block |
| `NetWorthRepository.kt` — `refreshNetWorthAssets()` | Add `AssetType.NEW_TYPE` to `fetchableTypes` set |
| `AGENT_INSTRUCTIONS.md` | Update asset categories list, table, and `asset_type` valid values |

---

## Portfolio P&L Tracking — Roadmap

Groww/Zerodha-style gain/loss tracking. `buyPrice: Double` already exists on `NetWorthAssetEntity` (default `0.0`).

### ✅ Phase 1 — Implemented (Basic P&L per holding)

| What | Where |
|---|---|
| **Buy price input** in the Add dialog | `dialog_add_asset.xml` — `til_buy_price` / `et_buy_price`, visible after price fetch |
| **Unit hint adapts** per type | `NetWorthFragment.triggerFetch()` — sets `₹/share`, `₹/gram`, `₹/coin` |
| **P&L row in item** | `item_networth_asset.xml` — `tv_asset_pl` (left, coloured ▲/▼) + `tv_asset_invested` (right, tertiary) |
| **Gain/loss computation** | `NetWorthAssetAdapter.bind()` — `gain = currentValue - (buyPrice * qty)`, colour `gain_green` / `loss_red` |
| **Stored on save** | `NetWorthFragment.showAddDialog()` — reads `etBuyPrice`, passes to `NetWorthAssetEntity.buyPrice` |

**Note:** No DB migration needed — `buyPrice` column was already in the schema.

---

### 🔲 Phase 2 — Planned (Portfolio Dashboard, ~1 day)

> **Do NOT implement without user approval.**

1. **Section-level P&L totals** — Add invested vs current labels next to each section header total in `fragment_networth.xml`. Requires a new `LiveData<Map<AssetType, Pair<Double, Double>>>` (invested, current) in `NetWorthViewModel` backed by a new DAO query that sums `buyPrice * quantity` and `currentValue` per type.

2. **Total Net Worth P&L card** — Redesign the top-level header into a two-row card:
   - Row 1: Current Net Worth (existing `tv_total_networth`)
   - Row 2: `▲ ₹X (+Y%)` vs invested total, coloured gain_green / loss_red.

3. **Edit asset dialog** — Tapping an asset row currently only offers delete. Add a long-press or pencil icon to open a pre-filled version of `showAddDialog` with the asset's current values, calling `viewModel.updateAsset(entity)` (already exists in `NetWorthDao`).

**Files to touch for Phase 2:**
- `fragment_networth.xml` — section header P&L labels + top card redesign
- `NetWorthViewModel.kt` — new `assetPlSummary: LiveData<Map<...>>`
- `NetWorthDao.kt` — `SELECT assetType, SUM(buy_price * quantity), SUM(current_value) GROUP BY assetType`
- `NetWorthFragment.kt` — observe new LiveData, wire edit click

---

### 🔲 Phase 3 — Planned (Advanced, ~2–3 days)

> **Do NOT implement without user approval.**

1. **Multiple lots / average cost basis** — Each purchase of the same stock creates a new row currently. To auto-average:
   - On add: if an asset with the same `name` + `assetType` exists, compute weighted average buy price `(existingQty * existingBuyPrice + newQty * newBuyPrice) / totalQty` and update rather than insert.
   - Or: store each lot separately and aggregate in the ViewModel. Preserves history but requires more complex queries.

2. **XIRR / CAGR return** — Needs `addedAt` timestamp per lot (already stored) and a numeric iterative XIRR solver. No Android/Kotlin library for this — write a custom Newton-Raphson implementation or port one from JVM finance libraries.

3. **Watchlist personal return column** — The Markets tab shows market-level 1d% change. To also show personal return ("your return since buy"):
   - New DAO query: `JOIN watchlist ON watchlist.symbol = networth_assets.name` to get `buyPrice` for each watched symbol.
   - New column in `WatchlistAdapter` item layout.
   - New `combine` of `watchlist` + `networth_assets` LiveData in `MainViewModel`.

---

*When starting a new task, always refer back to these guidelines to maintain the established project structure and visual identity.*

---

## Session Changes Log — 2026-03-19 (Flash Screen & Code Quality)

### 1. Custom Flash / Splash Screen

**What was added:** A fully animated `SplashActivity` is now the app's true launcher entry point, replacing the direct cold-start into `MainActivity`.

#### New files

| File | Role |
|---|---|
| `ui/splash/SplashActivity.kt` | Launcher `Activity` — plays entrance animations, collects `isReady` from ViewModel, navigates to `MainActivity` |
| `ui/splash/SplashViewModel.kt` | `@HiltViewModel` — concurrent pre-fetch of 4 indexes + watchlist; exposes `StateFlow<Boolean> isReady` |
| `res/layout/activity_splash.xml` | Splash UI — wordmark + tagline centred, neon accent bar at bottom, loading label |

#### Modified files

| File | Change |
|---|---|
| `res/values/themes.xml` | Added `Theme.FinanceWidget.Splash` — white bg, invisible OS splash icon, no action bar |
| `AndroidManifest.xml` | `SplashActivity` is now `exported=true` with `LAUNCHER` intent-filter; `MainActivity` changed to `exported=false` with no intent-filter |

#### Key design decisions

- **No icon on the splash** — after iteration the icon was removed; only the wordmark (`trackOne`, Inter SemiBold 36sp) and tagline (Geist Mono 13sp) are shown. This is intentional and must not be reverted.
- **Data pre-load**: `SplashViewModel.startPrefetch()` fetches indexes + watchlist concurrently via `StockRepository`. By the time `MainActivity` opens, Room is already populated → `HomeFragment` shows live data immediately with no loading spinners.
- **Minimum display time**: 1 400 ms (editable constant in `SplashActivity`). Change only if explicitly requested.
- **Exit transition**: 220 ms alpha fade + `overridePendingTransition(0, 0)` to suppress the OS slide. The `@Suppress("DEPRECATION")` annotation is intentional — the new API requires API 34 which is above our min SDK.

---

### 2. Code Quality — HomeFragment & HomeViewModel Warning Fixes

**Problem:** Two Kotlin compiler warnings existed in the Home screen code.

| File | Line | Warning | Fix |
|---|---|---|---|
| `HomeFragment.kt` | 151 | `Elvis operator (?:) always returns left operand` — `res.data ?: run { return }` is dead code because `Resource.Success.data` is non-nullable (`T`, not `T?`) | Removed the elvis fall-through; now simply `val data = res.data` |
| `HomeViewModel.kt` | 72 | `Condition 'stock != null' is always 'true'` — same root cause | Removed the `if (stock != null)` guard block, inlined the `liveData.postValue(...)` call directly |

**Root cause:** `Resource.Success<T>(val data: T)` — `data` is `T`, not `T?`. Any null-safety guards inside an `is Resource.Success` branch are therefore always redundant.

**Files changed:** `HomeFragment.kt`, `HomeViewModel.kt`

---

## Session Changes Log — 2026-03-18 (Home Screen & Navigation Overhaul)

### 1. Index Price Formatting (No Currency Symbol)

**Problem:** Stock indices like `^NSEI` (NIFTY 50) displayed with a `₹` symbol (e.g. `₹23,408.80`) — indices should show plain numbers.

**Solution:**
- Added `FormatUtils.formatIndexPrice(price, currency)` — uses `NumberFormat.getNumberInstance()` (no currency symbol) with Indian locale grouping for INR, US locale for others.
- Detection: any symbol starting with `^` is treated as an index.
- Applied in **`WatchlistAdapter.bind()`** (Markets tab list row) and **`StockDetailActivity.displayStockData()`** (all stat fields: price, open, high, low, prev close).

**Files changed:** `FormatUtils.kt`, `WatchlistAdapter.kt`, `StockDetailActivity.kt`

---

### 2. Item Count in Section Headers

**Problem:** No visual indication of how many assets are in a section (e.g. "Indian Stocks" with 3 holdings showed no count).

**Solution:**
- Each section header's category name `TextView` now has a unique ID: `tv_label_stock_in`, `tv_label_stock_us`, `tv_label_mf`, `tv_label_gold`, `tv_label_silver`, `tv_label_crypto`, `tv_label_cash`, `tv_label_bank`.
- In `NetWorthFragment.observeViewModel()`, whenever `allAssets` emits, the count is appended directly to the label text using an en-space separator:
  - `"Indian Stocks"` → `"Indian Stocks  3"` (when 3 items exist)
  - Resets to `"Indian Stocks"` when count is 0 (empty section).
- **No separate badge `TextView`** — the count is embedded in the label itself for clean visual placement.

**Files changed:** `fragment_networth.xml` (IDs on 8 label TextViews), `NetWorthFragment.kt`

---

### 3. Edit Asset Action

**Problem:** Asset rows in the Net Worth tab only had a delete (`×`) button. No way to modify an existing asset.

**Solution:**
- Created `ic_edit.xml` — pencil vector drawable using `#B3B3B3` fill (matches `ic_close` style), 24dp, same alpha (`0.35`).
- Added `btn_edit_asset` ImageButton to `item_networth_asset.xml`, placed immediately before `btn_delete_asset`, with `layout_marginEnd="2dp"`.
- `NetWorthAssetAdapter` now takes an `onEditClick: (NetWorthAssetEntity) -> Unit` callback alongside the existing `onDeleteClick`. The edit button triggers `onEditClick(asset)` with haptic feedback.
- Added `showEditDialog(asset: NetWorthAssetEntity)` in `NetWorthFragment`:
  - Reuses `dialog_add_asset.xml` layout (same as the add dialog).
  - Pre-fills all fields: name/symbol, quantity, buy price, current value, notes.
  - Title shows `"Edit <name>"`.
  - Symbol field is correctly **hidden** for `GOLD` and `SILVER` (fixed ticker — no user input needed).
  - On Save: calls `viewModel.updateAsset(asset.copy(...))` — preserves the original `id` and `addedAt` timestamp.

**Files changed:** `ic_edit.xml` (new), `item_networth_asset.xml`, `NetWorthAssetAdapter.kt`, `NetWorthFragment.kt`

> **Note:** This completes the "Edit asset dialog" item from Phase 2 roadmap (previously item 3 in Phase 2).

---

### 4. Gold & Silver Notes / Label Field

**Problem:** Gold and Silver entries all stored the fixed name `"GOLD"` / `"SILVER"`. If a user added the same metal twice (e.g. Physical gold vs Digital gold), the two rows were indistinguishable.

**Solution:**
- In `dialog_add_asset.xml`: the existing `til_notes` / `et_notes` fields now have a meaningful hint: *"Label / Note (e.g. Physical, Digital, Jewellery)"* and `layout_marginTop="12dp"`.
- In `showAddDialog()`: for `GOLD` and `SILVER` types, `d.tilNotes.isVisible = true` is set immediately after the symbol field is hidden. The typed note is saved to `NetWorthAssetEntity.notes`.
- In `showEditDialog()`: for metal types, `d.tilNotes.isVisible = isMetalType` and the existing note is pre-filled via `d.etNotes.setText(asset.notes)`. Notes are saved back on update.
- The edit dialog for Gold/Silver correctly hides the "Search symbol" field (`til_symbol`) — guarded by `val isMetalType = type == AssetType.GOLD || type == AssetType.SILVER`.

**Files changed:** `dialog_add_asset.xml`, `NetWorthFragment.kt`

---

### 5. Asset Label Display in Item Rows

**Problem:** The notes label for Gold/Silver was initially appended inline to the subtitle (`"15g · ₹14,831/unit · Physical"`) causing truncation when both the quantity/price string and label text were long.

**Solution:**
- Added a dedicated `tv_asset_label` `TextView` to `item_networth_asset.xml`, placed in the left column **between** `tv_asset_notes` (subtitle) and `tv_asset_pl` (P&L):
  - `textColor="@color/text_secondary"`, `textSize="11sp"`, `fontFamily="@font/geist_mono"`, `visibility="gone"` by default.
- In `NetWorthAssetAdapter.bind()`: `tvAssetLabel` is shown whenever `asset.notes.isNotBlank()`, hidden otherwise. Works for all asset types.
- `buildSubtitle()` reverted to show **only** the quantity/price info (no inline note append).

**Result — GOLD row display order:**
```
GOLD                        ₹2,22,475.28
15g · ₹14,831.69/unit       inv ₹2,10,000.00
Physical                    ← dedicated label row, never truncated
▲ ₹12,475.28 (+5.94%)
```

**Files changed:** `item_networth_asset.xml`, `NetWorthAssetAdapter.kt`

---

### Layout Structure Reference — `item_networth_asset.xml`

Current left-column order (all inside the `weight=1` vertical `LinearLayout`):

| View ID | Purpose | Visible when |
|---|---|---|
| `tv_asset_name` | Asset name (e.g. `HCLTECH.NS`, `GOLD`) | Always |
| `tv_asset_notes` | Subtitle — quantity/price (e.g. `10 units · ₹1,328.60/unit`) or manual notes for non-fetchable types | Has content |
| `tv_asset_label` | User label / note for Gold, Silver, etc. (e.g. `Physical`, `SGB`) | `notes.isNotBlank()` |
| `tv_asset_pl` | P&L line (e.g. `▲ ₹1,286 (+10.72%)`) | `buyPrice > 0 && quantity > 0` |

Right column: `tv_asset_value` (current value) + `tv_asset_invested` (invested amount, when `buyPrice` is set).

---

### Updated Phase 2 Status

| Item | Status |
|---|---|
| Section-level P&L totals | 🔲 Planned |
| Total Net Worth P&L card | 🔲 Planned |
| ~~Edit asset dialog~~ | ✅ **Implemented** (see Section 3 above) |

---

## Session Changes Log — UI Redesign (Light Theme Migration)

The app transitioned from a dark theme to a modern, Minna Bank-inspired light theme. Future development must strictly adhere to these aesthetic guidelines:

### 1. Color Palette & Theme
- The overall theme is `Theme.Material3.Light.NoActionBar`.
- Backgrounds are white (`#FFFFFF`), cards/surfaces are `surface_variant` (`#F4F4F5`), and strokes/borders are `border_color` (`#E4E4E7`).
- Primary text is almost black (`#09090B`), secondary text is dark gray (`#52525B`), tertiary text is light gray (`#A1A1AA`).
- Active UI indicators and positive financial changes utilize the signature neon yellow accent (`#D4E510`). Negative changes remain red (`#EF4444`).

### 2. Typography Dual-Font System
- All non-numeric UI copy (Symbols, Names, Navigation, Settings labels) uses the **Inter** font family.
- All numbers and financial metrics (Prices, P&L, Quantity, Chart labels) continue to use **Geist Mono**.

### 3. Widget Aesthetics
- The Widget follows the light theme: a white semi-transparent background with a subtle gray border.
- Headers and text use Inter, matching the main app.
- Loading shimmers use `shimmer_bg` (light gray `#E4E4E7`).

### 4. Detail Graph Page
- Redesigned for a cleaner "Coinbase" style minimal aesthetic.
- The chart uses a thin, pure black line with no gradient fill underneath.
- Selected timeframe options and highlight crosshairs appear in neon yellow.
- Positive price changes have an explicit `↗` arrow and are tinted neon yellow. Negative changes have `↘` and are tinted red. High/Low statistics are accompanied by corresponding neon/red accent color bars.

---

## Session Changes Log — Recent Updates

### 1. Net Worth Breakdown Visualization
- A new segmented bar module (`ll_breakdown_container`) displays the precise categorical breakdown of Net Worth (Indian Stocks vs Mutual Funds vs Crypto, etc.).
- Dynamically rendered in `NetWorthFragment.kt` using predefined category colors (`cat_stock_in`, `cat_crypto`, etc.).
- The component is fully hidden if the user has no configured assets.

### 2. Modern Market Hours Dialog
- Replaced the textual `AlertDialog` builder string with a fully custom, sleek XML layout (`dialog_market_hours.xml`).
- Features a neon-tinted "OPEN" / gray "CLOSED" pill depending on the underlying status.
- Separates Regular Session and Extended Hours logically using strict typography alignments.

### 3. Widget PendingIntent Navigation Fix
- `StockDetailActivity` now overrides `onNewIntent(intent: Intent)` to correctly accept trailing clicks from the home screen widget.
- This ensures that fast-switching between multiple widget stock items explicitly reloads the target viewmodel data instead of falling back to Android's recent task caching.

### 4. Index Display Formatting
- Market Indices that start with `^` (like `^NSEI`, `^NDXT`) have the `^` character stripped out purely at the view-binding layer (`.removePrefix("^")`).
- This applies to `WatchlistAdapter`, `SearchResultAdapter`, `StockDetailActivity`, `StockWidgetService`, and `NetWorthAssetAdapter`.

### 5. Marquee Text Animations
- Overly large texts (specifically `tv_company_name` and `tv_symbol` inside `item_stock.xml`) now use Android's marquee auto-scroll feature.
- `android:ellipsize="marquee"`, `android:singleLine="true"`, and `android:scrollHorizontally="true"` added.
- Adapters forcefully apply `isSelected = true` globally on bind to trigger the marquee loops continuously.

---

## Session Changes Log — 2026-03-18 (Home Screen & Navigation Overhaul)

### 1. New Home (Welcome) Screen

**What was added:** A brand-new `Home` tab is now the **first and default tab** on launch, replacing the Watchlist as the landing screen.

**Content displayed:**
| Element | Detail |
|---|---|
| **Date** | Uppercase, e.g. `WEDNESDAY, 18 MARCH 2026` — Geist Mono, tertiary color |
| **Greeting** | Time-based: `Good morning.` / `Good afternoon.` / `Good evening.` — Inter SemiBold, 32sp |
| **Live Clock** | `h:mm a TZ` format (e.g. `10:17 PM IST`), refreshes every minute via `Timer` — Geist Mono |
| **Market Status** | NSE chip + NYSE chip at top-right. Neon yellow = Open, gray = Closed. Tappable → opens `dialog_market_hours.xml`. |
| **INDIA indexes** | NIFTY 50 (`^NSEI`) and SENSEX (`^BSESN`) — side-by-side cards with price + gain/loss pill |
| **US indexes** | S&P 500 (`^GSPC`) and NASDAQ (`^IXIC`) — side-by-side cards with price + gain/loss pill |
| **Top Mover** | Stock from the user's watchlist with the largest absolute `changePercent`. Shows symbol, name, price, pill. Falls back to an empty-state message if watchlist is empty. |

**New files:**

| File | Role |
|---|---|
| `res/layout/fragment_home.xml` | Full Welcome screen layout (ScrollView, date/greeting/clock, market chips, 4 index cards, top mover card) |
| `ui/home/HomeFragment.kt` | Fragment — drives all UI: clock timer, market status, observes ViewModel LiveData, applies pill styles |
| `ui/home/HomeViewModel.kt` | HiltViewModel — fetches 4 indexes via `StockRepository.fetchAndCacheStock()`, derives top mover from `getWatchlistSync()` |
| `res/drawable/ic_home.xml` | Home icon (outline house) for bottom nav |
| `res/drawable/bg_index_card.xml` | Transparent card with `border_color` stroke + 12dp corners, used for index and top mover cards |

**Index symbols used:** `^NSEI`, `^BSESN`, `^GSPC`, `^IXIC` — fetched via the existing `StockRepository.fetchAndCacheStock()` (same Yahoo Finance chart API).

---

### 2. 4-Tab Bottom Navigation

The app now has **4 tabs** instead of 3:

| Order | ID | Label | Fragment |
|---|---|---|---|
| 1st (default) | `nav_home` | Home | `HomeFragment` |
| 2nd | `nav_watchlist` | Watchlist | `WatchlistFragment` |
| 3rd | `nav_networth` | Net Worth | `NetWorthFragment` |
| 4th | `nav_settings` | Settings | `SettingsFragment` |

**Files changed:** `res/menu/bottom_nav_menu.xml`, `ui/main/MainActivity.kt`, `res/values/strings.xml`

- `MainActivity` now lazily initialises `HomeFragment` and sets it as both `activeFragment` and `selectedItemId` on startup.
- `setupFragments()` adds all 4 fragments with `hide()` for the 3 background ones; `homeFragment` starts visible.
- String key `tab_home` added; `tab_markets` kept (its value changed — see §3 below).

---

### 3. Watchlist Tab Renamed (Markets → Watchlist)

- Bottom nav label: `"Markets"` → **`"Watchlist"`**
- `tab_markets` string value updated to `"Watchlist"`.
- Toolbar title updates automatically via the same string.

**Files changed:** `res/menu/bottom_nav_menu.xml`, `res/values/strings.xml`

---

### 4. Market Status Chips Removed from Watchlist

The Open/Closed market status bar that sat at the **bottom** of the Watchlist screen has been removed entirely from that screen and relocated to the **Home screen** (top-right of the Markets section header).

**Removed from `WatchlistFragment`:**
- `market_bar` LinearLayout (both `chip_us_market` and `chip_india_market` child layouts)
- `updateMarketStatus()` method
- `showMarketHoursDialog()` method and `Market` enum
- Unused imports: `AlertDialog`, `MarketUtils`, `SimpleDateFormat`, `Calendar`, `Locale`, `TimeZone`

**Files changed:** `res/layout/fragment_watchlist.xml` (removed ~75 lines), `ui/main/WatchlistFragment.kt` (removed ~100 lines)

> ⚠️ **Do NOT re-add** market status chips to `fragment_watchlist.xml`. They now live exclusively in `fragment_home.xml` / `HomeFragment.kt`.
