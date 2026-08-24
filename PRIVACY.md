# Privacy Policy — trackOne

_Last updated: 2026-08-24_

trackOne is a local-first portfolio tracker. This page explains exactly what data the app touches, and when.

## By default: nothing leaves your device

If you never sign in, trackOne stores everything — your watchlist, net worth entries, and cached prices — only in a local database on your device. No account is required to use the app. Nothing is sent to us or any analytics/advertising service, because there is no such service integrated into the app.

## If you choose to sign in

Signing in (with Google or email/password) is optional and only needed for **Cloud Backup**. Signing in sends the following to Firebase, the backend we use for this feature:

- **Firebase Authentication**: your email address and a unique account ID, used solely to identify your account so backups can be restored to it. Managed by Google's Firebase service under [Google's Privacy Policy](https://policies.google.com/privacy).
- **Cloud Firestore**: when you tap **Backup**, your watchlist and net worth entries (symbols, quantities, prices, notes) are uploaded to a Firestore document scoped to your account. Restoring pulls that same data back down and replaces what's on your device. This is a manual, one-way operation in each direction — not continuous background sync.

No other personal data (name, photo, contacts, location, etc.) is collected, even if your Google account provides it.

## Third-party network requests

- **Yahoo Finance** (public, unofficial endpoint): stock/crypto symbols you track or search for are sent to Yahoo Finance to fetch prices and charts. No account identifiers are attached to these requests.

## What we don't do

- No analytics or crash-reporting SDKs.
- No advertising or ad SDKs.
- No selling or sharing of your data with third parties beyond the Firebase infrastructure described above.

## Your data, your control

- Export your local data to a JSON file at any time (Settings → Export Data), independent of any account.
- Delete your account's cloud backup by signing in and simply not maintaining it — cloud data only exists for accounts that have tapped **Backup**.
- Uninstalling the app removes all local data. It does not delete a Firestore backup — sign in and contact the maintainer if you want your cloud backup deleted.

## Contact

trackOne is an independent, open-source project. For questions about this policy, open an issue on the project's GitHub repository.
