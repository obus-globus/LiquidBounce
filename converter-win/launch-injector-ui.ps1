param(
    [string]$JavaHome = 'C:\Users\Raphael\.jdks\jbrsdk_jcef-25.0.3',
    [string]$AgentJar = (Join-Path $PSScriptRoot '..\build\full-agent-schemafix\full-agent.jar')
)

$ErrorActionPreference = 'Stop'
$javac = Join-Path $JavaHome 'bin\javac.exe'
$javaw = Join-Path $JavaHome 'bin\javaw.exe'
$out = Join-Path $PSScriptRoot 'out'

if (-not (Test-Path -LiteralPath $javac)) { throw "javac.exe not found: $javac" }
if (-not (Test-Path -LiteralPath $javaw)) { throw "javaw.exe not found: $javaw" }
if (-not (Test-Path -LiteralPath $AgentJar)) { throw "Agent JAR not found: $AgentJar" }

New-Item -ItemType Directory -Force -Path $out | Out-Null
& $javac -d $out (Join-Path $PSScriptRoot 'Injector.java') (Join-Path $PSScriptRoot 'InjectorUi.java')
if ($LASTEXITCODE -ne 0) { throw "Injector compilation failed (exit code $LASTEXITCODE)." }

Start-Process -FilePath $javaw -ArgumentList @('-cp', $out, 'InjectorUi', (Resolve-Path -LiteralPath $AgentJar).Path)
