param(
    [string]$BuildDirectory,
    [string]$JavaHome = 'C:/Program Files/Java/jdk-17',
    [string]$NativeJavaHome = 'C:/Program Files/Java/jdk-22',
    [string]$AdditionalNativeDirectory,
    [hashtable]$Sources = @{},
    [switch]$PrepareOnly
)
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$properties = Get-Content -LiteralPath (Join-Path $projectDirectory 'gradle.properties') -Raw | ConvertFrom-StringData
if (!$BuildDirectory) { $BuildDirectory = Join-Path $projectDirectory ('.agentinfo/local-stack-' + [Guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $BuildDirectory) { throw "BuildDirectory must be new: $BuildDirectory" }
New-Item -ItemType Directory -Path $BuildDirectory | Out-Null
$BuildDirectory = (Resolve-Path -LiteralPath $BuildDirectory).Path
$defaultSources = @{
    native = Join-Path $env:USERPROFILE 'CLionProjects/KrunchX'
    api = Join-Path $env:USERPROFILE 'Desktop/Valkyrien-Skies-Physics-API'
    bridge = Join-Path $env:USERPROFILE 'Desktop/Valkyrien-Skies-Krunch-Physics-API'
    core = Join-Path $env:USERPROFILE 'IdeaProjects/vs-core'
    vs = Join-Path $env:USERPROFILE 'IdeaProjects/Valkyrien-Skies-2'
}
function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable exited with $LASTEXITCODE" }
}
function Initialize-Source([string]$Name, [string]$Revision, [string]$Patch) {
    $source = if ($Sources.ContainsKey($Name)) { $Sources[$Name] } else { $defaultSources[$Name] }
    $destination = Join-Path $BuildDirectory $Name
    Invoke-Checked git @('init', $destination)
    Invoke-Checked git @('-C', $destination, 'fetch', '--depth=1', $source, $Revision)
    Invoke-Checked git @('-C', $destination, 'checkout', '--detach', 'FETCH_HEAD')
    $actual = & git -C $destination rev-parse HEAD
    if ($actual -ne $Revision) { throw "Unexpected revision for $Name`: $actual" }
    Invoke-Checked git @('-C', $destination, 'apply', '--ignore-space-change', (Join-Path $projectDirectory "patches/$Patch"))
}
Initialize-Source native $properties.krunch_source_revision 'krunch-shape-queries.patch'
Initialize-Source api $properties.physics_api_source_revision 'physics-api-shape-queries.patch'
Initialize-Source bridge $properties.krunch_api_source_revision 'krunch-api-shape-queries.patch'
Initialize-Source core $properties.vs_core_source_revision 'vs-core-shape-queries.patch'
Initialize-Source vs $properties.vs2_source_revision 'vs-playtest-server-hooks.patch'
if ($PrepareOnly) { return }
$nativeDirectory = Join-Path $BuildDirectory 'native'
Invoke-Checked git @('-C', $nativeDirectory, '-c', 'url.https://github.com/.insteadOf=git@github.com:', 'submodule', 'update', '--init', '--recursive')
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
$visualStudio = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (!$visualStudio) { throw 'Visual Studio C++ tools are required' }
$environmentCommand = '""' + (Join-Path $visualStudio 'Common7/Tools/VsDevCmd.bat') + '" -arch=x64 >nul && set"'
$developmentEnvironment = & cmd.exe /d /s /c $environmentCommand
if ($LASTEXITCODE -ne 0) { throw 'Unable to initialize the Visual Studio environment' }
foreach ($line in $developmentEnvironment) {
    if ($line -match '^([^=]+)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process') }
}
$cmake = Join-Path $visualStudio 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe'
$env:JAVA_HOME = $NativeJavaHome
Push-Location -LiteralPath $nativeDirectory
try {
    Invoke-Checked $cmake @('--preset', 'windows-clang-cl-agent-release', '-DKRUNCH_ENABLE_SLOW_TESTS=ON')
    Invoke-Checked $cmake @('--build', '--preset', 'windows-clang-cl-agent-release', '--target', 'KrunchJni', 'KrunchBenchmarkTests')
    $env:KRUNCH_AVBD_PACKED_NATIVE_BENCH = '1'
    Invoke-Checked './build-agents-clang-cl/bin/KrunchBenchmarkTests.exe' @()
} finally { Pop-Location }
$nativeBundle = Join-Path $BuildDirectory 'natives'
New-Item -ItemType Directory -Path $nativeBundle | Out-Null
if ($AdditionalNativeDirectory) {
    Get-ChildItem -LiteralPath $AdditionalNativeDirectory -File | Where-Object { $_.Name -match 'KrunchJni.*\.(dll|so|dylib)$' } |
        Copy-Item -Destination $nativeBundle
}
Copy-Item -LiteralPath (Join-Path $nativeDirectory 'build-agents-clang-cl/bin/KrunchJni.dll') -Destination (Join-Path $nativeBundle 'KrunchJni64.dll')
$env:JAVA_HOME = $JavaHome
function Invoke-Gradle([string]$Name, [string[]]$Arguments) {
    Push-Location -LiteralPath (Join-Path $BuildDirectory $Name)
    try { Invoke-Checked './gradlew.bat' ($Arguments + @('--max-workers=4', '--no-daemon')) }
    finally { Pop-Location }
}
Invoke-Gradle api @('publishToMavenLocal', "-PCustomReleaseVersion=$($properties.physics_api_version)")
Invoke-Gradle bridge @('test', 'publishToMavenLocal', "-Pvs_phys_api_version=$($properties.physics_api_version)",
    "-PCustomReleaseVersion=$($properties.krunch_version)", "-PLocalKrunchNativeDirectory=$nativeBundle")
Invoke-Gradle core @('publishToMavenLocal', "-Pkrunch_version=$($properties.krunch_version)", "-PCustomReleaseVersion=$($properties.vs_core_version)")
Invoke-Gradle vs @(':forge:publishToMavenLocal', "-PCustomVersion=$($properties.vs2_version.Split('+', 2)[1])",
    "-Pvs_core_version=$($properties.vs_core_version)", "-Pkrunch_version=$($properties.krunch_version)", "-Pvs_phys_api_version=$($properties.physics_api_version)")
Write-Output "Built $($properties.vs2_version) from pinned playtest $($properties.vs2_source_revision)"
