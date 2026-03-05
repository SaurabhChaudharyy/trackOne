# trackOne — Finance Widget for Android

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="trackOne icon"/>
</p>

<p align="center">
  A minimal, dark-mode finance app and home-screen widget to track your<br/>
  watchlist, net worth, stocks, crypto, and more — all stored locally on your device.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?style=flat-square&logo=android&logoColor=white"/>
  <img src="https://img.shields.io/badge/Min%20SDK-26%20(Oreo)-blue?style=flat-square"/>
  <img src="https://img.shields.io/badge/Target%20SDK-34-blue?style=flat-square"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white"/>
  <img src="https://img.shields.io/badge/Architecture-MVVM-orange?style=flat-square"/>
</p>

---

## Features

| Tab | What it does |
|---|---|
| **Markets / Watchlist** | Track any stock or crypto symbol. Live prices via Yahoo Finance. Tap any item for a full candlestick / line chart with 1D → 5Y timeframes. |
| **Net Worth** | Add assets across 7 categories (Indian Stocks, US Stocks, Mutual Funds, Gold, Crypto, Cash, Bank). Collapsible sections. Auto-fetches current price for symbol-based assets. |
| **Settings** | Export your entire watchlist + net worth to a JSON backup file. Restore from a backup anytime. |
| **Home-screen Widget** | Scrollable stock list widget that updates in the background via WorkManager. Tap any row to open the detail screen. |

---

## Tech Stack

| Layer | Library |
|---|---|
| Language | Kotlin |
| UI | Android XML Views + Material 3 |
| Architecture | MVVM |
| Database | Room (KSP) |
| Dependency Injection | Dagger Hilt (KSP) |
| Networking | Retrofit + OkHttp + Gson |
| Background work | WorkManager |
| Image loading | Glide |
| Charts | MPAndroidChart |
| Async | Kotlin Coroutines |

---

## Data & Privacy

- **All data is stored locally** on your device in a Room (SQLite) database.
- **No account, no cloud sync, no analytics, no ads.**
- Stock/crypto prices are fetched from the public Yahoo Finance API — no API key required.
- You can export all your data to a JSON file at any time via **Settings → Export Data**, and restore it via **Import Data**.

---

## Building

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Steps

```bash
# 1. Clone the repo
git clone https://github.com/YOUR_USERNAME/trackOne.git
cd trackOne

# 2. Open in Android Studio — it will sync Gradle automatically
# OR build from the command line:
./gradlew assembleDebug

# 3. Install on a connected device / emulator
./gradlew installDebug
```

> **Note:** No API keys are needed. The app uses the public Yahoo Finance endpoint.

---

## Project Structure

```
app/src/main/
├── data/
│   ├── api/            # Retrofit service (Yahoo Finance)
│   ├── database/       # Room entities, DAOs, database class
│   ├── model/          # API response models
│   └── repository/     # StockRepository, BackupRepository
├── di/                 # Hilt AppModule
├── ui/
│   ├── config/         # Widget configuration activity
│   ├── detail/         # Stock detail screen + chart
│   ├── main/           # MainActivity, Watchlist fragment + adapter
│   ├── networth/       # Net Worth fragment + adapter + ViewModel
│   ├── settings/       # Settings fragment + ViewModel
│   └── widget/         # AppWidgetProvider + RemoteViews service
├── utils/              # FormatUtils, Resource wrapper
└── workers/            # StockUpdateWorker (WorkManager)
```

---

## Backup Format

Backups are plain JSON files (`trackone_backup_YYYYMMDD_HHmmss.json`):

```json
{
  "schema_version": 1,
  "exported_at_ms": 1741181454000,
  "app_package": "com.saurabh.financewidget",
  "watchlist": [
    { "symbol": "RELIANCE.NS", "display_name": "Reliance Industries Limited", "position": 0, "added_at_ms": 1740000000000 }
  ],
  "networth_assets": [
    { "name": "Bitcoin", "asset_type": "CRYPTO", "quantity": 0.05, "buy_price": 3500000.0, "current_value": 420000.0, "currency": "INR", "notes": "", "added_at_ms": 1740050000000, "updated_at_ms": 1741181454000 }
  ]
}
```

Valid `asset_type` values: `STOCK_IN` · `STOCK_US` · `MF` · `GOLD` · `CRYPTO` · `CASH` · `BANK`

---

## Known Limitations

- Yahoo Finance's public API is unofficial and may occasionally rate-limit or return stale data.
- The home-screen widget uses `RemoteViews`, which has strict constraints — complex layouts and custom attributes are not supported inside widget XMLs.
- No cloud sync currently. Use Export / Import to transfer data between devices.

---

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you'd like to change.

1. Fork the repo
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m 'feat: add my feature'`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

```
