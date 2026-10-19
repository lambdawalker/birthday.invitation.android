# Abi · Android invitation sender

Private Kotlin/Jetpack Compose companion for [birthday.invitation](https://github.com/lambdawalker/birthday.invitation). Android 8+ (API 26), target/compile API 35, JDK 17. Sends using your SIM; no external messaging service.

## Build
Open in Android Studio with Android SDK 35 installed, or:
```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```
Windows: `gradlew.bat testDebugUnitTest lintDebug assembleDebug`.
Debug APK: `app/build/outputs/apk/debug/app-debug.apk`; CI uploads it as an artifact. Debug signing is for private testing; use your own stable release key for ongoing distribution. No signing secrets are committed.

## Connect
1. Deploy the website/backend and import families.
2. In the web sending page, open **App users**, enter a username, and click **Create user and PIN**.
3. Enter the username and eight-digit single-use PIN into the app. The server defaults to `https://xitlaliabigail.com`; use **Server settings** only for another deployment. PINs expire in 15 minutes and lock after five incorrect attempts. The resulting session is encrypted using Android Keystore; the PIN is not saved and backup is disabled.
4. Grant SMS and phone-state permissions, and choose the correct SIM. `SEND_SMS` is a restricted permission: installation method/OEM policy may require additional permission handling. Test on the target phone; installing from Android Studio/ADB is recommended for private development. The app is not a default SMS handler and is not designed for unrestricted Play Store publication.
5. Assign one family to this phone and test. Then assign the remaining pending families.

## SMS
The app sends a short one-segment message per family after explicit batch confirmation, with a 10-second gap. Keep the app open; leaving the app pauses new sends. It does not change Android rate limits. Carrier/SIM charges and carrier filtering still apply. Messages sent by the app are SMS, not RCS. Replies go to the phone's regular SMS app.

Before transmission, the app records an attempt locally, registers it with the backend, then records dispatch intent before calling Android. A manifest receiver stores sent/delivery callbacks. Unknown/crashed attempts become **Por verificar**, never automatically resent. The app syncs outcomes when opened/refreshed. Delivery receipts depend on the carrier. Backend and local database cannot create an exactly-once guarantee across a cellular network; reconciliation is intentional.

## WhatsApp
Assign the family, tap WhatsApp, and send the prefilled invitation in WhatsApp yourself. Return and confirm **Ya lo envié**. Opening a chat does not prove sending. If canceled, keep the pending handoff, or reset/release through the website after reviewing. No accessibility automation, no simulated taps, no WhatsApp Business API.

## Reports and recovery
Device assignment, sending state and RSVP are independent. The backend rejects competing claims and conflicting updates. Cached families are visible after restart; new sends require backend connectivity. Local outcomes are durable and retried on refresh. Pending sync blocks resending.

If an SMS is uncertain, inspect your messaging app. Confirm manually if it was sent. Otherwise reconcile on the website, release assignment if needed, and use **Preparar reenvío** with a reason. The app then requires accepting the reset before transmitting again. Do not reset while the old phone is still sending. Revoking a token stops API access, not a transmission already submitted to Android.

Signing out requires the outbox to be synced. If an attempt was replaced administratively, compare the phone messages with the web report, then use **Reconciliar cambios antiguos**. This archives only results whose attempt no longer matches the server and preserves local history. Accepting a web reset is a separate explicit action; neither reconciliation nor reset acceptance sends a message.

## Security and verification
The app never receives AWS credentials or the administrator key. User sessions and legacy device credentials are revocable. HTTPS is required and redirects are rejected. API definitions: [docs/api.md](docs/api.md). Unit tests cover assignment gating, no automatic resend, restart recovery and result ordering. Real SIM sending, permission grant behavior, WhatsApp handoff and OEM lifecycle behavior must be tested on a physical device.


## Sign-in recovery and rollout
Deploy the backend/web PIN support before installing app version 1.1.0 (version code 2). Existing installed device credentials continue to work; new sign-ins use username and PIN.

After logout, reinstall, expiry, or a locked PIN, the administrator selects **Generate new PIN** beside the same username. This invalidates the previous PIN/session and keeps family assignments. One active session per user is supported. Creating a different username creates a different identity. Legacy device assignments can be released in the web report before claiming them under a new username.

When a session is revoked/replaced, the app returns to sign-in and preserves pending sending results. Sign in with the same username/server to recover them; switching identities is blocked while results are pending. A successful PIN exchange is saved before refreshing families so a network failure during refresh does not discard the new login. Logout revokes the server session and requires synchronized results. A reinstall loses local unsynchronized results: reconcile uncertain attempts in the web report before sending again.

The PIN may have been consumed if a network failure hides a successful sign-in response. Generate another PIN in that case. Never share the administrator key with app users.
