# Installs (or updates) oeHubApp on Windows from the GitHub release.
#
#   irm https://raw.githubusercontent.com/tricatch/oeHubApp/master/install.ps1 | iex
#   & ([scriptblock]::Create((irm https://raw.githubusercontent.com/tricatch/oeHubApp/master/install.ps1))) -Uninstall
#
# Environment:
#   OEHUBAPP_VERSION  release tag to install (default: the latest release)
#   OEHUBAPP_HOME     where the jar goes (default: %LOCALAPPDATA%\Programs\oeHubApp)
#
# No .exe is made (Windows Smart App Control blocks unsigned ones): the Start menu shortcut runs
# the installed, signed javaw.exe with the jar. The app is also listed in Settings > Apps, where
# "Uninstall" runs this script (kept in the install folder) with -Uninstall.
# Works on Windows PowerShell 5.1 and PowerShell 7.

param([switch]$Uninstall)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Repo = 'tricatch/oeHubApp'
$Raw = "https://raw.githubusercontent.com/$Repo/master"
$MinJava = 21
$UninstallKey = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\oeHubApp'
$AppHome = if ($env:OEHUBAPP_HOME) { $env:OEHUBAPP_HOME } else { Join-Path $env:LOCALAPPDATA 'Programs\oeHubApp' }
# Uninstall removes what was installed, wherever OEHUBAPP_HOME pointed at the time.
if ($Uninstall -and -not $env:OEHUBAPP_HOME) {
    $recorded = (Get-ItemProperty -Path $UninstallKey -Name InstallLocation -ErrorAction SilentlyContinue).InstallLocation
    if ($recorded) { $AppHome = $recorded }
}
$Jar = Join-Path $AppHome 'oehubapp.jar'
$Ico = Join-Path $AppHome 'oehubapp.ico'
$Agent = Join-Path $AppHome 'oehosts-agent.jar'
$Script = Join-Path $AppHome 'install.ps1'
$Shortcut = Join-Path ([Environment]::GetFolderPath('Programs')) 'oeHubApp.lnk'

function Fail($msg) {
    Write-Host "error: $msg" -ForegroundColor Red
    # Under "irm | iex" an exit would close the user's PowerShell window; throw stops the script only.
    throw $msg
}

if ($Uninstall) {
    Remove-Item -LiteralPath $Shortcut -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $UninstallKey -Recurse -Force -ErrorAction SilentlyContinue
    foreach ($f in @($Jar, $Ico, $Script)) {
        try {
            Remove-Item -LiteralPath $f -Force -ErrorAction Stop
        } catch [System.Management.Automation.ItemNotFoundException] {
        } catch {
            Fail "could not remove $f - close oeHubApp and try again"
        }
    }
    # A Java application still running with -javaagent keeps the agent locked: warn, do not stop.
    foreach ($f in @($Agent, (Join-Path $AppHome 'oehubapp-update.log'))) {
        try {
            Remove-Item -LiteralPath $f -Force -ErrorAction Stop
        } catch [System.Management.Automation.ItemNotFoundException] {
        } catch {
            Write-Host "warning: could not remove $f - a Java application may still be using it" -ForegroundColor Yellow
        }
    }
    if ((Test-Path -LiteralPath $AppHome) -and -not (Get-ChildItem -LiteralPath $AppHome -Force)) {
        Remove-Item -LiteralPath $AppHome -Force
    }
    Write-Host 'oeHubApp removed. Your data folder was kept:'
    Write-Host "  $(Join-Path $env:APPDATA 'oeHubApp')"
    return
}

# Windows PowerShell 5.1 may not offer TLS 1.2 by default; GitHub requires it.
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch {}

# The major version of the Java whose bin folder holds $javaw: from the JDK's "release" file, or
# by asking java.exe next to it (e.g. the Oracle javapath shim, which has no release file).
function Get-JavaMajor($javaw) {
    $bin = Split-Path -Parent $javaw
    $release = Join-Path (Split-Path -Parent $bin) 'release'
    $v = $null
    if (Test-Path -LiteralPath $release) {
        $m = Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="([^"]+)"' | Select-Object -First 1
        if ($m) { $v = $m.Matches[0].Groups[1].Value }
    }
    if (-not $v) {
        $java = Join-Path $bin 'java.exe'
        if (-not (Test-Path -LiteralPath $java)) { return 0 }
        # java -version writes to stderr; Windows PowerShell 5.1 turns that into an error under 'Stop'.
        $ErrorActionPreference = 'Continue'
        $out = (& $java -version 2>&1 | Out-String)
        if ($out -match 'version "([^"]+)"') { $v = $Matches[1] }
    }
    if (-not $v) { return 0 }
    if ($v.StartsWith('1.')) { $v = $v.Substring(2) }
    if ($v -match '^(\d+)') { return [int]$Matches[1] }
    return 0
}

# The newest javaw.exe of Java $MinJava or later: JAVA_HOME, PATH, then the usual install folders.
function Find-Javaw {
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_HOME) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\javaw.exe')) }
    foreach ($c in @(Get-Command javaw.exe -All -ErrorAction SilentlyContinue)) { $candidates.Add($c.Source) }
    $roots = @($env:ProgramFiles, ${env:ProgramFiles(x86)}, (Join-Path $env:LOCALAPPDATA 'Programs')) | Where-Object { $_ }
    foreach ($root in $roots) {
        foreach ($vendor in 'Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft', 'OpenJDK') {
            $dir = Join-Path $root $vendor
            if (Test-Path -LiteralPath $dir) {
                Get-ChildItem -LiteralPath $dir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                    $candidates.Add((Join-Path $_.FullName 'bin\javaw.exe'))
                }
            }
        }
    }
    $best = $null
    $bestMajor = 0
    foreach ($j in $candidates) {
        if (-not (Test-Path -LiteralPath $j)) { continue }
        $major = Get-JavaMajor $j
        if ($major -ge $MinJava -and $major -gt $bestMajor) {
            $best = $j
            $bestMajor = $major
        }
    }
    return $best
}

$base = if ($env:OEHUBAPP_VERSION) {
    "https://github.com/$Repo/releases/download/$($env:OEHUBAPP_VERSION)"
} else {
    "https://github.com/$Repo/releases/latest/download"
}

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("oehubapp-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Write-Host ("Downloading oeHubApp" + $(if ($env:OEHUBAPP_VERSION) { " $($env:OEHUBAPP_VERSION)" } else { '' }) + " ...")
    $tmpJar = Join-Path $tmp 'oehubapp.jar'
    $sums = Join-Path $tmp 'SHA256SUMS.txt'
    try {
        Invoke-WebRequest -UseBasicParsing -Uri "$base/oehubapp.jar" -OutFile $tmpJar
        Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS.txt" -OutFile $sums
    } catch {
        Fail "download failed from ${base}: $($_.Exception.Message)"
    }

    $expected = $null
    foreach ($line in Get-Content -LiteralPath $sums) {
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -ge 2 -and $parts[1].TrimStart('*') -match 'oehubapp\.jar$') { $expected = $parts[0].ToLower() }
    }
    if (-not $expected) { Fail 'no checksum for oehubapp.jar in SHA256SUMS.txt' }
    $actual = (Get-FileHash -LiteralPath $tmpJar -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected) { Fail "checksum mismatch (expected $expected, got $actual)" }

    New-Item -ItemType Directory -Force -Path $AppHome | Out-Null
    try {
        Move-Item -LiteralPath $tmpJar -Destination $Jar -Force
    } catch {
        Fail "could not replace $Jar - close oeHubApp and run the installer again"
    }
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

# Kept for "Uninstall" in Settings > Apps. Under "irm | iex" there is no script file, so the
# script is fetched again; when run from a file, that file is copied.
try {
    if ($PSCommandPath -and ((Resolve-Path -LiteralPath $PSCommandPath).Path -ne $Script)) {
        Copy-Item -LiteralPath $PSCommandPath -Destination $Script -Force
    } elseif (-not $PSCommandPath) {
        Invoke-WebRequest -UseBasicParsing -Uri "$Raw/install.ps1" -OutFile $Script
    }
} catch {
    Write-Host "warning: could not save the uninstaller ($($_.Exception.Message))" -ForegroundColor Yellow
}
try {
    Invoke-WebRequest -UseBasicParsing -Uri "$Raw/packaging/windows/oehubapp.ico" -OutFile $Ico
} catch {
    Remove-Item -LiteralPath $Ico -Force -ErrorAction SilentlyContinue
}

$version = $null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
        if ($entry) {
            $reader = New-Object IO.StreamReader($entry.Open())
            try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
            if ($manifest -match '(?m)^Implementation-Version:\s*(\S+)') { $version = $Matches[1] }
        }
    } finally {
        $zip.Dispose()
    }
} catch {}

# oeHosts-Agent ships inside the jar; the app also copies it out at every start (and so keeps it
# up to date). Copied here too so it is in place before the first start. A Java application
# running with -javaagent keeps the old file locked; the app replaces it on a later start.
try {
    $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $entry = $zip.GetEntry('oehosts-agent.jar')
        if ($entry) { [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $Agent, $true) }
    } finally {
        $zip.Dispose()
    }
} catch {}

$javaw = Find-Javaw
if ($javaw) {
    # The shortcut names javaw.exe by its path: after installing another Java, run the installer
    # again to point it there.
    $shell = New-Object -ComObject WScript.Shell
    $lnk = $shell.CreateShortcut($Shortcut)
    $lnk.TargetPath = $javaw
    $lnk.Arguments = "-jar `"$Jar`""
    $lnk.WorkingDirectory = $AppHome
    $lnk.Description = 'oeHubApp'
    if (Test-Path -LiteralPath $Ico) { $lnk.IconLocation = "$Ico,0" }
    $lnk.Save()
}

New-Item -Path $UninstallKey -Force | Out-Null
$uninstallCmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$Script`" -Uninstall"
$sizeKb = [int]((Get-ChildItem -LiteralPath $AppHome -File | Measure-Object -Property Length -Sum).Sum / 1KB)
$values = @{
    DisplayName     = 'oeHubApp'
    DisplayVersion  = $(if ($version) { $version } else { '0' })
    Publisher       = 'tricatch'
    InstallLocation = $AppHome
    UninstallString = $uninstallCmd
    URLInfoAbout    = "https://github.com/$Repo"
}
if (Test-Path -LiteralPath $Ico) { $values.DisplayIcon = $Ico }
foreach ($k in $values.Keys) { Set-ItemProperty -Path $UninstallKey -Name $k -Value $values[$k] }
Set-ItemProperty -Path $UninstallKey -Name NoModify -Value 1 -Type DWord
Set-ItemProperty -Path $UninstallKey -Name NoRepair -Value 1 -Type DWord
Set-ItemProperty -Path $UninstallKey -Name EstimatedSize -Value $sizeKb -Type DWord

Write-Host "Installed oeHubApp $version to $AppHome"
if ($javaw) {
    Write-Host "Start menu: oeHubApp  ($Shortcut)"
    Write-Host "Java: $javaw"
} else {
    Write-Host ''
    Write-Host "Java $MinJava or later was not found, so no Start menu shortcut was made." -ForegroundColor Yellow
    Write-Host '  Install it (e.g. winget install EclipseAdoptium.Temurin.21.JRE, or https://adoptium.net/),'
    Write-Host '  then run this installer again.'
}
Write-Host 'Uninstall: Settings > Apps > oeHubApp, or run this installer with -Uninstall.'
