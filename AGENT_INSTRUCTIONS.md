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
    *   Content: time-based greeting, date (uppercase), live clock (updates every minute), market Open/Closed status chips for NSE and NYSE, 4 market index cards (NIFTY 50 `^NSEI`, SENSEX `^BSESN`, S&P 500 `^GSPC`, NASDAQ `^IXIC`), and **Top Mover** (largest absolute % change among all fetchable **portfolio** assets — Indian Stocks, US Stocks, Crypto, Gold, Silver).
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
| **Current schema version** | 4 |
| **Migration policy** | `fallbackToDestructiveMigration()` + explicit migrations (e.g. `MIGRATION_3_4`) — explicit migrations preserve data; fallback is a safety net |
| **Physical location on device** | `/data/data/com.saurabh.financewidget/databases/finance_widget_db` (internal storage, not accessible without root) |

### Tables

| Table | Entity Class | Primary Key | Purpose |
|---|---|---|---|
| `stocks` | `StockEntity` | `symbol` (String) | Cached stock data (price, change %, market cap, etc.) fetched from Yahoo Finance |
| `watchlist` | `WatchlistEntity` | `(symbol, groupId)` **composite** | Per-group stock membership — same symbol can exist in multiple groups independently |
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
Android Auto Backup (`android:allowBackup`) is currently **disabled** in the manifest. If it is enabled in the future, add explicit `android:backupRules` to exclude the Room database file (to avoid restoring a stale schema that triggers `fallbackToDestructiveMigration`).

---

## Settings Tab — Data Management

The Settings tab exposes **one** data-ingestion action and **no** JSON backup/restore. All JSON import/export (watchlist, assets) was removed in the 2026-04-01 session (see Session Changes Log below).

### What remains (Settings screen)

| Card | Purpose |
|---|---|
| `card_import_broker_csv` | Import holdings from broker CSV/XLSX (Zerodha, Groww, HDFC, Vested, IB) |

> ⚠️ **Do NOT re-add any JSON export or import cards** to the Settings screen. The broker CSV/XLSX importer is the sole data-ingestion mechanism.

### Relevant Files

| File | Role |
|---|---|
| `data/repository/BackupRepository.kt` | Still exists in the data layer (watchlist/asset serialisation helpers used internally) — **not exposed in UI** |
| `data/repository/BrokerCsvRepository.kt` | Core import engine — detects broker format, parses CSV or XLSX, inserts via `NetWorthDao` |
| `ui/settings/SettingsViewModel.kt` | Exposes only `importBrokerCsv(uri)` and `resetState()` |
| `ui/settings/SettingsFragment.kt` | Registers only `importBrokerCsvLauncher`; observes `CsvImportSuccess`, `FetchingPrices`, `Error`, `Loading`, `Idle` states |

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
| **Top Mover** | Fetchable portfolio asset with the largest absolute `changePercent`. Shows symbol, name, price, pill. Falls back to an empty-state message if portfolio has no fetchable assets. Source: `NetWorthDao.getAllAssetsSync()` filtered to `STOCK_IN`, `STOCK_US`, `CRYPTO`, `GOLD`, `SILVER`. |

**New files:**

| File | Role |
|---|---|
| `res/layout/fragment_home.xml` | Full Welcome screen layout (ScrollView, date/greeting/clock, market chips, 4 index cards, top mover card) |
| `ui/home/HomeFragment.kt` | Fragment — drives all UI: clock timer, market status, observes ViewModel LiveData, applies pill styles |
| `ui/home/HomeViewModel.kt` | HiltViewModel — fetches 4 indexes via `StockRepository.fetchAndCacheStock()`, derives top mover from **portfolio** (`netWorthDao.getAllAssetsSync()`, fetchable types only) |
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

---

## Session Changes Log — 2026-03-23 (Net Worth P&L Display)

### 1. Total Portfolio P&L Chip

**What was added:** A visually distinct P&L chip (`tv_total_pnl`) is now displayed directly below the Total Net Worth figure in `fragment_networth.xml`. It shows the overall absolute gain/loss and percentage change across all tracked assets.

#### Display logic

| Asset has `buyPrice > 0` | Asset has `buyPrice == 0` |
|---|---|
| `invested = buyPrice × quantity`, `current = currentValue` | Treated as break-even: `invested = current = currentValue` (no artificial P&L inflated) |

- **Chip hidden** when total P&L is exactly `0.0 / 0.0%` (e.g., no asset with a buy price set yet).
- **Arrow icons**: `↗` (gain) / `↘` (loss), same convention as `StockDetailActivity`.
- **Colors**: gains use `R.color.gain_green` text + `R.color.gain_green_bg` fill; losses use `R.color.loss_red` + `R.color.loss_red_bg`.
- Background is `bg_pnl_chip` drawable (rounded pill), tinted programmatically via `GradientDrawable.mutate()`.

#### New ViewModel LiveData

Added to `NetWorthViewModel`:

```kotlin
/** Pair<absolutePnL, percentPnL>.
 *  For assets where buyPrice > 0: pnl = currentValue - buyPrice * quantity
 *  For assets where buyPrice == 0: invested = currentValue, pnl = 0
 */
val totalPnL: LiveData<Pair<Double, Double>> = allAssets.map { assets ->
    var totalInvested = 0.0
    var totalCurrent  = 0.0
    for (asset in assets) {
        if (asset.buyPrice > 0.0) {
            totalInvested += asset.buyPrice * asset.quantity
            totalCurrent  += asset.currentValue
        } else {
            totalInvested += asset.currentValue
            totalCurrent  += asset.currentValue
        }
    }
    val absChange = totalCurrent - totalInvested
    val pct = if (totalInvested > 0.0) (absChange / totalInvested) * 100.0 else 0.0
    Pair(absChange, pct)
}
```

**Files changed:** `NetWorthViewModel.kt`, `NetWorthFragment.kt` (`observeViewModel()`), `fragment_networth.xml` (`tv_total_pnl` TextView added below `tv_total_networth`), `res/drawable/bg_pnl_chip.xml` (new rounded pill drawable)

---

### 2. Smart Insert — `addOrMergeAsset()`

**Problem:** Adding a second Gold/Silver/Crypto entry (without a buy price) created a duplicate row rather than merging with the existing no-buy-price holding.

**Solution:** Replaced direct `viewModel.addAsset()` calls with a new `addOrMergeAsset()` function in `NetWorthViewModel`:

| Condition | Action |
|---|---|
| `asset.buyPrice > 0.0` | Always insert as a **distinct lot** (e.g. separate DCA purchase) |
| `asset.buyPrice == 0.0` | Find existing record with same `name + assetType` **and** `buyPrice == 0`; if found, **merge** (`quantity +=`, `currentValue +=`); otherwise insert new |

The merge find is backed by a new DAO query: `findMergeCandidate(name, assetType)`.

**Files changed:** `NetWorthViewModel.kt` (new `addOrMergeAsset()`), `NetWorthDao.kt` (new `findMergeCandidate()` query), `NetWorthFragment.kt` (calls `addOrMergeAsset` instead of `addAsset`)

---

### Updated Phase 2 Status

| Item | Status |
|---|---|
| Section-level P&L totals | 🔲 Planned |
| ~~Total Net Worth P&L card~~ | ✅ **Implemented** (see §1 above — chip below total, not full card redesign) |
| ~~Edit asset dialog~~ | ✅ **Implemented** (Session 2026-03-18) |

---

## Session Changes Log — 2026-03-30 (Broker Portfolio Import)

### 1. Broker CSV / XLSX Import Feature

**Commits:** `f7f3d47` (initial import), `284cdd9` (USD→INR conversion + US Stocks)

**Purpose:** Allow users to import their brokerage portfolio directly from a CSV/XLSX export file, appending holdings to the Net Worth tab without deleting existing data.

#### New File

| File | Role |
|---|---|
| `data/repository/BrokerCsvRepository.kt` | Core import engine — detects broker format, parses CSV or XLSX, maps rows to `NetWorthAssetEntity` records, inserts via `NetWorthDao` |

#### Modified Files

| File | Change |
|---|---|
| `ui/settings/SettingsFragment.kt` | Added `importBrokerCsvLauncher` (SAF `OpenDocument` contract), `showBrokerCsvImportConfirmationDialog()`, `FetchingPrices` state handler |
| `ui/settings/SettingsViewModel.kt` | Added `importBrokerCsv(uri)` coroutine — calls `BrokerCsvRepository`, then `NetWorthRepository.refreshNetWorthAssets()` for live prices |
| `res/layout/fragment_settings.xml` | Added `card_import_broker_csv` card in the STOCKS & INVESTMENTS section |
| `app/build.gradle.kts` | Added Apache POI dependency for XLSX support (later **removed** — see §2) |
| `gradle/libs.versions.toml` | Added/removed library version entries accordingly |

#### Supported Broker Formats

| Format ID | Broker(s) | Column layout | Asset Type |
|---|---|---|---|
| `FORMAT_A` | HDFC Securities, Angel One | `Stock Name \| ISIN \| Qty \| Avg Buy Price \| Buy Value \| Closing Price \| Closing Value \| P&L` | `STOCK_IN` |
| `FORMAT_B` | Zerodha, Groww | `Instrument \| Qty. \| Avg. cost \| LTP \| Invested \| Cur. val \| P&L \| Net chg.` | `STOCK_IN` |
| `FORMAT_C` | Vested, Interactive Brokers (US stocks) | `name \| quantity \| buyPrice \| currentValue` | `STOCK_US` |

**Auto-detection logic** (`detectFormat()`): inspects the lowercased header row joined by `|` for landmark column names (e.g. `"stock name"`, `"isin"` → FORMAT_A; `"instrument"`, `"avg. cost"` → FORMAT_B; exact 4-column match `name|quantity|buyprice|currentvalue` → FORMAT_C).

#### XLSX Parsing — No Apache POI

Instead of Apache POI (which caused large APK size and Proguard complexity), XLSX files are parsed using **a hand-rolled ZIP + XmlPullParser approach**:
1. Read the file as a ZIP stream (`ZipInputStream`).
2. Parse `xl/sharedStrings.xml` first to build the shared-string table.
3. Parse `xl/worksheets/sheet1.xml`, mapping `<c r="B3" t="s"><v>5</v>` to resolved string values via the shared-string table.
4. Output is `List<List<String>>` — same structure as CSV rows, so the same `FORMAT_A/B/C` parsers handle both.

> ⚠️ **Do NOT re-add Apache POI / `fastexcel` / any XLSX library** — the hand-rolled parser is intentional and avoids the Proguard/R8 complexities those libraries introduce.

#### Vested Name → Ticker Mapping

Vested exports use full company names (e.g. `"APPLE INC"`) instead of tickers. A static lookup map `VESTED_NAME_TO_TICKER` in the companion object maps these to Yahoo Finance symbols (e.g. `"AAPL"`). The map covers ~30 common US stocks/ETFs. If a name is not found, the raw uppercased value is used as-is (works for Interactive Brokers which already exports tickers).

**To add more Vested mappings:** Add entries to `VESTED_NAME_TO_TICKER` in `BrokerCsvRepository.kt`. No other file needs to change.

#### Import Behaviour

- **Append-only**, never deletes existing data.
- A confirmation dialog is shown before importing.
- `imported` = number of rows successfully inserted; `skipped` = rows that failed to parse (logged as warnings).
- After parsing, `NetWorthRepository.refreshNetWorthAssets()` is called to fetch live prices — see §2 for full details.

---

### 2. USD → INR Auto-Conversion for US Stock Imports

**Commit:** `284cdd9`

When a `FORMAT_C` (US stocks) file is imported, holdings are stored with `currency = "USD"` and a placeholder `currentValue` (from the export file). After import, `NetWorthRepository.refreshNetWorthAssets()` is called immediately to fetch live INR prices.

#### Live Price Refresh Pipeline

`NetWorthRepository.refreshNetWorthAssets()` was extended to handle USD assets:

| Step | Detail |
|---|---|
| 1. Fetch `USDINR=X` exchange rate | One-shot call to Yahoo Finance; cached in-memory for the duration of the refresh |
| 2. Fetch USD stock price | Yahoo Finance ticker (e.g. `AAPL`) → price in USD |
| 3. Convert | `currentValue = usdPrice × quantity × usdToInr` |
| 4. Set currency | `currency = "INR"` (stored as INR from this point onward) |
| 5. Persist | Single `DAO.updateAsset()` call per asset |

**Key invariant:** After a successful refresh, all `STOCK_US` assets in Room have `currency = "INR"` and `currentValue` in rupees. The `currency` field is never shown in the UI — it only controls the conversion pipeline.

#### `UiState` — FetchingPrices State

`BackupUiState.FetchingPrices` was added to the sealed class. The Settings fragment shows a blocking non-cancellable progress dialog with label "Fetching live prices…" while this state is active. The label then changes to "Importing…" when `Loading` is active.

#### MainViewModel — Real-time P&L Fix

`MainViewModel` was updated to re-trigger the net worth asset refresh after the broker import completes, so that the Net Worth tab reflects the newly imported + live-priced holdings immediately without requiring the user to manually navigate away and back.

---

### 3. Complete Removal of JSON Import & Export (2026-04-01)

**Date:** 2026-04-01

All JSON-based backup UI has been **fully removed** from the Settings screen. This includes the previously-kept Watchlist export, Watchlist import, and Assets (Stocks & Investments) export cards. The broker CSV/XLSX importer (`card_import_broker_csv`) is now the **sole** data-management action in Settings.

#### What was removed

| Location | Removed |
|---|---|
| `fragment_settings.xml` | Entire WATCHLIST section (`card_export_watchlist`, `card_import_watchlist`) + `card_export_assets` card |
| `SettingsFragment.kt` | `exportWatchlistLauncher`, `importWatchlistLauncher`, `exportAssetsLauncher` registrations; corresponding `setOnClickListener` blocks; `WatchlistExportSuccess`, `WatchlistImportSuccess`, `AssetsExportSuccess` state handlers; `showWatchlistImportConfirmationDialog()` method; unused imports (`BackupRepository`, `SimpleDateFormat`, `Date`, `Locale`) |
| `SettingsViewModel.kt` | `WatchlistExportSuccess`, `WatchlistImportSuccess`, `AssetsExportSuccess` sealed class entries; `exportWatchlist(uri)`, `importWatchlist(uri)`, `exportAssets(uri)` functions; `backupRepository` constructor injection; unused imports (`BackupRepository`, `BackupResult`, `ImportResult`) |

> ⚠️ **Do NOT re-add any JSON export or import card** to the Settings screen. `BackupRepository.kt` still exists in the data layer and can be used internally if needed, but it must not be exposed through the UI.

#### What remains (Settings screen)

| Card | Purpose |
|---|---|
| `card_import_broker_csv` | Import holdings from broker CSV/XLSX (Zerodha, Groww, HDFC, Vested, IB) |

---

### 4. File-Hash Deduplication for Broker CSV Uploads

**Problem:** A user uploading the same broker CSV file multiple times would import the same holdings repeatedly, inflating their net worth. The upsert logic prevented _exact_ share duplicates but could not detect that the same _file_ was already imported.

**Solution:** A SHA-256 hash of the CSV/XLSX byte content is computed before parsing. The hash is stored as a `SharedPreferences` `Set<String>` keyed `imported_file_hashes`. If the incoming file's hash is already in the set, the import is aborted and the user sees a snackbar indicating the file has already been imported.

**Key details:**
- Hash computed via `MessageDigest.getInstance("SHA-256")` on the raw `InputStream` bytes — no temp file written to disk.
- The `InputStream` cannot be read twice; bytes are buffered into a `ByteArray` first (`readBytes()`), then both the hash check and the parser receive the same array.
- Fix for `Unresolved reference: InputStream` build error — correct import is `java.io.InputStream`.

**Files changed:** `BrokerCsvRepository.kt` (hash compute + deduplicate check), `SettingsViewModel.kt` (reads `SharedPreferences` for the hash set)

> ⚠️ The hash set is stored in app `SharedPreferences`. It persists across sessions but is wiped on app uninstall along with all other app data.

---

## Session Changes Log — 2026-04-01 (Bulk Portfolio Removal · Swipe Indicator · Top Mover)

### 1. Bulk Asset Removal from Net Worth (Long-Press Selection Mode)

**Problem:** After importing a broker CSV the user needed a quick way to remove multiple holdings at once without tapping the individual `×` button on every row.

**Solution:** A full multi-select / bulk-delete workflow was added directly inside the existing Net Worth list UI.

#### UX Flow

1. **Long-press** any asset row → haptic feedback → that section enters **selection mode**.
2. The section **auto-expands** (all items shown, not just the top-N preview).
3. All rows show a `MaterialCheckBox`; edit & delete buttons are hidden.
4. A **sticky bottom action bar** (`ll_selection_bar`) slides into view:
   - **Cancel** — exits selection mode without changes.
   - **`N selected · Section Name`** count label.
   - **Select All / Deselect All** toggle.
   - **🗑 Delete** (red, disabled while 0 selected, alpha `0.38`).
5. Tapping rows toggles checkboxes.
6. **Delete** → confirmation `AlertDialog` → `viewModel.deleteAssets(ids)` → exits selection mode.
7. **Back press** exits selection mode (handled by `OnBackPressedCallback`).
8. Entering selection mode in one section automatically exits any active selection in another.

#### Files Changed

| File | Change |
|---|---|
| `NetWorthDao.kt` | `@Query("DELETE FROM networth_assets WHERE id IN (:ids)") suspend fun deleteAssetsByIds(ids: List<Long>)` |
| `NetWorthViewModel.kt` | `fun deleteAssets(ids: Set<Long>)` — delegates to `deleteAssetsByIds` |
| `item_networth_asset.xml` | Added `MaterialCheckBox` (`id: checkbox_select`, `visibility="gone"`, `app:useMaterialThemeColors="true"`) after `btn_delete_asset`. Added `xmlns:app` namespace. |
| `fragment_networth.xml` | Root changed `NestedScrollView` → `FrameLayout`; `NestedScrollView` is now a child (`id="nested_scroll_view"`). `LinearLayout` (`id="ll_selection_bar"`, `layout_gravity="bottom"`, `elevation="12dp"`, `visibility="gone"`) added as a sibling floating over scroll content. |
| `NetWorthAssetAdapter.kt` | **Full rewrite.** Added `onLongPress` + `onSelectionChanged` constructor callbacks. Public API: `enterSelectionMode(initialId)`, `exitSelectionMode()`, `selectAll()`, `deselectAll()`, `getSelectedIds()`, `areAllSelected()`. Selection mode shows checkboxes, hides edit/delete, rows clickable. Normal mode shows edit/delete, rows long-pressable. |
| `NetWorthFragment.kt` | `selectionModeType: AssetType?` field. `OnBackPressedCallback` for back-press exit. `setupRecyclerViews()` passes `onLongPress`/`onSelectionChanged`. New methods: `setupSelectionBar()`, `enterSelectionMode()`, `exitSelectionMode()`, `updateSelectionBar()`, `confirmDeleteSelected()`. |

#### Why MaterialCheckBox

The old `CheckBox` with `android:buttonTint="@color/text_primary"` painted the button solid black — making the white ✓ invisible. `MaterialCheckBox` with `app:useMaterialThemeColors="true"` renders a Material Design filled box with a clearly visible white checkmark.

---

### 2. Watchlist Swipe-to-Delete "Remove" Indicator

**Problem:** Left-swipe to delete a watchlist item showed no visual feedback — the row simply slid off with no affordance.

**Solution:** Overrode `onChildDraw` in the existing `ItemTouchHelper.SimpleCallback` in `WatchlistFragment`.

- As the user drags left, a `loss_red` background strip expands behind the item in real time.
- Once **≥ 60 dp** is revealed, **"Remove"** appears centred in the strip (white, bold, 13sp).
- `super.onChildDraw(...)` called after drawing so normal item translation still works.

**Files changed:** `WatchlistFragment.kt` (added `onChildDraw` override + `android.graphics.{Canvas,Paint,Typeface,Color}` imports)

---

### 3. Top Mover Source — Watchlist → Portfolio

**Previous:** `HomeViewModel.fetchTopMover()` called `repository.getWatchlistSync()`.

**New:** Reads portfolio holdings via `netWorthDao.getAllAssetsSync()`, filters to `STOCK_IN`, `STOCK_US`, `CRYPTO`, `GOLD`, `SILVER`, deduplicates by name, fetches live prices, returns the largest abs `changePercent`.

**Special symbol mappings inside `fetchTopMover()`:**
- `GOLD` → `GC=F`, `SILVER` → `SI=F`, all others → `asset.name` directly.

**Empty state text:** `"Add stocks to your watchlist…"` → `"Add investments to your portfolio to see today's top mover"`

**Why:** Users who import via broker CSV may have no watchlist entries, making the Top Mover card always empty. Portfolio is the authoritative source of the user's actual holdings.

**Files changed:** `HomeViewModel.kt` (`NetWorthDao` injected, `fetchTopMover()` rewritten), `fragment_home.xml` (empty state string)

---

## Session Changes Log — 2026-04-03 (Multiple Watchlists)

### 1. Multiple Named Watchlists

**Purpose:** Users can now create, rename, and delete multiple named watchlists (e.g. "Tech", "India", "Crypto") accessible as tabs at the top of the Watchlist screen.

#### New DB Table: `watchlist_groups`

| Column | Type | Notes |
|---|---|---|
| `id` | Long (PK, autoGenerate) | Row 1 is always the default "My Watchlist" |
| `name` | String | User-editable display name |
| `position` | Int | Sort order of the tab |
| `createdAt` | Long | Epoch ms |

`WatchlistEntity` gains a new **`groupId: Long`** column (default `1L`) — the foreign-key tie to `watchlist_groups.id`. The DB version was bumped to **3** (fallbackToDestructiveMigration wipes old data on upgrade).

#### UX Flow

| Action | Trigger |
|---|---|
| **Switch watchlist** | Tap a tab in the horizontal strip |
| **Create watchlist** | Tap the **`+`** button next to the tabs → name dialog |
| **Rename watchlist** | Long-press any tab → PopupMenu → *Rename* |
| **Delete watchlist** | Long-press any tab → PopupMenu → *Delete* (disabled when only 1 list remains) |

#### Tab Strip Design

- A horizontally-scrollable `LinearLayout` (`id: ll_tabs`) inside an `HorizontalScrollView` (`id: hsv_tabs`) sits above the RecyclerView.
- Active tab: **black** (`text_primary`) label text + neon-yellow (`#D4E510`) 3dp bottom-bar indicator (`bg_watchlist_tab_active.xml`).
- Inactive tabs: gray (`text_tertiary`) text, no background.
- A transparent `ImageButton` (`btn_add_watchlist`) with a `+` icon (`ic_add_watchlist.xml`) sits at the trailing edge.

#### Files Changed / Created

| File | Change |
|---|---|
| `data/database/Entities.kt` | Added `WatchlistGroupEntity`; added `groupId: Long = 1L` to `WatchlistEntity` |
| `data/database/Daos.kt` | Added `WatchlistGroupDao`; updated `WatchlistDao` with group-scoped queries (`getWatchlistByGroup`, `getWatchlistSyncByGroup`, `removeFromWatchlistInGroup`, `updatePosition(symbol, groupId, position)`, `getMaxPositionInGroup`) |
| `data/database/FinanceDatabase.kt` | Version **2 → 3**, registered `WatchlistGroupEntity` + `WatchlistGroupDao` |
| `di/AppModule.kt` | Added `provideWatchlistGroupDao()` |
| `data/repository/StockRepository.kt` | Injected `WatchlistGroupDao`; added `ensureDefaultGroup()`, `createWatchlistGroup()`, `renameWatchlistGroup()`, `deleteWatchlistGroup()`, `getWatchlistGroups()`, `getWatchlistByGroup()`; all add/remove/reorder now take `groupId` |
| `ui/main/MainViewModel.kt` | Added `watchlistGroups`, `activeGroupId`, `activeGroupWatchlist`; `watchlistStocks` is now a `switchMap` over active group; added `selectGroup()`, `createWatchlistGroup()`, `renameWatchlistGroup()`, `deleteWatchlistGroup()` |
| `ui/main/WatchlistFragment.kt` | Full rewrite — builds tab strip dynamically from `watchlistGroups` LiveData; handles long-press rename/delete popup; wires create dialog to `+` button |
| `ui/config/ConfigViewModel.kt` | `addToWatchlist` / `removeFromWatchlist` / `addToWatchlistSync` accept optional `groupId` (default `DEFAULT_GROUP_ID`) |
| `ui/splash/SplashViewModel.kt` | Calls `repository.ensureDefaultGroup()` on first launch |
| `res/layout/fragment_watchlist.xml` | Restructured as vertical `LinearLayout`; tab strip row + divider on top; `SwipeRefreshLayout` fills rest |
| `res/layout/item_watchlist_tab.xml` | New — single tab chip layout (TextView inside LinearLayout) |
| `res/layout/dialog_watchlist_name.xml` | New — name input dialog for create / rename |
| `res/drawable/ic_add_watchlist.xml` | New — plus-icon vector for add-button |
| `res/drawable/bg_watchlist_tab_active.xml` | New — neon-yellow 3dp bottom bar for active tab |

> ⚠️ Because `fallbackToDestructiveMigration()` is in effect, existing watchlist data is wiped on the v2→v3 upgrade. This is acceptable per the project policy.

---

## Session Changes Log — 2026-04-06 (Watchlist Multi-Group Bug Fixes & Validation)

### 1. AlertDialog Type Mismatch Fix

**Problem:** `MaterialAlertDialogBuilder.create()` returns `androidx.appcompat.app.AlertDialog`, but `triggerCreateWatchlist()` and `triggerRenameWatchlist()` in `WatchlistFragment` typed their `dialog` parameter as `android.app.AlertDialog` — causing a compile-time type mismatch.

**Fix:** Changed both function signatures from `android.app.AlertDialog` → `androidx.appcompat.app.AlertDialog`.

**Files changed:** `ui/main/WatchlistFragment.kt`

---

### 2. Stocks Added to Wrong Watchlist Group

**Root cause (schema):** `WatchlistEntity` used `symbol` alone as `@PrimaryKey`. A stock can only occupy one row in the `watchlist` table, so adding the same symbol to a second group silently failed (`OnConflictStrategy.IGNORE`). Every stock therefore lived in whichever group first inserted it — almost always the default "My Watchlist" (group 1).

**Fix:** Changed `WatchlistEntity` to a **composite primary key `(symbol, groupId)`** using `@Entity(primaryKeys = ["symbol", "groupId"])`. The same stock can now exist independently in multiple groups.

```kotlin
// Before
@Entity(tableName = "watchlist")
data class WatchlistEntity(
    @PrimaryKey val symbol: String, ...
)

// After
@Entity(tableName = "watchlist", primaryKeys = ["symbol", "groupId"])
data class WatchlistEntity(
    val symbol: String, ..., val groupId: Long = 1L, ...
)
```

**DB migration:** Version bumped **3 → 4**. `MIGRATION_3_4` in `FinanceDatabase.kt` recreates the `watchlist` table with the new composite PK and migrates all existing rows (preserving them under `groupId = 1`). Wired into `Room.databaseBuilder` via `.addMigrations(MIGRATION_3_4)`.

**Root cause (WidgetConfigActivity):** `WidgetConfigActivity` (the "Add Stocks" search screen) always called `addToWatchlistSync(symbol, name)` with no `groupId`, defaulting to `DEFAULT_GROUP_ID = 1L`, regardless of which watchlist was active.

**Fix:**
- `WidgetConfigActivity.start()` now accepts a `groupId: Long = 1L` parameter and passes it as an Intent extra (`EXTRA_GROUP_ID`).
- `WatchlistFragment` reads `viewModel.activeGroupId.value` and passes it when calling `WidgetConfigActivity.start(requireActivity(), groupId)` — for both the "Add Stock" button in the toolbar and the "Add stock" button inside the empty-state layout.
- `WidgetConfigActivity` reads the extra and forwards `activeGroupId` to `addToWatchlistSync(symbol, name, activeGroupId)`.

**Files changed:** `data/database/Entities.kt`, `data/database/FinanceDatabase.kt`, `di/AppModule.kt`, `ui/config/WidgetConfigActivity.kt`, `ui/main/WatchlistFragment.kt`

#### Updated Database Table Reference

| Table | Entity Class | Primary Key | Purpose |
|---|---|---|---|
| `stocks` | `StockEntity` | `symbol` (String) | Cached stock price data |
| `watchlist_groups` | `WatchlistGroupEntity` | `id` (Long, autoGenerate) | Named watchlist groups |
| `watchlist` | `WatchlistEntity` | `(symbol, groupId)` **composite** | Per-group stock membership + order |
| `price_history` | `PriceHistoryEntity` | `id` (Long, autoGenerate) | OHLCV chart data |
| `networth_assets` | `NetWorthAssetEntity` | `id` (Long, autoGenerate) | Net Worth holdings |

> **Current DB schema version: 4**

---

### 3. Tab-Switch Flicker Fix

**Problem:** Switching between watchlist tabs caused a visible "white flash" / flicker of the list content.

**Two distinct causes were identified and fixed separately:**

#### Cause A — Duplicate LiveData emissions (populated → populated)
The nested `switchMap` chain in `MainViewModel.watchlistStocks` could fire twice with the same list when switching groups (Room delivers a cached value immediately, then re-queries). The second identical emission re-drove the adapter and `updateEmptyState`, causing a redundant layout pass.

**Fix:** Added `.distinctUntilChanged()` to `watchlistStocks` in `MainViewModel`.

```kotlin
val watchlistStocks: LiveData<List<StockEntity>> =
    _activeGroupId.switchMap { groupId ->
        repository.getWatchlistByGroup(groupId).switchMap { groupItems ->
            ...
        }
    }.distinctUntilChanged()  // ← added
```

#### Cause B — RecyclerView ItemAnimator (empty → populated)
When the adapter transitions from 0 items → N items, `DefaultItemAnimator` plays an "add" animation for every item at the exact moment `RecyclerView` changes from `GONE` to `VISIBLE`. This creates a one-frame blank flash.

**Fix:** In `WatchlistFragment.observeViewModel()`, detect the `wasEmpty && isNowPopulated` transition and temporarily null the `itemAnimator` for one frame (restoring it on the next `post{}`).

```kotlin
viewModel.watchlistStocks.observe(viewLifecycleOwner) { stocks ->
    val wasEmpty = adapter.items.isEmpty()
    if (wasEmpty && stocks.isNotEmpty()) {
        val saved = binding.recyclerViewWatchlist.itemAnimator
        binding.recyclerViewWatchlist.itemAnimator = null
        binding.recyclerViewWatchlist.post { binding.recyclerViewWatchlist.itemAnimator = saved }
    }
    adapter.submitList(stocks)
    updateEmptyState(stocks.isEmpty())
}
```

> The item animations for normal add/remove/reorder operations are **not affected** — the animator is only suppressed for the one frame when a previously-empty group first receives its items.

**Files changed:** `ui/main/MainViewModel.kt`, `ui/main/WatchlistFragment.kt`

---

### 4. Watchlist Duplicate Name & Count Limit Validation

**Problem:** Users could create multiple watchlists with the same name (including case variants like "Tech" vs "tech"), and could create unlimited watchlists.

**Fix:** Three layers of validation added:

| Layer | Location | Checks |
|---|---|---|
| `+` **button click guard** | `btnAddWatchlist.setOnClickListener` | If `groups.size >= 5` → shows Snackbar, dialog never opens |
| `+` **button visual dim** | `watchlistGroups` observer | `btnAddWatchlist.alpha = 0.35f` when at limit, `1f` otherwise (reactive) |
| **Create dialog confirm** | `triggerCreateWatchlist()` | (1) Empty name, (2) count ≥ limit, (3) case-insensitive duplicate name → inline error on `TextInputLayout` |
| **Rename dialog confirm** | `triggerRenameWatchlist()` | Case-insensitive duplicate name, **excluding the group's own current name** (so saving an unchanged name doesn't error) → inline error |

```kotlin
companion object {
    private const val MAX_WATCHLISTS = 5  // single constant — change here to adjust limit
}
```

**Key behaviours:**
- Duplicate check is **case-insensitive**: `"tech"` and `"Tech"` are treated as identical.
- Rename self-exclusion: `it.id != group.id` in the duplicate check lets the user save a rename with conceptually the same casing without triggering a false positive.
- All validation errors show **inline** in the `TextInputLayout` — the dialog stays open for correction rather than dismissing.

**Files changed:** `ui/main/WatchlistFragment.kt`
