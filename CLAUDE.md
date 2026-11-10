# oeHubApp

> **CLAUDE.md update rule:** Record development rules and procedures only; user-facing description and usage belong in `README.md`. All text in this file is written in English.

oeHubApp is a standalone Java Swing (FlatLaf) desktop port of oeHub's oeHosts / oeProxy / traffic-monitor screens. The sibling checkout `../oeHub` is the reference implementation; `../oeProxy` is the embedded reverse-proxy library.

## Build and Run

- `./gradlew test` (unit tests), `./gradlew shadowJar` (→ `build/libs/oehubapp.jar`), `./gradlew run` (dev run).
- JDK 25 toolchain, compiled with `--release 21` (oe-proxy uses virtual threads, so 21 is the floor). Do not raise the release level without a reason.
- `settings.gradle.kts` uses `includeBuild("../oeProxy")` when that folder exists, otherwise Maven Central's `io.github.tricatch:oe-proxy`.

## Launch Options

- App-specific JVM system properties are named `-Doehubapp.<name>` and read with `System.getProperty("oehubapp.<name>")`. Existing: `oehubapp.log` (log level), `oehubapp.shot` and `oehubapp.settings.page` (screenshot aid, below).
- The only command-line argument is `--data DIR`. Document any new user-facing option in `README.md`.

## Verifying UI Changes

- Verify a UI change by running the built jar in screenshot mode and looking at the images, not only by compiling:
  `java -Duser.language=ko -Doehubapp.shot=<prefix> -jar build/libs/oehubapp.jar --data <scratch dir>`
  It saves each tab as `<prefix>-<n>.png` and each dialog listed in `MainWindow.screenshots()` as `<prefix>-d<n>.png`, then exits. Add a dialog there when you need to check it.
- `-Doehubapp.settings.page=<id>` opens that settings page first (ids are the `Page` ids in `SettingsTab`).
- Always use a scratch `--data` folder, never the user's real data folder. Check both `ko` and `en` when strings change.
- The user often has their own instance running: it holds port 443 (the proxy then reports "failed to start" in the test instance, which is expected) and its window may be on screen. Do not drive real mouse/keyboard input (e.g. `java.awt.Robot`) unless the test window is known to be the target; prefer screenshot mode.
- Small changes do not need the full test suite; run the tests that cover the touched code.

## Platform Constraints

- Ship only a jar run by a signed `java`. Do not wrap it in an unsigned `.exe`/`.app` and do not build native helpers: Windows Smart App Control and macOS Gatekeeper block unsigned binaries. For the same reason FlatLaf's native library stays disabled (`flatlaf.useNativeLibrary=false`); do not add dependencies that extract native DLLs.
- No network port is used for single-instance detection; it is a file lock in the data folder (`SingleInstance`). Keep it that way.

## Matching oeHub

- The screens keep oeHub's look and behavior. Colors, sizes and wording come from oeHub's CSS and messages; when the oeHub counterpart changes, update the matching class here:
  - `ChromeArgs` ↔ `static/js/chrome-launch-args.js` and `util.js` (`resolveUDD`, `wrapDelayedUrl`, `winArgQuote`, `macArgQuote`)
  - `HostsRules` ↔ `util.js` `parseHostsToMap` and the hosts page's `mergeSelectedProfiles`
  - `ui.HostsTab` ↔ `templates/oehub/hosts.pebble` (same `hostsConf` keys)
  - `ui.ProxyTab` ↔ `templates/oehub/proxy.pebble` (`validateSchema`, `buildMergedVirtualYaml`)
  - `ui.MonitorTab` ↔ `templates/oehub/proxy-monitor.pebble`
  - `ui.SidebarTree` ↔ `static/js/list-folders.js`
  - `ui.Ui`, `ui.OeDialog` ↔ `static/css/app.css`, `oe-panel.css`, `hosts.css`
- Data exported from Settings → Data stays in oeHub's export format so the two apps can exchange it.
- Brand colors are oeHub's (`Ui.BC100`…`Ui.BC700`); do not introduce other greens. Buttons have a 6px radius.
- Navbar: tabs on the left (`MainWindow.TABS`, shown uppercase), gear on the right, and the current tab's actions in the middle of the whole bar (a `CardLayout`, one card per tab). Middle-bar buttons use `Ui.navIconButton` (rounded frame); panel-header buttons use `Ui.iconButton`.

## i18n — UI Messages

- Never hardcode UI strings; use `ctx.m("key", args...)` (`{0}`, `{1}` placeholders) or `ctx.plain(...)` for messages that carry HTML.
- `i18n/messages_{en,ko}.properties` are copied from oeHub and trimmed to the keys this app uses. When bringing over more oeHub keys, add only the ones referenced.
- App-specific strings go in `i18n/oehubapp_{en,ko}.properties` (prefix `app.`), which override `messages_*` keys of the same name. Do not keep a `messages_*` key that an `oehubapp_*` key shadows.
- Add every key to both `en` and `ko`. When a change stops using a key, delete it from both languages in the same change.
- Tab names (OEHOSTS, OEPROXY, NETWORK) are product names and are not translated.
- UI wording is the user's call: when asked to change a label, propose options first instead of applying one directly.

## Icons

- Lucide SVGs live in `src/main/resources/icon/lucide/` (copied from oeHub) and are referenced by file name through `Ui.icon(name, size, color)`, which recolors them. To add one, copy the SVG from oeHub (or lucide.dev) into that folder.
- Full-color images (Chrome, logo) are in `icon/` and loaded with `Ui.image`.

## Files and Data

- All user data is one JSON file (`Store`), saved with a `.bak` of the previous version. A single hosts profile / vhost is saved and imported as `.oehosts` / `.oeproxy` (`ui.ItemFiles`), the editor text as UTF-8.
- An invalid vhost (fails `ProxyTab.problems`) is never stored: not on edit, not on import.
- `${PROXY_SVR}` / `${LOCAL_SVR}` always resolve to `127.0.0.1` in this app.

## Dependencies — License Listing

- When a runtime library is added or bumped in `build.gradle.kts`, update both the open-source list in `SettingsTab.LIBS` and the table in `README.md`. The settings screen shows the resolved versions recorded in the jar at build time (`oehubapp-build.properties`).

## Documentation Language

- Source code comments are written in English.
- `README.md` and other documentation are written in Korean, and describe the app for its users (what it does, how to install, use and troubleshoot it). Development rules go here instead.
