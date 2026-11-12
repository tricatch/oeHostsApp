# oeHubApp

> **CLAUDE.md update rule:** Record development rules and procedures only; user-facing description and usage belong in `README.md`. All text in this file is written in English.

oeHubApp is a standalone Java Swing (FlatLaf) desktop port of oeHub's oeHosts / oeProxy / traffic-monitor screens. The sibling checkout `../oeHub` is the reference implementation; `../oeProxy` is the embedded reverse-proxy library.

## Build and Run

- `./gradlew test` (unit tests), `./gradlew shadowJar` (→ `build/libs/oehubapp.jar`), `./gradlew run` (dev run).
- JDK 25 toolchain, compiled with `--release 21` (oe-proxy uses virtual threads, so 21 is the floor). Do not raise the release level without a reason.
- `settings.gradle.kts` uses `includeBuild("../oeProxy")` when that folder exists, otherwise Maven Central's `io.github.tricatch:oe-proxy`.

## Launch Options

- App-specific JVM system properties are named `-Doehubapp.<name>` and read with `System.getProperty("oehubapp.<name>")`. Existing: `oehubapp.log` (log level), `oehubapp.shot` and `oehubapp.settings.page` (screenshot aid, below), `oehubapp.update.repo` and `oehubapp.agent.repo` (base URLs the app update and the oeHosts-Agent update use instead of the GitHub repositories; for testing against a local server).
- The only command-line argument is `--data DIR`. Document any new user-facing option in `README.md`.

## Verifying UI Changes

- Verify a UI change by running the built jar in screenshot mode and looking at the images, not only by compiling:
  `java -Duser.language=ko -Doehubapp.shot=<prefix> -jar build/libs/oehubapp.jar --data <scratch dir>`
  It saves each tab as `<prefix>-<n>.png` and each dialog listed in `MainWindow.screenshots()` as `<prefix>-d<n>.png`, then exits. Add a dialog there when you need to check it.
- `-Doehubapp.settings.page=<id>` opens that settings page first (ids are the `Page` ids in `SettingsTab`).
- Always use a scratch `--data` folder, never the user's real data folder. Check both `ko` and `en` when strings change.
- The user often has their own instance running: it holds ports 443 and 36980 (the reverse and forward proxies then report "failed to start" in the test instance, which is expected) and its window may be on screen. Do not drive real mouse/keyboard input (e.g. `java.awt.Robot`) unless the test window is known to be the target; prefer screenshot mode.
- Small changes do not need the full test suite; run the tests that cover the touched code.

## Platform Constraints

- Ship only a jar run by a signed `java`. Do not wrap it in an unsigned `.exe`/`.app` and do not build native helpers: Windows Smart App Control and macOS Gatekeeper block unsigned binaries. For the same reason FlatLaf's native library stays disabled (`flatlaf.useNativeLibrary=false`); do not add dependencies that extract native DLLs.
- The forward proxy (`ForwardProxy`, LittleProxy) is a trimmed port of oeHub's `tricatch.oe.fwdproxy.ForwardProxyServer`: it has no account authentication, whitelist, loopback/DNS-rebinding guards, blocked page or self-loop owner registration (no `LittleProxyInternals` reflection). It binds to 127.0.0.1 by default; binding every interface (`settings.fwdproxyLan`) is the user's explicit choice and makes it an open proxy on that network, so keep it off by default and keep the warning next to the option. Netty must stay pure Java here: do not add `netty-transport-native-*` or `netty-tcnative` artifacts (native libraries).
- The reverse proxy's binding (`settings.reverseProxyLan`, every interface by default) is applied through `ReverseProxyServer.setBindAddress` (oe-proxy 0.1.1 and later).
- The oeHosts share server (`ShareServer`, the JDK's `com.sun.net.httpserver`) binds to 127.0.0.1 only, serves only profiles whose `Item.shared` is on, only under a token signed with `Data.shareKey` (`Store.shareToken`: the id and a 40-bit HMAC, both in Crockford base32, then a `/name` slug the server ignores; every failure is the same 404, and the key stays out of backups), and refuses a non-loopback Host header (DNS rebinding). Keep all of these; do not add a LAN option without the user's decision. It is read-only (GET/HEAD).
- No network port is used for single-instance detection; it is a file lock in the data folder (`SingleInstance`). Keep it that way.

## Matching oeHub

- The screens keep oeHub's look and behavior. Colors, sizes and wording come from oeHub's CSS and messages; when the oeHub counterpart changes, update the matching class here:
  - `ChromeArgs` ↔ `static/js/chrome-launch-args.js` and `util.js` (`resolveUDD`, `winArgQuote`, `macArgQuote`). Deliberate difference: the start URL is passed as it is, not through `wrapDelayedUrl` (the delay only served oeHub's oeOID browser extension). This app does not use oeOID at all: no identifier header/codec setup, no extension hints in messages.
  - `HostsRules` ↔ `util.js` `parseHostsToMap` and the hosts page's `mergeSelectedProfiles`
  - `ui.HostsTab` ↔ `templates/oehub/hosts.pebble` (same `hostsConf` keys)
  - `ui.ProxyTab` ↔ `templates/oehub/proxy.pebble` (`validateSchema`, `buildMergedVirtualYaml`)
  - `ui.MonitorTab` ↔ `templates/oehub/proxy-monitor.pebble`
  - `ui.SidebarTree` ↔ `static/js/list-folders.js`
  - `ui.Ui`, `ui.OeDialog` ↔ `static/css/app.css`, `oe-panel.css`, `hosts.css`
- Settings → Data exports the checked parts (oeHosts, oeProxy, settings, CA) to one ZIP (`Backup`, `Store.exportParts`); a part is present in `data.json` only when it was exported, and an import touches only the parts that are both checked and present, either adding to the current data (`Store.mergeParts`, the default) or overwriting it (`Store.restoreParts`); the CA is always overwritten.
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
- Item and folder ids are 14 Crockford base32 digits (`Store.newId`), used as they are in share URLs. Ids are never converted; data made with the older hex ids is not migrated.
- `${PROXY_SVR}` / `${LOCAL_SVR}` always resolve to `127.0.0.1` in this app.

## Dependencies — License Listing

- When a runtime library is added or bumped in `build.gradle.kts`, update the open-source list in `SettingsTab.LIBS` (README only points to it). Take the license from the library's POM/LICENSE, not from memory. The settings screen shows the resolved versions recorded in the jar at build time (`oehubapp-build.properties`).
- License texts ship in the jar under `META-INF/licenses/<library>/` (`thirdPartyLicenses` task): dependency jars' own `META-INF/*LICENSE*`/`*NOTICE*` are moved there instead of overwriting each other at the jar root. For a new library whose jar carries no license file, add its upstream LICENSE (and NOTICE, if any) under `src/main/licenses/<library>/`; check with `unzip -Z1 build/libs/oehubapp.jar | grep META-INF/licenses/`.

## Self-Update

- `Update` checks at every start: the latest version is the tag `<repo>/releases/latest` redirects to (no GitHub API). The result shows only on Settings → General → About. It downloads `releases/download/<tag>/oehubapp.jar` to `oehubapp.jar.new` next to the installed jar and rejects it unless the SHA-256 matches `SHA256SUMS.txt`, the manifest's `Implementation-Version` equals the tag, and the jar contains `UpdateHelper`. So every release must keep those asset names and a tag equal to `version`.
- `UpdateHelper` runs from the NEW jar with a command line built by the OLD version (`--apply PID TARGET -- COMMAND...`). That format is a contract between releases: never change it. It uses only the JDK, copies the jar to `.tmp` and moves it over the target (it cannot rename the `.new` jar it holds open on Windows), always starts the app again (the old version when the copy fails), and updates install.ps1's `DisplayVersion` with `reg.exe` only when the registered `InstallLocation` is the jar's folder.
- Only a `.jar` in a writable folder updates itself (`Update.canSelfUpdate`); IDE and Gradle runs only report the version. Do not add an unsigned helper executable or script (see Platform Constraints).
- Test an update end to end before releasing a change to either class: build an old and a new jar (bump `version` temporarily), serve the new one and its `SHA256SUMS.txt` from a local server that answers `/releases/latest` with a redirect, and drive it with `-Doehubapp.update.repo`. Run the relaunched app in screenshot mode (`-Doehubapp.shot`, inherited through the JVM options) so it exits by itself, then check `oehubapp-update.log` and the installed jar's version.

## Bundled oeHosts-Agent

- The jar carries `/oehosts-agent.jar`, built from the sibling `../oeHostsAgent` checkout (an included build; `bundleAgent` in `build.gradle.kts` picks `build/libs/oehosts-agent-<its version>.jar`). Without the checkout the jar has no bundled agent and the app relies on the download below; do not vendor the agent jar into this repository.
- `HostsAgent.start` runs at every start: it installs the bundled copy next to the installed app jar (data folder for IDE/Gradle runs) unless the agent there is the same version or newer, then checks `https://github.com/tricatch/oeHostsAgent` `releases/latest` in the background and installs that release's `oehosts-agent.jar` when newer (SHA-256 from its `SHA256SUMS.txt`, manifest version equal to the tag; `Update.latestVersion`/`Update.fetchAsset` do the HTTP part). `-Doehubapp.agent.repo=<url>` points the check at a test server.
- The file is always named `oehosts-agent.jar`: never put a version in it, because users paste the `-javaagent` path into IDEs and scripts and every update must keep that path valid.
- A JVM running with the agent can keep the file locked on Windows: the replacement is then skipped (state `IN_USE`, check `PENDING`) and retried at the next start. Never fail the app start over the agent.
- `install.sh` and `install.ps1` extract the same entry at install time and remove `oehosts-agent.jar` (and `oehubapp-update.log`) on uninstall; `install.ps1` only warns when the agent is locked.

## Windows Installer (`install.ps1`)

- Run as `irm …/install.ps1 | iex`; must work on Windows PowerShell 5.1 as well as PowerShell 7 (no `??`, ternary or other 7-only syntax). Errors `throw` instead of `exit`, which would close the user's window under `iex`.
- Installs per user, no admin: the jar in `%LOCALAPPDATA%\Programs\oeHubApp`, a Start menu shortcut that runs the found `javaw.exe` with the jar (no .exe or wrapper, see Platform Constraints), and an `HKCU\…\Uninstall\oeHubApp` entry whose `UninstallString` runs the copy of the script kept in the install folder with `-Uninstall`. Uninstall keeps the data folder (`%APPDATA%\oeHubApp`).
- The shortcut and Settings > Apps icon is `packaging/windows/oehubapp.ico`, fetched from master: sizes below 256 stored as 32-bit BMP, 256 as PNG (Windows decodes small PNG entries wrongly). Regenerate it from the same logo drawing as the macOS/Linux icons when the logo changes.
- Test changes against a scratch `OEHUBAPP_HOME`, install then uninstall through the registered `UninstallString`, and check the shortcut and registry key are gone afterwards.

## Release

- Tag = bare version (`0.1.0`, no `v`), the same as `version` in `build.gradle.kts`. Release assets: `oehubapp.jar` and `SHA256SUMS.txt` (`sha256sum` format, two spaces, no `*`). Keep both names exactly: `install.sh` downloads `releases/latest/download/oehubapp.jar` and verifies it against `SHA256SUMS.txt`. Release notes in Korean.
- After publishing, download the jar back from the release and check its sha256, then run `install.sh` once with `OEHUBAPP_HOME`/`OEHUBAPP_BIN` pointing at a scratch folder, and `install.ps1` likewise on Windows (install, then uninstall).
- `install.sh` also makes `oeHubApp.app` on macOS (in `/Applications` when writable without sudo, else `~/Applications`; uninstall and reinstall remove only bundles carrying its bundle id) (a script bundle that runs the launcher with `--foreground`) and a `.desktop` entry on Linux, with icons fetched from `packaging/` on master. Those icons are the Dock icon drawing of `OeHubApp.dockIcon`; regenerate them if the logo changes.
- `install.sh` must stay POSIX `sh` (macOS runs it with `/bin/sh`), must not need root, and keeps the user's data folder on `--uninstall`.

## Documentation Language

- Source code comments are written in English.
- `README.md` and other documentation are written in Korean, and describe the app for its users (what it does, how to install, use and troubleshoot it). Development rules go here instead.
