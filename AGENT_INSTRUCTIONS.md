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

*When starting a new task, always refer back to these guidelines to maintain the established project structure and visual identity.*
