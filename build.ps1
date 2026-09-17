param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$SdkPath,
    [Parameter(Mandatory=$true)][string]$XposedApiJar,
    [string]$BuildToolsVersion,
    [string]$WorkDir = (Join-Path $PSScriptRoot '.build'),
    [string]$OutputApk = (Join-Path $PSScriptRoot 'XiaoAiVolumeSync-1.4.0.apk'),
    [switch]$Diagnostics
)
$ErrorActionPreference = 'Stop'
function Run([string]$Exe, [string[]]$Arguments) {
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Exe failed with exit code $LASTEXITCODE" }
}
$JavaHome = [IO.Path]::GetFullPath($JavaHome)
$SdkPath = [IO.Path]::GetFullPath($SdkPath)
$WorkDir = [IO.Path]::GetFullPath($WorkDir)
$OutputApk = [IO.Path]::GetFullPath($OutputApk)
$XposedApiJar = [IO.Path]::GetFullPath($XposedApiJar)
New-Item -ItemType Directory -Force -Path $WorkDir,(Split-Path $OutputApk) | Out-Null
$androidJar = Join-Path $SdkPath 'platforms/android-36/android.jar'
if ([string]::IsNullOrWhiteSpace($BuildToolsVersion)) {
    $toolsDir = Get-ChildItem (Join-Path $SdkPath 'build-tools') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'aapt2.exe') } | Sort-Object Name -Descending | Select-Object -First 1
} else {
    $toolsDir = Get-Item (Join-Path $SdkPath "build-tools/$BuildToolsVersion") -ErrorAction SilentlyContinue
}
$toolsLabel = if ([string]::IsNullOrWhiteSpace($BuildToolsVersion)) { 'Build Tools 36' } else { "Build Tools $BuildToolsVersion" }
if (!$toolsDir -or !(Test-Path (Join-Path $toolsDir.FullName 'aapt2.exe')) -or !(Test-Path $androidJar) -or !(Test-Path $XposedApiJar)) {
    throw "Install Android platform 36, $toolsLabel, and Xposed API 82 first; see README."
}
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$jar = Join-Path $JavaHome 'bin/jar.exe'
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
$aapt2 = Join-Path $toolsDir.FullName 'aapt2.exe'
$sourceRoot = Join-Path $PSScriptRoot 'app/src/main'
$classes = Join-Path $WorkDir 'classes'
$generated = Join-Path $WorkDir 'generated'
$tests = Join-Path $WorkDir 'tests'
$dex = Join-Path $WorkDir 'dex'
New-Item -ItemType Directory -Force -Path $classes,$generated,$tests,$dex | Out-Null
# A new build directory per mode prevents diagnostic-only generated classes from leaking across builds.
$flagFile = Join-Path $generated 'BuildFlags.java'
$flag = if ($Diagnostics) { 'true' } else { 'false' }
[IO.File]::WriteAllText($flagFile, "package io.github.rin.xiaoaivolumesync; final class BuildFlags { static final boolean DIAGNOSTICS = $flag; }", [Text.UTF8Encoding]::new($false))
Run $javac @('--release','8','-encoding','UTF-8','-d',$tests,(Join-Path $sourceRoot 'java/io/github/rin/xiaoaivolumesync/VolumeMath.java'),(Join-Path $sourceRoot 'java/io/github/rin/xiaoaivolumesync/KeyRoutePolicy.java'),(Join-Path $PSScriptRoot 'tests/VolumeMathTest.java'))
Run $java @('-cp',$tests,'VolumeMathTest')
$resources = Join-Path $WorkDir 'resources.zip'
$base = Join-Path $WorkDir 'resources.apk'
Run $aapt2 @('compile','--dir',(Join-Path $sourceRoot 'res'),'-o',$resources)
Run $aapt2 @('link','-o',$base,'-I',$androidJar,'--manifest',(Join-Path $sourceRoot 'AndroidManifest.xml'),'-A',(Join-Path $sourceRoot 'assets'),'--auto-add-overlay',$resources)
$sourceFiles = @(Get-ChildItem (Join-Path $sourceRoot 'java') -Recurse -Filter '*.java' | ForEach-Object FullName) + @($flagFile)
Run $javac (@('--release','8','-encoding','UTF-8','-classpath',"$androidJar;$XposedApiJar",'-d',$classes) + $sourceFiles)
$classJar = Join-Path $WorkDir 'module-classes.jar'
Run $jar @('cf',$classJar,'-C',$classes,'.')
Run $java @('-cp',(Join-Path $toolsDir.FullName 'lib/d8.jar'),'com.android.tools.r8.D8','--release','--min-api','31','--lib',$androidJar,'--classpath',$XposedApiJar,'--output',$dex,$classJar)
$unsigned = Join-Path $WorkDir 'unsigned.apk'
Copy-Item -LiteralPath $base -Destination $unsigned -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $dex 'classes.dex'), 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }
$aligned = Join-Path $WorkDir 'aligned.apk'
Run (Join-Path $toolsDir.FullName 'zipalign.exe') @('-f','-p','4',$unsigned,$aligned)
$keyFile = Join-Path (Split-Path $WorkDir) 'xiaoaivolumesync-signing.p12'
if (!(Test-Path $keyFile)) {
    Run $keytool @('-genkeypair','-keystore',$keyFile,'-storetype','PKCS12','-storepass','local-build-key','-keypass','local-build-key','-alias','module','-keyalg','RSA','-keysize','3072','-validity','10000','-dname','CN=XiaoAi Volume Sync Local Build','-noprompt')
}
Run $java @('-jar',(Join-Path $toolsDir.FullName 'lib/apksigner.jar'),'sign','--ks',$keyFile,'--ks-pass','pass:local-build-key','--key-pass','pass:local-build-key','--ks-key-alias','module','--out',$OutputApk,$aligned)
Run $java @('-jar',(Join-Path $toolsDir.FullName 'lib/apksigner.jar'),'verify','--verbose',$OutputApk)
Run (Join-Path $toolsDir.FullName 'zipalign.exe') @('-c','4',$OutputApk)
Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256
Write-Output "APK: $OutputApk"
