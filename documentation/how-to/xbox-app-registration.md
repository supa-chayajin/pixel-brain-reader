# How to register the Microsoft app the Xbox sign-in uses

Problem: the Games tab reads your Xbox games, play time and achievements (13.3) through an app
that you register with Microsoft once, in your own name. Without it, the Xbox row stays hidden and
everything else works as before.

How it works:

- **Your own registration, no secret.** You register a "public client" in Microsoft Entra: it has a
  client id (a public value) and no password. The id goes into `local.properties`, never into this
  repository, like the GitHub login's.
- **A device code.** In the app, Connect shows a short code; you enter it at
  `microsoft.com/link`, on any device, and sign in with the account your Xbox uses. Nothing to
  type into the app, the way the GitHub login works.
- **Straight from the phone to Microsoft.** The tokens stay on the phone, in encrypted storage; no
  other server sees them. The app only reads: your games, minutes played and achievements. Every
  Xbox call shows in Settings › Integrations › Privacy ledger.

Checked against Microsoft's pages and apps registered this way in 2026 (November 2026); the
portal's labels move from time to time, so look for the same words nearby.

## Prerequisites

- The Microsoft account your Xbox One X uses: an adult account with an Xbox profile. A child
  account (in a family group) can't sign in to an app like this one.
- A computer with a browser, and about ten minutes.
- If your account has no Entra directory yet (a personal account usually has none): a phone number
  and a payment card for Microsoft's identity check. Nothing is charged as long as you don't move
  to a paid plan.

## 1. Get a directory

A personal Microsoft account has no Entra directory of its own, and the portals can't open
without one: signing in to entra.microsoft.com or portal.azure.com first fails with `AADSTS50020`
("… does not exist in tenant 'Microsoft Services' and cannot access the application
'c44b4083-…'", the Azure portal's id). The free Azure sign-up is what creates the directory.

1. Open a private window (InPrivate or Incognito), so that no work or school account signed in in
   the browser gets picked instead.
2. Go to <https://azure.microsoft.com/free>, choose **Try Azure for free** (or **Start free**), and
   sign in with the account your Xbox uses; if Microsoft asks which kind, choose the personal
   account.
3. Finish the sign-up: the phone check, the card check (the free account charges nothing) and the
   agreement. It creates your directory, with you as its administrator.
4. In the same window, open <https://portal.azure.com>: it now opens, in your directory. Set up
   multi-factor authentication when it asks.

The directory stays after the free account's trial ends: an app registration lives in the
directory, not in a subscription.

## 2. Register the app

1. In the portal, search for **App registrations** (or open <https://entra.microsoft.com> ›
   Applications › App registrations), then **New registration**.
2. **Name:** `Pixel Brain Reader` (any name; Microsoft shows it when you sign in).
3. **Supported account types:** **Personal Microsoft accounts only.**
4. **Redirect URI:** leave it empty.
5. **Register.**

## 3. Allow the device code

1. In the new app, open **Authentication**.
2. Under **Advanced settings**, set **Allow public client flows** to **Yes**.
3. **Save.**

Leave everything else as it is: no client secret (**Certificates & secrets** stays empty), no
platform, no API permission. The app asks for Xbox sign-in (`XboxLive.signin`) when you connect,
and you approve it then.

## 4. Copy the client id into the build

1. On the app's **Overview**, copy **Application (client) ID**: 36 characters, like
   `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`.
2. Add it to `local.properties`, at the repository's root:

   ```properties
   xboxClientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   ```

   No quotes, no space after it: `java.util.Properties` keeps a trailing space, and a wrong id
   only fails at the sign-in. The build warns when the value isn't a lowercase 36-character id;
   it gives the length, never the value.
3. Build the signed APK again in Android Studio: the id is baked in at build time.

## 5. Connect in the app

1. **Settings › Integrations › Xbox › Connect.** The page shows a code.
2. Open `microsoft.com/link` on any device, enter the code, and sign in with the account your Xbox
   uses.
3. Microsoft asks whether Pixel Brain Reader may sign in to Xbox and keep access: accept. It may
   call the app "unverified": publisher verification is for business accounts, and a personal
   registration shows that.
4. Back in the app, the page turns to **Connected** and runs the first sync: a baseline of your
   games and minutes, an `## 🎖️ Achievements` section on each game's page, and a page for each
   played game that has none (13.3's XB4).

Only enter a code the app showed you a moment ago: a code typed at `microsoft.com/link` signs in
whoever asked for it.

## Stop it

- **On the phone:** the Xbox page's **Disconnect** forgets the tokens.
- **For the account:** <https://account.microsoft.com> › Privacy › the apps that can access your
  data › Pixel Brain Reader › remove its permissions.
- **For good:** delete the registration in **App registrations**. A build with an empty
  `xboxClientId` hides the Xbox row again.

## When it goes wrong

| What you see | Why | What to do |
|---|---|---|
| `AADSTS50020`: the account "does not exist in tenant 'Microsoft Services'" | The account has no directory yet, so the portal can't open | Step 1: the free Azure sign-up first, in a private window |
| `AADSTS7000218` (asks for a `client_secret`) | Public client flows are off, so Microsoft takes the app for one with a secret | Step 3: **Allow public client flows: Yes**, then Save |
| `unauthorized_client`: not enabled for consumers | The registration isn't for personal accounts | Register a new app with **Personal Microsoft accounts only** (step 2) |
| `AADSTS700016` (application not found) | A typo in `xboxClientId`, or the registration was deleted | Copy the **Application (client) ID** again, then build again |
| The build warns about `xboxClientId` | A space, quotes, upper case or a cut value | Paste the id again, exactly |
| The code expired | A code lasts about 15 minutes | **Connect** again |
| Xbox error `2148916233` | The account has no Xbox profile yet | Sign in once at xbox.com or on the console |
| Xbox error `2148916238` | A child account | Use the adult account (or change it in the family settings) |
| Xbox error `2148916235` | The Xbox network isn't offered in the account's country | Nothing the app can change |
