# oeHostsApp

> **CLAUDE.md update rule:** Record development rules and procedures only; user-facing description and usage belong in `README.md`. All text in this file is written in English.

oeHostsApp is a standalone Java Swing (FlatLaf) desktop port of oeHub's oeHosts / oeProxy / traffic-monitor screens. The sibling checkout `../oeHub` is the reference implementation; `../oeProxy` is the embedded reverse-proxy library.

## Build and Run

- `./gradlew test` (unit tests), `./gradlew shadowJar` (→ `build/libs/oehostsapp.jar`), `./gradlew run` (dev run).
- JDK 25 toolchain, compiled with `--release 21` (oe-proxy uses virtual threads, so 21 is the floor). Do not raise the release level without a reason.
- `settings.gradle.kts` uses `includeBuild("../oeProxy")` when that folder exists, otherwise Maven Central's `io.github.tricatch:oe-proxy`.

## Packages

- Code under `tricatch.oehostsapp` is split by feature: `store` (data file and its model, backup), `proxy` (forward/reverse proxy runtime, root CA, vhost YAML, traffic monitor), `web` (the pages the proxy service answers itself: shared hosts, `/active-hosts`, phone setup page), `net` (this PC's addresses, binding values), `hosts` (hosts rules, oeHosts-Agent), `chrome` (Chrome launch), `update` (self-update), `ui` (Swing screens). The root package keeps only app-wide classes (entry point, data folder, build info, messages, single instance, `Listeners`).
- Put a new class in the package of its feature, not in the root. Make a member `public` only when another package needs it.
- `UpdateHelper` stays in the root package: its fully qualified name is a contract between releases (an older version looks it up by name in the new jar). Never rename or move it, and never move `OeHostsApp` (the `Main-Class`).
- Keep rules about data out of `ui`: parsing, merging and validation (hosts rules, vhost YAML, presets' storage) live in the feature package and get unit tests; a screen only formats their results. One merge per kind: every consumer of the selected hosts profiles goes through `HostsRules.merge`/`active`, every consumer of the selected vhosts through `Vhosts.merge`/`served`. Do not store data that can be derived from the store (the proxy reads the selected vhosts itself).

## Screens

- The app-wide services (`ProxyRuntime`, `ForwardProxy`, `ClientAccess`, `Update`, `HostsAgent`) announce changes through `Listeners`; a screen subscribes only with `AppContext.listen(Service::onChange, onEdt)` (and `onSettingsChanged`), never by calling `onChange` itself. `MainWindow.relaunch` disposes the old `AppContext`, which drops those subscriptions, so a rebuilt window leaves nothing behind.
- The oeHosts and oeProxy screens share `ItemPanel` (list, editor, autosave, add/import/delete/export); put behavior both need there and the rest in the screen's `ItemPanel.Hooks`.
- Each settings page is its own class (`*Page`, built on first show and registered in `SettingsTab`); shared layout pieces are in `SettingsKit`, the server state table in `ServerStatus`, the PC address drop-down in `NetworkChoice`.

## Launch Options

- App-specific JVM system properties are named `-Doehostsapp.<name>` and read with `System.getProperty("oehostsapp.<name>")`. Existing: `oehostsapp.log` (log level), `oehostsapp.shot` and `oehostsapp.settings.page` (screenshot aid, below), `oehostsapp.update.repo` and `oehostsapp.agent.repo` (base URLs the app update and the oeHosts-Agent update use instead of the GitHub repositories; for testing against a local server).
- The only command-line argument is `--data DIR`. Document any new user-facing option in `README.md`.

## Verifying UI Changes

- Verify a UI change by running the built jar in screenshot mode and looking at the images, not only by compiling:
  `java -Duser.language=ko -Doehostsapp.shot=<prefix> -jar build/libs/oehostsapp.jar --data <scratch dir>`
  It saves each tab as `<prefix>-<n>.png` and each dialog listed in `MainWindow.screenshots()` as `<prefix>-d<n>.png`, then exits. Add a dialog there when you need to check it.
- `-Doehostsapp.settings.page=<id>` opens that settings page first (ids are the `Page` ids in `SettingsTab`).
- Always use a scratch `--data` folder, never the user's real data folder. Check both `ko` and `en` when strings change.
- The user often has their own instance running: it holds ports 443 and 36980 (the reverse and forward proxies then report "failed to start" in the test instance, which is expected) and its window may be on screen. Do not drive real mouse/keyboard input (e.g. `java.awt.Robot`) unless the test window is known to be the target; prefer screenshot mode.
- Small changes do not need the full test suite; run the tests that cover the touched code.

## Platform Constraints

- Ship only a jar run by a signed `java`. Do not wrap it in an unsigned `.exe`/`.app` and do not build native helpers: Windows Smart App Control and macOS Gatekeeper block unsigned binaries. For the same reason FlatLaf's native library stays disabled (`flatlaf.useNativeLibrary=false`); do not add dependencies that extract native DLLs.
- The proxy service (`ForwardProxy`, LittleProxy; "프록시 서비스" in the UI) is a trimmed port of oeHub's `tricatch.oe.fwdproxy.ForwardProxyServer`: no account authentication, whitelist, blocked page or self-loop owner registration (no `LittleProxyInternals` reflection). It has one listener, on every interface (`0.0.0.0:<settings.fwdproxy.port>`); there is no binding choice and no proxy authentication (the earlier `fwdproxy.bind`/`auth`/`user`/`password` and the separate web service with `settings.web` were removed; old data files keep those keys and they are ignored). The Chrome launcher's `--proxy-server` is `127.0.0.1:<port>`.
- Who may use the proxy service is decided per device by `ClientAccess`, not by a binding or a password: a client on this PC (loopback or one of this PC's own interface addresses) is always let in; any other address is asked about once in a dialog showing its IP and User-Agent (`MainWindow.askAccess`, Allow / Deny, closing denies), and the answer is saved in the data file (`settings.fwdproxy.devices`, loaded by `ClientAccess.attach` when the service starts) and holds until it is removed on the proxy service page (one device, or the whole list after a confirm). Requests before the answer and after a refusal get a 403 with a short text from the filter (`app.access.page.*`). Keep this gate on every request the service handles, proxied or answered itself; do not add per-feature gates next to it.
- Short-circuit responses come from `clientToProxyRequest` in the shared `HttpFiltersAdapter`. Do not answer 407 from there: LittleProxy 2.9.1 then throws a NullPointerException (it looks for an upstream connection) and strips the `Proxy-Authenticate` header as hop-by-hop. Re-check short-circuit responses when the `littleproxy` version changes. Netty must stay pure Java here: do not add `netty-transport-native-*` or `netty-tcnative` artifacts (native libraries).
- The reverse proxy's binding (`settings.reverseProxy.bind`: 0.0.0.0 by default, 127.0.0.1, or one IPv4 address) is applied through `ReverseProxyServer.setBindAddresses` (oe-proxy 0.3.0 and later). A network card's address is always bound together with 127.0.0.1, because the hosts profiles' `127.0.0.1 domain` lines reach the proxy there. A release of this app that uses it needs oe-proxy 0.3.0 on Maven Central first (the `includeBuild` of `../oeProxy` only covers local builds).
- The proxy service also answers requests addressed to itself (`ForwardProxy.target`): a path only (`GET /active-hosts`, how a hosts manager or oeHosts-Agent calls a URL), or an absolute URL naming this PC (loopback or an own address) and this port (`GET http://<PC IP>:<port>/ssl`, a phone opening the QR code through this same proxy). A CONNECT to itself is refused (403), never looped. `ShareServer.handle` builds those answers: the shared oeHosts profiles under `/hosts/`, the selected profiles merged for oeHosts-Agent at `ShareServer.ACTIVE_PATH` (`/active-hosts`: `IP host` lines in tree order, first profile wins, through `HostsRules.active` like the proxy and the Chrome launcher, not limited to shared profiles; the Java agent page's option always points here), and the phone setup page with the CA's public certificate (`PhoneSetup`, `PhoneSetup.PAGE_PATH` (`/ssl`) and `PhoneSetup.CA_PATH`). Every URL the app shows uses the proxy service port. Shared profiles are served only when `Item.shared` is on, only under a token signed with `Data.shareKey` (`Store.shareToken`: the id and a 40-bit HMAC, both in Crockford base32, then a `/name` slug the server ignores; every failure is the same 404, and the key stays out of backups). A Host header that is neither a loopback name nor one of this PC's own IP addresses is refused (DNS rebinding). It is read-only (GET/HEAD). Never serve the private key (`ca.pfx`) or other data files there.
- Do not put `127.0.0.1` or `localhost` in the QR URL: phone browsers send loopback addresses to the phone itself, never to the proxy.
- The phone section at the bottom of Settings → oeProxy → CA 인증서 (모바일 기기 인증서 설치, `CaPage.mobileSection`) only downloads the CA; it configures nothing. It needs the proxy service running; the phone is asked about (`ClientAccess`) on its first request.
- No network port is used for single-instance detection; it is a file lock in the data folder (`SingleInstance`). Keep it that way.

## Matching oeHub

- The screens keep oeHub's look and behavior. Colors, sizes and wording come from oeHub's CSS and messages; when the oeHub counterpart changes, update the matching class here:
  - `ChromeArgs` ↔ `static/js/chrome-launch-args.js` and `util.js` (`resolveUDD`, `winArgQuote`, `macArgQuote`). Deliberate difference: the start URL is passed as it is, not through `wrapDelayedUrl` (the delay only served oeHub's oeOID browser extension). This app does not use oeOID at all: no identifier header/codec setup, no extension hints in messages.
  - `HostsRules` ↔ `util.js` `parseHostsToMap` and the hosts page's `mergeSelectedProfiles`
  - `ui.HostsTab` ↔ `templates/oehub/hosts.pebble` (the options of `store.LauncherOptions`)
  - `proxy.Vhosts` ↔ `templates/oehub/proxy.pebble` (`validateSchema`, `buildMergedVirtualYaml`); `ui.ProxyTab` ↔ the rest of that page
  - `ui.MonitorTab` ↔ `templates/oehub/proxy-monitor.pebble`
  - `ui.SidebarTree` ↔ `static/js/list-folders.js`
  - `ui.Ui`, `ui.OeDialog` ↔ `static/css/app.css`, `oe-panel.css`, `hosts.css`
- Settings → Data exports the checked parts (oeHosts, oeProxy, settings, CA) to one ZIP (`Backup`, `Store.exportParts`); a part is present in `data.json` only when it was exported, and an import touches only the parts that are both checked and present, either adding to the current data (`Store.mergeParts`, the default) or overwriting it (`Store.restoreParts`); the CA is always overwritten.
- Brand colors are oeHub's (`Ui.BC100`…`Ui.BC700`); do not introduce other greens. Buttons have a 6px radius.
- Navbar: tabs on the left (`MainWindow.TABS`, shown uppercase), gear on the right, and the current tab's actions in the middle of the whole bar (a `CardLayout`, one card per tab). Middle-bar buttons use `Ui.navIconButton` (rounded frame); panel-header buttons use `Ui.iconButton`.

## Settings Page Layout

- A settings page that both changes something and shows something is split into two equal halves (`SettingsKit.fullPage(halves(...))`): the left half holds what saves or changes (description, then the inputs, then the action buttons, the three parts set apart by `line()`; buttons right-aligned), the right half holds what only shows or looks up (state, addresses, file locations). When the right half has buttons too, it is laid out the same way: description, `line()`, the information, `line()`, buttons right-aligned (`rightAligned`). The vertical divider runs down to the page's bottom padding.
- Inside a form, labels share one width (`sameWidth`) and notes or warnings that belong to a field start where the field starts, not under the label.
- A status table shows its error row only while there is an error (`errorRow`).

## i18n — UI Messages

- Never hardcode UI strings; use `ctx.m("key", args...)` (`{0}`, `{1}` placeholders) or `ctx.plain(...)` for messages that carry HTML.
- `i18n/messages_{en,ko}.properties` are copied from oeHub and trimmed to the keys this app uses. When bringing over more oeHub keys, add only the ones referenced.
- App-specific strings go in `i18n/oehostsapp_{en,ko}.properties` (prefix `app.`), which override `messages_*` keys of the same name. Do not keep a `messages_*` key that an `oehostsapp_*` key shadows.
- Add every key to both `en` and `ko`. When a change stops using a key, delete it from both languages in the same change.
- Tab names (OEHOSTS, OEPROXY, NETWORK) are product names and are not translated.
- UI wording is the user's call: when asked to change a label, propose options first instead of applying one directly.

## Icons

- Lucide SVGs live in `src/main/resources/icon/lucide/` (copied from oeHub) and are referenced by file name through `Ui.icon(name, size, color)`, which recolors them. To add one, copy the SVG from oeHub (or lucide.dev) into that folder.
- Full-color images (Chrome, logo) are in `icon/` and loaded with `Ui.image`.

## Files and Data

- All user data is one JSON file (`Store`), saved with a `.bak` of the previous version. A single hosts profile / vhost is saved and imported as `.oehosts` / `.oeproxy` (`ui.ItemFiles`), the editor text as UTF-8.
- An invalid vhost (fails `Vhosts.problems`) is never stored: not on edit, not on import.
- Item and folder ids are 14 Crockford base32 digits (`Store.newId`), used as they are in share URLs. Ids are never converted; data made with the older hex ids is not migrated.
- `${PROXY_SVR}` / `${LOCAL_SVR}` always resolve to `127.0.0.1` in this app.
- The data model is `Store.Data` with `store.Settings` (grouped: `fwdproxy`, `reverseProxy`, `userAgentPresets`/`openUrlPresets`, `window`) and `store.LauncherOptions`. A part missing from the file is filled in by `fillDefaults` on open, so code never checks for null parts.
- The data format carries no compatibility code with older versions (no migration of older files or old field names, no fields kept only for older versions); this was the owner's decision while the app has a single user. Ask before adding any.

## Dependencies — License Listing

- When a runtime library is added or bumped in `build.gradle.kts`, update the open-source list in `LicensesPage.LIBS` (README only points to it). Take the license from the library's POM/LICENSE, not from memory. The settings screen shows the resolved versions recorded in the jar at build time (`oehostsapp-build.properties`).
- License texts ship in the jar under `META-INF/licenses/<library>/` (`thirdPartyLicenses` task): dependency jars' own `META-INF/*LICENSE*`/`*NOTICE*` are moved there instead of overwriting each other at the jar root. For a new library whose jar carries no license file, add its upstream LICENSE (and NOTICE, if any) under `src/main/licenses/<library>/`; check with `unzip -Z1 build/libs/oehostsapp.jar | grep META-INF/licenses/`.

## Self-Update

- `Update` checks at every start: the latest version is the tag `<repo>/releases/latest` redirects to (no GitHub API). The result shows only on Settings → General → About. It downloads `releases/download/<tag>/oehostsapp.jar` to `oehostsapp.jar.new` next to the installed jar and rejects it unless the SHA-256 matches `SHA256SUMS.txt`, the manifest's `Implementation-Version` equals the tag, and the jar contains `UpdateHelper`. So every release must keep those asset names and a tag equal to `version`.
- `UpdateHelper` runs from the NEW jar with a command line built by the OLD version (`--apply PID TARGET -- COMMAND...`). That format is a contract between releases: never change it. It uses only the JDK, copies the jar to `.tmp` and moves it over the target (it cannot rename the `.new` jar it holds open on Windows), always starts the app again (the old version when the copy fails), and updates install.ps1's `DisplayVersion` with `reg.exe` only when the registered `InstallLocation` is the jar's folder.
- Only a `.jar` in a writable folder updates itself (`Update.canSelfUpdate`); IDE and Gradle runs only report the version. Do not add an unsigned helper executable or script (see Platform Constraints).
- Test an update end to end before releasing a change to either class: build an old and a new jar (bump `version` temporarily), serve the new one and its `SHA256SUMS.txt` from a local server that answers `/releases/latest` with a redirect, and drive it with `-Doehostsapp.update.repo`. Run the relaunched app in screenshot mode (`-Doehostsapp.shot`, inherited through the JVM options) so it exits by itself, then check `oehostsapp-update.log` and the installed jar's version.

## Bundled oeHosts-Agent

- The jar carries `/oehosts-agent.jar`, built from the sibling `../oeHostsAgent` checkout (an included build; `bundleAgent` in `build.gradle.kts` picks `build/libs/oehosts-agent-<its version>.jar`). Without the checkout the jar has no bundled agent and the app relies on the download below; do not vendor the agent jar into this repository.
- `HostsAgent.start` runs at every start: it installs the bundled copy next to the installed app jar (data folder for IDE/Gradle runs) unless the agent there is the same version or newer, then checks `https://github.com/tricatch/oeHostsAgent` `releases/latest` in the background and installs that release's `oehosts-agent.jar` when newer (SHA-256 from its `SHA256SUMS.txt`, manifest version equal to the tag; `Update.latestVersion`/`Update.fetchAsset` do the HTTP part). `-Doehostsapp.agent.repo=<url>` points the check at a test server.
- The file is always named `oehosts-agent.jar`: never put a version in it, because users paste the `-javaagent` path into IDEs and scripts and every update must keep that path valid.
- A JVM running with the agent can keep the file locked on Windows: the replacement is then skipped (state `IN_USE`, check `PENDING`) and retried at the next start. Never fail the app start over the agent.
- `install.sh` and `install.ps1` extract the same entry at install time and remove `oehosts-agent.jar` (and `oehostsapp-update.log`) on uninstall; `install.ps1` only warns when the agent is locked.

## Windows Installer (`install.ps1`)

- Run as `irm …/install.ps1 | iex`; must work on Windows PowerShell 5.1 as well as PowerShell 7 (no `??`, ternary or other 7-only syntax). Errors `throw` instead of `exit`, which would close the user's window under `iex`.
- Installs per user, no admin: the jar in `%LOCALAPPDATA%\Programs\oeHostsApp`, a Start menu shortcut that runs the found `javaw.exe` with the jar (no .exe or wrapper, see Platform Constraints), and an `HKCU\…\Uninstall\oeHostsApp` entry whose `UninstallString` runs the copy of the script kept in the install folder with `-Uninstall`. Uninstall keeps the data folder (`%APPDATA%\oeHostsApp`).
- The shortcut and Settings > Apps icon is `packaging/windows/oehostsapp.ico`, fetched from master: sizes below 256 stored as 32-bit BMP, 256 as PNG (Windows decodes small PNG entries wrongly). It is `Ui.appIcon(size, false)`: the round logo filling the icon, no frame. Regenerate it together with the macOS/Linux icons with `./gradlew icons` whenever that drawing or the logo changes.
- Test changes against a scratch `OEHOSTSAPP_HOME`, install then uninstall through the registered `UninstallString`, and check the shortcut and registry key are gone afterwards.
- That test replaces the developer's own Start menu shortcut and `HKCU\...\Uninstall\oeHostsApp` entry (they are per user, not per `OEHOSTSAPP_HOME`). Back both up first (`reg export`, copy the `.lnk`) and restore them in a `finally` after the uninstall. `reg import` prints its success message to stderr, so do not run it under `$ErrorActionPreference = 'Stop'` with `2>&1`, or the restore stops halfway.

## Release

- Tag = bare version (`0.1.0`, no `v`), the same as `version` in `build.gradle.kts`. Release assets: `oehostsapp.jar` and `SHA256SUMS.txt` (`sha256sum` format, two spaces, no `*`). Keep both names exactly: `install.sh` downloads `releases/latest/download/oehostsapp.jar` and verifies it against `SHA256SUMS.txt`. Release notes in Korean.
- After publishing, download the jar back from the release and check its sha256, then run `install.sh` once with `OEHOSTSAPP_HOME`/`OEHOSTSAPP_BIN` pointing at a scratch folder, and `install.ps1` likewise on Windows (install, then uninstall).
- `install.sh` also makes `oeHostsApp.app` on macOS (in `/Applications` when writable without sudo, else `~/Applications`; uninstall and reinstall remove only bundles carrying its bundle id) (a script bundle that runs the launcher with `--foreground`) and a `.desktop` entry on Linux, with icons fetched from `packaging/` on master. Those icons, the window icons and the Dock icon all come from `Ui.appIcon`: on macOS (`.icns`, the Dock icon, window icons) and in the Linux `.png` the logo sits on a white rounded square (`framed`), on Windows (`.ico`, window icons) it fills the icon unframed; regenerate the `packaging/` files with `./gradlew icons` (`src/tools`, `tricatch.oehostsapp.tools.AppIcons`; not in the app jar) whenever it or the logo changes, and commit them.
- `install.sh` must stay POSIX `sh` (macOS runs it with `/bin/sh`), must not need root, and keeps the user's data folder on `--uninstall`.

## Documentation Language

- Source code comments are written in English.
- `README.md` and other documentation are written in Korean, and describe the app for its users (what it does, how to install, use and troubleshoot it). Development rules go here instead.
