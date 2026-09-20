<#
    FanLab build -- aapt2 -> javac -> d8 -> zipalign -> apksigner. No Gradle.

    Usage
        .\build.ps1                 # tests, then both variants
        .\build.ps1 -Variant plain  # just the unsigned-privilege one
        .\build.ps1 -Variant system # just the platform-signed one
        .\build.ps1 -SkipTests      # skip the headless test suite
        .\build.ps1 -Clean          # wipe build/ and out/ first
#>
[CmdletBinding()]
param(
    [ValidateSet('both', 'plain', 'system')]
    [string]$Variant = 'both',
    [switch]$SkipTests,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot

# Toolchain discovery. Override with JAVA_HOME (a JDK 11+; Android Studio ships one at
# jbr\) and ANDROID_SDK_ROOT or ANDROID_HOME; the fallbacks are the Windows defaults.
$Jbr = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Android\Android Studio\jbr' }
$Sdk = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
       elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
       else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }

# Newest build-tools and platform present, rather than versions pinned to one machine.
$BuildTools = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
              Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$Platform   = Get-ChildItem (Join-Path $Sdk 'platforms') -Directory -ErrorAction SilentlyContinue |
              Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
              Sort-Object { [int]($_.Name -replace '\D','') } -Descending |
              Select-Object -First 1 -ExpandProperty FullName
if (-not $BuildTools) { throw "No build-tools found under $Sdk. Set ANDROID_SDK_ROOT." }
if (-not $Platform)   { throw "No platform with android.jar found under $Sdk. Set ANDROID_SDK_ROOT." }

$Javac     = Join-Path $Jbr 'bin\javac.exe'
$Java      = Join-Path $Jbr 'bin\java.exe'
$Jar       = Join-Path $Jbr 'bin\jar.exe'
$Keytool   = Join-Path $Jbr 'bin\keytool.exe'
$Aapt2     = Join-Path $BuildTools 'aapt2.exe'
$D8        = Join-Path $BuildTools 'd8.bat'
$Zipalign  = Join-Path $BuildTools 'zipalign.exe'
$Apksigner = Join-Path $BuildTools 'apksigner.bat'
$AndroidJar= Join-Path $Platform 'android.jar'

# d8.bat and apksigner.bat are shell wrappers that look for a JDK.
$env:JAVA_HOME = $Jbr

# API 28 is load-bearing, not cosmetic: plat_seapp_contexts only places an app in the
# untrusted_app domain when minTargetSdkVersion is 28, and only that domain may WRITE sysfs.
$MinSdk    = 28
$TargetSdk = 28

$JavaPkg   = 'com.daleygames.fanlab'

function Fail([string]$msg) {
    Write-Host ''
    Write-Host "BUILD FAILED: $msg" -ForegroundColor Red
    exit 1
}

function Step([string]$msg) {
    Write-Host ''
    Write-Host "== $msg" -ForegroundColor Cyan
}

function Run([string]$exe, [string[]]$argv) {
    Write-Host "   $exe $($argv -join ' ')" -ForegroundColor DarkGray
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { Fail "$exe exited $LASTEXITCODE" }
}

Step 'toolchain'
foreach ($t in @($Javac, $Java, $Jar, $Keytool, $Aapt2, $D8, $Zipalign, $Apksigner, $AndroidJar)) {
    if (-not (Test-Path $t)) { Fail "missing: $t" }
    Write-Host "   ok  $t" -ForegroundColor DarkGray
}

$SrcDir   = Join-Path $Root 'src'
$TestDir  = Join-Path $Root 'test'
$ResDir   = Join-Path $Root 'res'
$ManDir   = Join-Path $Root 'manifests'
$KeyDir   = Join-Path $Root 'keys'
$BuildDir = Join-Path $Root 'build'
$OutDir   = Join-Path $Root 'out'
$ToolsDir = Join-Path $Root 'tools'

if ($Clean) {
    Step 'clean'
    foreach ($d in @($BuildDir, $OutDir)) {
        if (Test-Path $d) { Remove-Item -Recurse -Force $d }
    }
}
New-Item -ItemType Directory -Force $BuildDir | Out-Null
New-Item -ItemType Directory -Force $OutDir   | Out-Null

# Every .java in src/ except the ones that touch android.* — used for the host tests.
$PureSources = @(
    'Thermistor.java', 'Sysfs.java', 'FanIo.java', 'CurveConfig.java',
    'FanCurve.java', 'Mode.java', 'Sample.java', 'CsvLogger.java', 'SysProps.java',
    'LinearConfig.java', 'FanLinear.java',
    'LedDrive.java',
    'Provenance.java',
    'ExpFit.java', 'SweepPlan.java', 'SweepStep.java', 'SweepEngine.java',
    'HoldSession.java', 'PicoReg.java', 'Json.java', 'SweepReport.java'
) | ForEach-Object { Join-Path $SrcDir "com\daleygames\fanlab\$_" }

Step 'resources'
if (-not (Test-Path (Join-Path $ResDir 'drawable\ic_launcher.png'))) {
    Run 'python' @((Join-Path $ToolsDir 'mkicons.py'), (Join-Path $ResDir 'drawable'))
} else {
    Write-Host '   icons already generated (delete res\drawable to regenerate)' -ForegroundColor DarkGray
}

if (-not $SkipTests) {
    Step 'headless tests'
    $TestBuild = Join-Path $BuildDir 'test'
    if (Test-Path $TestBuild) { Remove-Item -Recurse -Force $TestBuild }
    New-Item -ItemType Directory -Force $TestBuild | Out-Null

    # The pure core has no android.* references, so the host JDK compiles it as ordinary Java.
    $coreArgs = @('-Xlint:-options', '-encoding', 'UTF-8', '-d', $TestBuild,
                  '-sourcepath', $SrcDir) + $PureSources
    Run $Javac $coreArgs
    Run $Javac @('-Xlint:-options', '-encoding', 'UTF-8', '-d', $TestBuild,
                 '-cp', $TestBuild, (Join-Path $TestDir 'FanLabTest.java'))
    Write-Host "   $Java -cp $TestBuild FanLabTest" -ForegroundColor DarkGray
    & $Java -cp $TestBuild FanLabTest
    if ($LASTEXITCODE -ne 0) { Fail 'headless tests failed' }
}

function Ensure-DebugKeystore {
    $ks = Join-Path $KeyDir 'fanlab-debug.keystore'
    if (Test-Path $ks) { return $ks }
    Step 'generating a debug keystore (first run only)'
    New-Item -ItemType Directory -Force $KeyDir | Out-Null
    Run $Keytool @(
        '-genkeypair', '-noprompt',
        '-keystore', $ks,
        '-storepass', 'android', '-keypass', 'android',
        '-alias', 'fanlab', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10950',
        '-dname', 'CN=FanLab, OU=SCN350, O=Daley Games, C=GB'
    )
    return $ks
}

function Ensure-PlatformKey {
    $pk8 = Join-Path $KeyDir 'platform.pk8'
    $pem = Join-Path $KeyDir 'platform.x509.pem'
    if ((Test-Path $pk8) -and (Test-Path $pem)) { return @($pk8, $pem) }
    Step 'fetching the AOSP platform key'
    New-Item -ItemType Directory -Force $KeyDir | Out-Null
    $base = 'https://android.googlesource.com/platform/build/+/refs/heads/main/target/product/security'
    foreach ($pair in @(@('platform.pk8', $pk8), @('platform.x509.pem', $pem))) {
        $b64 = "$($pair[1]).b64"
        Invoke-WebRequest -UseBasicParsing -Uri "$base/$($pair[0])?format=TEXT" -OutFile $b64
        [IO.File]::WriteAllBytes($pair[1],
            [Convert]::FromBase64String(((Get-Content -Raw $b64) -replace '\s', '')))
        Remove-Item -Force $b64
    }
    # AOSP's own published platform key: not a secret, and not ours.
    Write-Host '   NOTE: keys/platform.pk8 is the PUBLIC AOSP platform key.' -ForegroundColor DarkYellow
    return @($pk8, $pem)
}

function Build-Variant([string]$name) {
    Step "variant: $name"
    $vb = Join-Path $BuildDir $name
    if (Test-Path $vb) { Remove-Item -Recurse -Force $vb }
    New-Item -ItemType Directory -Force (Join-Path $vb 'gen')     | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $vb 'classes') | Out-Null
    New-Item -ItemType Directory -Force (Join-Path $vb 'dex')     | Out-Null

    $manifest = Join-Path $ManDir "AndroidManifest.$name.xml"
    if (-not (Test-Path $manifest)) { Fail "no manifest for variant $name" }

    $resZip = Join-Path $vb 'res.zip'
    Run $Aapt2 @('compile', '--dir', $ResDir, '-o', $resZip)

    $baseApk = Join-Path $vb 'base.apk'
    Run $Aapt2 @('link',
        '-o', $baseApk,
        '--manifest', $manifest,
        '-I', $AndroidJar,
        '--java', (Join-Path $vb 'gen'),
        '--custom-package', $JavaPkg,
        '--min-sdk-version', "$MinSdk",
        '--target-sdk-version', "$TargetSdk",
        '--no-version-vectors',
        $resZip)

    $sources = @(Get-ChildItem -Recurse -Filter *.java $SrcDir | ForEach-Object FullName)
    $sources += @(Get-ChildItem -Recurse -Filter R.java (Join-Path $vb 'gen') |
                  ForEach-Object FullName)
    $argFile = Join-Path $vb 'javac.args'
    Set-Content -Path $argFile -Value ($sources | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' })
    Run $Javac @('-Xlint:-options', '-encoding', 'UTF-8',
                 '-source', '8', '-target', '8',
                 '-bootclasspath', $AndroidJar,
                 '-d', (Join-Path $vb 'classes'),
                 "@$argFile")

    $classesJar = Join-Path $vb 'classes.jar'
    Run $Jar @('--create', '--file', $classesJar, '-C', (Join-Path $vb 'classes'), '.')
    Run $D8 @('--release', '--min-api', "$MinSdk",
              '--lib', $AndroidJar,
              '--output', (Join-Path $vb 'dex'),
              $classesJar)

    # javac is pointed at a much newer android.jar, so check every symbol in the dex
    # against the SDK's own api-versions.xml.
    Run 'python' @((Join-Path $ToolsDir 'dexapi.py'),
                   (Join-Path $vb 'dex\classes.dex'),
                   (Join-Path $Platform 'data\api-versions.xml'),
                   "$MinSdk")

    $unsigned = Join-Path $vb 'unsigned.apk'
    Run 'python' @((Join-Path $ToolsDir 'apkadd.py'), $baseApk, $unsigned,
                   ('classes.dex=' + (Join-Path $vb 'dex\classes.dex')))

    # Align before signing: v2 signs the aligned bytes.
    $aligned = Join-Path $vb 'aligned.apk'
    Run $Zipalign @('-f', '-p', '4', $unsigned, $aligned)

    $final = Join-Path $OutDir "fanlab-$name.apk"
    if (Test-Path $final) { Remove-Item -Force $final }

    if ($name -eq 'system') {
        $keys = Ensure-PlatformKey
        Run $Apksigner @('sign',
            '--key', $keys[0], '--cert', $keys[1],
            '--min-sdk-version', "$MinSdk",
            '--v1-signing-enabled', 'true',
            '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'true',
            '--v4-signing-enabled', 'false',
            '--out', $final, $aligned)
    } else {
        $ks = Ensure-DebugKeystore
        Run $Apksigner @('sign',
            '--ks', $ks, '--ks-pass', 'pass:android',
            '--ks-key-alias', 'fanlab', '--key-pass', 'pass:android',
            '--min-sdk-version', "$MinSdk",
            '--v1-signing-enabled', 'true',
            '--v2-signing-enabled', 'true',
            '--v3-signing-enabled', 'true',
            '--v4-signing-enabled', 'false',
            '--out', $final, $aligned)
    }

    Run $Apksigner @('verify', '--print-certs', '-v', $final)
    Run $Zipalign @('-c', '-v', '4', $final) 2>&1 | Out-Null
    Write-Host "   zipalign check: OK" -ForegroundColor DarkGray

    $size = (Get-Item $final).Length
    Write-Host "   built $final  ($size bytes)" -ForegroundColor Green
}

if ($Variant -eq 'both' -or $Variant -eq 'plain')  { Build-Variant 'plain' }
if ($Variant -eq 'both' -or $Variant -eq 'system') { Build-Variant 'system' }

Step 'manifest verification'
foreach ($f in (Get-ChildItem -Filter *.apk $OutDir)) {
    Write-Host "-- $($f.Name)" -ForegroundColor Yellow
    & $Aapt2 dump badging $f.FullName |
        Select-String -Pattern 'package:|sdkVersion|sharedUserId|launchable|application-label|application-icon|uses-permission'
    Write-Host '   sharedUserId in the compiled manifest:' -ForegroundColor DarkGray
    $tree = & $Aapt2 dump xmltree --file AndroidManifest.xml $f.FullName
    $sui = $tree | Select-String -Pattern 'sharedUserId'
    if ($sui) { $sui } else { Write-Host '      (none - correct for the plain build)' }
    Write-Host '   signing schemes:' -ForegroundColor DarkGray
    & $Apksigner verify -v --min-sdk-version 21 --max-sdk-version 23 $f.FullName 2>&1 |
        Select-String -Pattern 'v1 scheme'
    & $Apksigner verify -v --min-sdk-version 24 --max-sdk-version 27 $f.FullName 2>&1 |
        Select-String -Pattern 'v2 scheme'
    & $Apksigner verify -v --min-sdk-version 28 $f.FullName 2>&1 |
        Select-String -Pattern 'v3 scheme|certificate DN|certificate SHA-256'
}

Step 'done'
Get-ChildItem $OutDir | Format-Table Name, Length, LastWriteTime
