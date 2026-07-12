# build-agents.ps1 — rebuild full-agent.jar from converter-win\src (instrumented copies of the docs converter-core
# sources; deviations documented in bug22 notes) + build enterworld.jar and guimsgprobe.jar.
param([string]$BuildDirName = 'full-agent')
$ErrorActionPreference = 'Stop'
$Jdk   = 'C:\Users\Raphael\.jdks\jbrsdk_jcef-25.0.3\bin'
$Repo  = Split-Path $PSScriptRoot
$Src   = Join-Path $PSScriptRoot 'src'
$PA    = Join-Path $Repo 'build\agent-vanilla\liquidbounce-agent-vanilla-pure.jar'
$FaDir = Join-Path $Repo (Join-Path 'build' $BuildDirName)
$Out   = Join-Path $FaDir 'out'

if (-not (Test-Path $PA)) { throw "PA jar missing: $PA" }

# --- 1. compile converter + verifier/dispatch/accessor runtime --------------------------------------------
if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Force $Out | Out-Null
& "$Jdk\javac.exe" --release 25 -cp $PA -d $Out `
    (Join-Path $Src 'FullInjectAgent.java') (Join-Path $Src 'RetransformConverter.java') `
    (Join-Path $Src 'LateAttachVerifier.java') (Join-Path $Src 'AccessorBridgeRewriter.java') `
    (Join-Path $Src 'JoinGateRewriter.java') (Join-Path $Src 'AwReflect.java') `
    (Join-Path $Src 'DuckDispatch.java') (Join-Path $Src 'JoinGate.java')
if ($LASTEXITCODE -ne 0) { throw 'javac (converter) failed' }

# --- 1b. focused schema/dispatch/accessor fixtures ---------------------------------------------------------
$TestOut = Join-Path $FaDir 'test-out'
if (Test-Path $TestOut) { Remove-Item $TestOut -Recurse -Force }
New-Item -ItemType Directory -Force $TestOut | Out-Null
& "$Jdk\javac.exe" --release 25 -cp "$Out;$PA" -d $TestOut (Join-Path $PSScriptRoot 'tests\ConverterAutoTests.java')
if ($LASTEXITCODE -ne 0) { throw 'javac (converter fixtures) failed' }
& "$Jdk\java.exe" -cp "$TestOut;$Out;$PA" ConverterAutoTests
if ($LASTEXITCODE -ne 0) { throw 'converter fixtures failed' }

# --- 2. assemble full-agent.jar: PA bundle + manifest swap + targets list + converter classes -------------
$FullJar = Join-Path $FaDir 'full-agent.jar'
Copy-Item $PA $FullJar -Force
# Preserve every PA manifest section (especially ASM's package version, which Mixin uses for Java 25 support),
# changing only the dynamic entry point. Dropping the named ASM section makes real ASM 9.10 look like ASM 9.0.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($FullJar, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    $oldManifest = $zip.GetEntry('META-INF/MANIFEST.MF')
    if ($null -eq $oldManifest) { throw 'PA jar has no manifest' }
    $reader = [System.IO.StreamReader]::new($oldManifest.Open())
    try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
    $oldManifest.Delete()
}
finally { $zip.Dispose() }
if ($manifestText -notmatch '(?ms)Name: org/objectweb/asm/\r?\n.*?Implementation-Version: 9\.[1-9][0-9]*') {
    throw 'PA manifest is missing a usable ASM 9.x package implementation version'
}
$manifestText = $manifestText -replace '(?m)^Agent-Class:[^\r\n]*', 'Agent-Class: FullInjectAgent'
$ManAdd = Join-Path $FaDir 'manifest-add.txt'
$manifestText | Set-Content -Path $ManAdd -Encoding ascii
Copy-Item (Join-Path $PSScriptRoot 'lb-mixin-targets.txt') (Join-Path $FaDir 'lb-mixin-targets.txt') -Force
Push-Location $FaDir
& "$Jdk\jar.exe" ufm full-agent.jar manifest-add.txt lb-mixin-targets.txt -C out .
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'jar ufm (full-agent) failed' }
Pop-Location
$jarEntries = @(& "$Jdk\jar.exe" tf $FullJar)
foreach ($required in @('FullInjectAgent.class','LateAttachVerifier.class','AccessorBridgeRewriter.class',
        'JoinGateRewriter.class','lbrt/DuckDispatch.class','lbrt/DuckDispatch$Impl.class',
        'lbrt/JoinGate.class','lbrt/JoinGate$Call.class')) {
    if ($required -notin $jarEntries) { throw "full-agent.jar missing required entry: $required" }
}
& "$Jdk\java.exe" -cp "$TestOut;$FullJar" ConverterAutoTests compat
if ($LASTEXITCODE -ne 0) { throw 'assembled agent Java 25 compatibility check failed' }
Write-Host "[build] full-agent.jar OK ($((Get-Item $FullJar).Length) bytes)"

# --- 3. enterworld.jar ------------------------------------------------------------------------------------
$EwDir = Join-Path $Repo 'build\enterworld'
New-Item -ItemType Directory -Force $EwDir | Out-Null
& "$Jdk\javac.exe" --release 25 -d $EwDir (Join-Path $Src 'EnterWorld.java')
if ($LASTEXITCODE -ne 0) { throw 'javac (EnterWorld) failed' }
@'
Agent-Class: EnterWorld
'@ | Set-Content -Path (Join-Path $EwDir 'manifest.txt') -Encoding ascii
Push-Location $EwDir
& "$Jdk\jar.exe" cfm enterworld.jar manifest.txt EnterWorld.class
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'jar (enterworld) failed' }
Pop-Location
Write-Host '[build] enterworld.jar OK'

# --- 4. joinserver.jar ------------------------------------------------------------------------------------
$JsDir = Join-Path $Repo 'build\joinserver'
New-Item -ItemType Directory -Force $JsDir | Out-Null
& "$Jdk\javac.exe" --release 25 -d $JsDir (Join-Path $Src 'JoinServer.java')
if ($LASTEXITCODE -ne 0) { throw 'javac (JoinServer) failed' }
@'
Agent-Class: JoinServer
'@ | Set-Content -Path (Join-Path $JsDir 'manifest.txt') -Encoding ascii
Push-Location $JsDir
& "$Jdk\jar.exe" cfm joinserver.jar manifest.txt JoinServer.class
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'jar (joinserver) failed' }
Pop-Location
Write-Host '[build] joinserver.jar OK'

# --- 5. openinventory.jar ---------------------------------------------------------------------------------
$OiDir = Join-Path $Repo 'build\openinventory'
New-Item -ItemType Directory -Force $OiDir | Out-Null
& "$Jdk\javac.exe" --release 25 -d $OiDir (Join-Path $Src 'OpenInventory.java')
if ($LASTEXITCODE -ne 0) { throw 'javac (OpenInventory) failed' }
@'
Agent-Class: OpenInventory
'@ | Set-Content -Path (Join-Path $OiDir 'manifest.txt') -Encoding ascii
Push-Location $OiDir
& "$Jdk\jar.exe" cfm openinventory.jar manifest.txt OpenInventory.class
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'jar (openinventory) failed' }
Pop-Location
Write-Host '[build] openinventory.jar OK'

# --- 6. sidecar-access-audit.jar --------------------------------------------------------------------------
$SaDir = Join-Path $Repo 'build\sidecar-access-audit'
New-Item -ItemType Directory -Force $SaDir | Out-Null
& "$Jdk\javac.exe" --release 25 -cp $FullJar -d $SaDir (Join-Path $Src 'SidecarAccessAudit.java')
if ($LASTEXITCODE -ne 0) { throw 'javac (SidecarAccessAudit) failed' }
@'
Agent-Class: SidecarAccessAudit
'@ | Set-Content -Path (Join-Path $SaDir 'manifest.txt') -Encoding ascii
Push-Location $SaDir
& "$Jdk\jar.exe" cfm sidecar-access-audit.jar manifest.txt SidecarAccessAudit.class 'SidecarAccessAudit$Info.class' 'SidecarAccessAudit$Issue.class'
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'jar (SidecarAccessAudit) failed' }
Pop-Location
Write-Host '[build] sidecar-access-audit.jar OK'

# --- 7. guimsgprobe.jar -----------------------------------------------------------------------------------
$PrDir = Join-Path $Repo 'build\guimsgprobe'
New-Item -ItemType Directory -Force $PrDir | Out-Null
& "$Jdk\javac.exe" --release 25 -d $PrDir (Join-Path $Src 'GuiMsgProbe.java')
if ($LASTEXITCODE -ne 0) { throw 'javac (GuiMsgProbe) failed' }
@'
Agent-Class: GuiMsgProbe
'@ | Set-Content -Path (Join-Path $PrDir 'manifest.txt') -Encoding ascii
Push-Location $PrDir
& "$Jdk\jar.exe" cfm guimsgprobe.jar manifest.txt GuiMsgProbe.class
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'jar (guimsgprobe) failed' }
Pop-Location
Write-Host '[build] guimsgprobe.jar OK'
