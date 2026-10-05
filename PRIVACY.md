# Privacy Policy — trackOne

_Last updated: 2026-10-06_

trackOne is a local-first portfolio tracker. This page explains exactly what data the app touches, and when.

## By default: nothing leaves your device

If you never sign in, trackOne stores everything — your watchlist, net worth entries, and cached prices — only in a local database on your device. No account is required to use the app. Nothing is sent to us or any analytics/advertising service, because there is no such service integrated into the app.

## If you choose to sign in

Signing in (with Google or email/password) is optional and only needed for **Cloud Backup**. Signing in sends the following to Firebase, the backend we use for this feature:

- **Firebase Authentication**: your email address and a unique account ID, used solely to identify your account so backups can be restored to it. Managed by Google's Firebase service under [Google's Privacy Policy](https://policies.google.com/privacy).
- **Cloud Firestore**: when you tap **Backup**, your watchlist and net worth entries (symbols, quantities, prices, notes) are uploaded to a Firestore document scoped to your account. Restoring pulls that same data back down and replaces what's on your device. This is a manual, one-way operation in each direction — not continuous background sync.

No other personal data (name, photo, contacts, location, etc.) is collected, even if your Google account provides it.

## Third-party network requests

- **Yahoo Finance** (public, unofficial endpoint): stock/crypto symbols you track, hold or search for are sent to Yahoo Finance to fetch prices and charts. When a holding was imported or saved under a company name instead of a ticker (some broker statements list only the name), that company name or its ISIN is also sent to Yahoo Finance's search so the app can find the right ticker. No account identifiers are attached to these requests.

## Crash reporting

- **Firebase Crashlytics** collects crash logs, stack traces, and basic device/app info (device model, OS version, app version, and a Firebase-generated installation ID) whenever the app crashes or encounters a handled error. This runs regardless of whether you're signed in, and never includes your watchlist, net worth, or other portfolio data.

## What we don't do

- No advertising or ad SDKs.
- No selling or sharing of your data with third parties beyond the Firebase infrastructure described above.

## Your data, your control

- Delete your account and its cloud backup at any time from **Settings → Delete Account** (details below).
- Uninstalling the app removes all local data. It does **not** delete your account or cloud backup, so delete the account first, or use the email route below.

## Delete your trackOne account and data

You can delete your trackOne account and everything stored for it in the cloud, from inside the app:

1. Sign in, then open **Settings**.
2. Tap **Delete Account** and confirm. If you signed in a while ago, you will be asked to confirm it's you first (your password, or your Google account).

**What is deleted:** your Firebase Authentication account (email address and account ID) and everything in your Cloud Firestore backup (watchlists, net worth entries and the backup time). It is deleted permanently and cannot be recovered.

**What is not changed:** the data stored on your device. Use **Settings → Clear Local Data**, or uninstall the app, to remove that. Crash reports (Firebase Crashlytics) are not linked to your account and are not removed by deleting it.

**If you can no longer open the app** (for example you uninstalled it, or lost your phone): email [maintainer contact email] from the address your account uses, with the subject "trackOne account deletion", and we will delete your account and cloud backup.

## Contact

trackOne is an independent, open-source project. For questions about this policy, open an issue on the project's GitHub repository.
