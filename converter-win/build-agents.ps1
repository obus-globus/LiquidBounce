# build-agents.ps1 — rebuild full-agent.jar from converter-win\src (instrumented copies of the docs converter-core
# sources; deviations documented in bug22 notes) + build enterworld.jar and guimsgprobe.jar.
param([string]$BuildDirName = 'full-agent')
$ErrorActionPreference = 'Stop'
$Jdk   = 'C:\Users\Raphael\.jdks\jbrsdk_jcef-25.0.3\bin'
$Repo  = Split-Path $PSScriptRoot
$Src   = Join-Path $PSScriptRoot 'src'
$PA    = Join-Path $Repo 'build\agent-vanilla\liquidbounce-agent-vanilla-pure.jar'
$MC    = "$env:USERPROFILE\.gradle\caches\fabric-loom\26.2\minecraft-client.jar"
$FaDir = Join-Path $Repo (Join-Path 'build' $BuildDirName)
$Out   = Join-Path $FaDir 'out'

if (-not (Test-Path $PA)) { throw "PA jar missing: $PA" }

# --- 1. compile converter classes (FullInjectAgent + RetransformConverter + AwReflect) --------------------
if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
New-Item -ItemType Directory -Force $Out | Out-Null
& "$Jdk\javac.exe" --release 25 -cp "$PA;$MC" -d $Out `
    (Join-Path $Src 'FullInjectAgent.java') (Join-Path $Src 'RetransformConverter.java') (Join-Path $Src 'AwReflect.java')
if ($LASTEXITCODE -ne 0) { throw 'javac (converter) failed' }

# --- 2. assemble full-agent.jar: PA bundle + manifest swap + targets list + converter classes -------------
$FullJar = Join-Path $FaDir 'full-agent.jar'
Copy-Item $PA $FullJar -Force
$ManAdd = Join-Path $FaDir 'manifest-add.txt'
@'
Agent-Class: FullInjectAgent
Can-Retransform-Classes: true
Can-Redefine-Classes: true
'@ | Set-Content -Path $ManAdd -Encoding ascii
Copy-Item (Join-Path $PSScriptRoot 'lb-mixin-targets.txt') (Join-Path $FaDir 'lb-mixin-targets.txt') -Force
Push-Location $FaDir
& "$Jdk\jar.exe" ufm full-agent.jar manifest-add.txt lb-mixin-targets.txt -C out .
if ($LASTEXITCODE -ne 0) { Pop-Location; throw 'jar ufm (full-agent) failed' }
Pop-Location
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

# --- 4. guimsgprobe.jar -----------------------------------------------------------------------------------
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
