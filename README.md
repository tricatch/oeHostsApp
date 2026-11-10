# oeHubApp

oeHub의 **oeHosts / oeProxy(+ oeMonitor)** 화면과 설정 화면을 탭으로 묶은 단독 데스크톱 앱입니다.
Java Swing(FlatLaf)으로 그린 창 하나로 실행되며, 브라우저나 로컬 웹 서버가 필요 없습니다.
Java(JDK 21 이상)로 실행하는 jar 하나이며, Windows와 macOS에서 같은 파일을 씁니다.
Chrome은 앱이 직접 실행합니다. oeHub처럼 oeLink(`oelink://` URL 핸들러)를 따로 설치할 필요가 없습니다.

oeHub의 사용자 관리, E2E 암호화, 검색, 공유·공개 링크 기능은 넣지 않습니다.

| 탭 | 동작 |
|---|---|
| oeHosts | 실제 동작. hosts 프로필 편집·폴더·선택·병합, "Chrome 실행" 버튼으로 앱이 Chrome을 직접 실행 |
| oeProxy | 실제 동작. 앱에 내장된 [oe-proxy](https://github.com/tricatch/oeProxy)가 앱과 함께 https 443에서 실행되며, 선택한 vhost를 병합해 바로 반영 |
| oeMonitor | 실제 동작. 별도 탭 없이 oeProxy 화면의 모니터 버튼으로 엶(OEPROXY 탭을 누르면 돌아감). 내장 프록시를 지나가는 요청·응답(헤더, 쿠키, 본문, WebSocket 프레임)을 실시간으로 보여 줌. 녹화를 멈추면 프록시도 이벤트를 만들지 않음 |
| 설정 | 우측 상단 톱니바퀴. 왼쪽 목록에서 oeHosts(User-Agent·Open URL 프리셋, 포워드 프록시), oeProxy(CA 인증서, 리버스 프록시), 일반(언어, 데이터 내보내기·가져오기, 정보, 오픈소스) 항목을 골라 관리 |

## 실행

```
java -jar oehubapp.jar                    # 데스크톱 앱
javaw -jar oehubapp.jar                   # Windows에서 콘솔 창 없이
java -Doehubapp.log=debug -jar oehubapp.jar   # 콘솔 로그를 더 자세히
java -jar oehubapp.jar --data D:\oehub-data
```

- "Chrome 실행"은 앱이 Chrome을 직접 띄웁니다(Windows: Program Files·LocalAppData의 `chrome.exe`, macOS: `/Applications/Google Chrome.app`). 같은 프로필(`--user-data-dir` 값이 같거나, 둘 다 기본 프로필)로 이미 떠 있는 Chrome은 새 옵션을 받지 않으므로, 확인을 받은 뒤 정상 종료를 요청하고 5초 안에 닫히지 않으면 강제로 끝낸 다음 다시 실행합니다. 가져온 프로필의 extraArgs 등에 프로그램 실행·샌드박스 해제·디버깅 포트 같은 위험한 옵션이 있으면 아무것도 실행하지 않습니다.
- Win / Mac 버튼은 같은 실행을 터미널에 붙여 넣을 명령으로 보여 줍니다.
- 실행 파일(.exe/.app)로 감싸지 않습니다. 서명되지 않은 래퍼는 Smart App Control·Gatekeeper에 막히지만, 서명된 `java`로 jar를 실행하면 막히지 않습니다. 같은 이유로 FlatLaf의 네이티브 DLL은 쓰지 않습니다(`flatlaf.useNativeLibrary=false`).
- 데이터 폴더마다 하나만 실행됩니다. 이미 실행 중일 때 다시 실행하면 새 창을 띄우지 않고 실행 중인 창을 앞으로 가져온 뒤 종료합니다. 데이터 폴더의 `oehubapp.lock`을 OS 파일 잠금으로 잡고, 두 번째 실행은 같은 폴더에 `oehubapp.focus`를 남겨 알립니다(네트워크 포트는 쓰지 않음). 비정상 종료해도 OS가 잠금을 풀어 주므로 다음 실행이 막히지 않습니다.
- `java`로 실행하면 콘솔(stderr)에 로그가 나옵니다: 프록시 시작·실패, 불러온 라우트 목록, 라우트가 없는 요청(404, 그때의 라우트 목록 포함), 백엔드 연결 실패(502·504). 기본 수준은 `info`이고 `-Doehubapp.log=debug`로 바꿀 수 있습니다.
- macOS에서는 Dock 아이콘을 oe 로고로 바꾸고 메뉴 막대 이름을 oeHubApp으로 표시합니다. jar로 실행하므로 시작 직후 잠깐은 Java 아이콘이 보일 수 있습니다.
- 화면 언어는 시스템 언어를 따르며, 설정 → 일반 → 언어에서 바꿀 수 있습니다(데이터 파일에 저장).
- 각 화면의 왼쪽 목록은 경계선을 끌어 너비를 바꿀 수 있습니다. 기본 300px이고, 한 번 바꾼 너비는 데이터 파일에 저장되고 모든 탭(oeHosts·oeProxy·설정)이 같은 너비를 씁니다. oeHosts·oeProxy의 오른쪽 패널 너비(기본 548px)도 같은 방식으로 저장·공유됩니다.

## 데이터

| OS | 위치 |
|---|---|
| Windows | `%APPDATA%\oeHubApp\oehubapp.json` |
| macOS | `~/Library/Application Support/oeHubApp/oehubapp.json` |
| 그 밖 | `~/.config/oeHubApp/oehubapp.json` |

- 저장할 때마다 이전 내용은 `oehubapp.json.bak`으로 보관합니다. 읽을 수 없는 데이터 파일은 덮어쓰지 않고 실행을 멈춥니다.
- 첫 실행 때 이전 이름(oeHosts)의 데이터를 가져옵니다. `oeHosts/oehosts-app.json`이 있으면 그대로 복사하고, 없으면 egui 버전의 `oeHosts/oehosts.json`에서 프로필·폴더·옵션·프리셋을 옮겨 옵니다. 이전 파일은 **읽기만** 합니다.
- `--data`로 다른 폴더를 지정하면 이전 데이터를 가져오지 않고 빈 상태로 시작합니다.
- 설정 → 일반 → 데이터에서 oeHosts·oeProxy를 각각 JSON으로 내보내고 가져옵니다(병합 또는 전체 교체). 파일 형식이 oeHub의 내보내기와 같아서 oeHub와 서로 주고받을 수 있고, 암호화된(E2E) 항목은 건너뜁니다.
- 같은 화면의 "전체 (인증서 포함)"은 모든 프로필·vhost·옵션·설정과 CA 인증서·개인키를 ZIP 하나로 내보내고, 가져올 때는 전부 교체합니다. 백업 파일에 CA 개인키가 들어 있으므로 공유하지 않습니다.
- oeHub의 `${PROXY_SVR}`·`${LOCAL_SVR}`는 이 앱에서 쓰지 않습니다. 프록시·Chrome·백엔드가 모두 같은 PC에 있고, 휴대폰도 이 PC의 프록시를 거치므로 둘 다 항상 `127.0.0.1`입니다. 기존 데이터나 oeHub에서 가져온 내용에 남아 있으면 `127.0.0.1`로 바꿔 씁니다.

## oeProxy (내장 역방향 프록시)

- 앱이 시작될 때 같은 프로세스 안에서 [oe-proxy](https://github.com/tricatch/oeProxy)를 https 443 포트로 띄웁니다(oeHub와 같은 내장 방식). oeProxy 화면 아래에 실행 상태가 표시되고, 실패하면 마우스를 올려 이유를 볼 수 있습니다(예: 443 포트를 다른 프로그램이 사용 중).
- 계정이 없는 단일 소유자 모드라, 어디서 온 요청이든 식별 헤더가 있든 없든(oeHub 확장 프로그램의 `X-OeHub-Oid` 포함) 모든 요청에 선택한 vhost의 라우팅이 적용됩니다. vhost를 고치거나 선택을 바꾸면 다음 요청부터 반영됩니다.
- 첫 실행 때 데이터 폴더의 `root-ca/ca.cer`(인증서), `ca.pfx`(개인키, 암호 없음)로 자체 서명 CA를 만듭니다. `ca.cer`를 OS 신뢰 저장소에 한 번 등록해야 브라우저 경고가 사라집니다. 설정 → oeProxy → CA 인증서에서 등록 여부를 확인하고, Windows는 "신뢰 저장소에 등록" 버튼으로 현재 사용자 저장소에 바로 등록합니다(관리자 권한 불필요, Windows 확인 창이 뜸). 내보내기·등록 명령 복사·새 CA 만들기도 같은 화면에 있습니다. `ca.pfx`는 이 CA로 서명된 모든 인증서를 위조할 수 있으므로 공유하지 않습니다.
- 이미 가진 CA(예: oeHub의 `ca.cer`/`ca.pfx`)는 같은 화면의 "CA 가져오기"로 바꿔 쓸 수 있습니다. 브라우저가 이미 그 CA를 신뢰하면 다시 등록할 필요가 없습니다. 개인키는 PEM(PKCS#8, 비밀번호 암호화 가능) 또는 PKCS#12(.pfx/.p12)를 받고, PKCS#12에 인증서가 들어 있으면 인증서 파일은 생략할 수 있습니다. CA 인증서인지, 개인키가 인증서와 짝이 맞는지 확인한 뒤 `root-ca/`에 암호 없는 PEM으로 저장하고 프록시를 다시 시작합니다.
- 처음 443 포트를 열 때 Windows 방화벽이 허용 여부를 물을 수 있습니다. 휴대폰 등 다른 기기에서 쓰려면 허용합니다.
- 백엔드는 내부망뿐 아니라 외부 주소도 허용하고, 백엔드의 내부 인증서도 신뢰합니다.

## 빌드

```
./gradlew test         # 단위 테스트
./gradlew shadowJar    # build/libs/oehubapp.jar
./gradlew run          # 데스크톱 앱 개발 실행
```

JDK 25 툴체인으로 빌드하고 `--release 21`로 컴파일합니다(oe-proxy가 가상 스레드를 쓰므로 21 이상). 형제 폴더 `../oeProxy`가 있으면 oeHub처럼 `includeBuild`로 그 소스를 직접 빌드해 쓰고, 없으면 Maven Central의 `io.github.tricatch:oe-proxy`를 받습니다.

화면 확인용으로 `-Doehubapp.shot=<경로 접두어>`를 주고 실행하면 탭 4개와 주요 대화상자를 `<접두어>-0.png` … 로 저장하고 종료합니다.

| 라이브러리 | 버전 | 라이선스 |
|---|---|---|
| [FlatLaf](https://github.com/JFormDesigner/FlatLaf) | 3.8 | Apache-2.0 |
| [FlatLaf Extras](https://github.com/JFormDesigner/FlatLaf/tree/main/flatlaf-extras) | 3.8 | Apache-2.0 |
| [JSVG](https://github.com/weisJ/jsvg) (FlatLaf Extras 의존성) | 2.2.0 | MIT |
| [RSyntaxTextArea](https://github.com/bobbylight/RSyntaxTextArea) | 3.6.0 | BSD-3-Clause |
| [Jackson Databind](https://github.com/FasterXML/jackson-databind) | 2.22.4 | Apache-2.0 |
| [Jackson YAML](https://github.com/FasterXML/jackson-dataformats-text) | 2.22.4 | Apache-2.0 |
| [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml) (Jackson YAML 의존성) | 2.5 | Apache-2.0 |
| [oe-proxy](https://github.com/tricatch/oeProxy) | 0.1.0 | MIT |
| [gotpache-keytool](https://github.com/tricatch/gotpache-keytool) (oe-proxy 의존성) | 0.1.0 | MIT |
| [Bouncy Castle](https://www.bouncycastle.org) (oe-proxy 의존성) | 1.85 | MIT |
| [Caffeine](https://github.com/ben-manes/caffeine) (oe-proxy 의존성) | 3.1.8 | Apache-2.0 |
| [Ant-Style Path Matcher](https://github.com/azagniotov/ant-style-path-matcher) (oe-proxy 의존성) | 1.0.0 | Apache-2.0 |
| [Caffeine](https://github.com/ben-manes/caffeine)이 쓰는 [Checker Framework Qualifiers](https://checkerframework.org) | 3.37.0 | MIT |
| [Caffeine](https://github.com/ben-manes/caffeine)이 쓰는 [Error Prone Annotations](https://errorprone.info) | 2.21.1 | Apache-2.0 |
| [SLF4J Simple](https://www.slf4j.org/) | 2.0.17 | MIT |
| [SLF4J API](https://www.slf4j.org/) (oe-proxy 의존성) | 2.0.18 | MIT |
| [Lucide](https://lucide.dev/) 아이콘 (oeHub에서 복사) | — | ISC |

앱의 설정 → 일반 → 오픈소스에도 같은 목록이 링크와 함께 나옵니다. 버전은 빌드할 때 jar에 기록한 실제 포함 버전(`oehubapp-build.properties`)을 보여 주므로, 라이브러리를 추가하면 그 화면의 목록(`SettingsTab.LIBS`)과 이 표에 함께 넣습니다.

## oeHub와 맞추기

화면은 oeHub와 같은 모양을 유지하는 것이 원칙입니다.

색·크기·문구는 oeHub의 CSS와 메시지를 기준으로 Java에 옮겼습니다. oeHub 쪽이 바뀌면 아래 짝을 함께 고칩니다.

| 이 저장소 (`tricatch.oehubapp`) | oeHub 원본 |
|---|---|
| `ChromeArgs` | `static/js/chrome-launch-args.js`, `util.js`의 `resolveUDD`·`wrapDelayedUrl`·`winArgQuote`·`macArgQuote` |
| `HostsRules` | `util.js`의 `parseHostsToMap`, hosts 페이지의 `mergeSelectedProfiles` |
| `ui.HostsTab` | `templates/oehub/hosts.pebble` (설정 키는 같은 `hostsConf` 이름) |
| `ui.ProxyTab` | `templates/oehub/proxy.pebble` (`validateSchema`, `buildMergedVirtualYaml`) |
| `ui.MonitorTab` | `templates/oehub/proxy-monitor.pebble` |
| `ui.SidebarTree` | `static/js/list-folders.js` |
| `ui.Ui`, `ui.OeDialog` | `static/css/app.css`, `oe-panel.css`, `hosts.css` |

### oeHub에서 복사해 온 리소스

경로는 `src/main/resources/` 기준입니다.

| 이 저장소 | oeHub 원본 | 비고 |
|---|---|---|
| `i18n/messages_{en,ko}.properties` | `i18n/` | 그대로. 앱 전용 문구는 `i18n/oehubapp_{en,ko}.properties`에 두고 같은 키는 덮어씀 |
| `icon/` | `static/icon/` | 그대로(Lucide SVG, 로고, 창 아이콘) |
| `example/` | `example/` | 새 프로필·vhost의 예시 내용 |
