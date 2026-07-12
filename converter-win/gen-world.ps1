# gen-world.ps1 — generate a 26.2 test world headlessly with the loom-cached dedicated server jar.
# Output: converter-win\saves\LBWorld  (client-openable; MC upgrades/opens server worlds fine)
$ErrorActionPreference = 'Stop'
$Java = 'C:\Users\Raphael\.jdks\graalvm-ce-25.0.2\bin\java.exe'
$ServerJar = "$env:USERPROFILE\.gradle\caches\fabric-loom\26.2\minecraft-server.jar"
$Work = Join-Path $PSScriptRoot 'server-gen'

if (Test-Path $Work) { Remove-Item $Work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Work | Out-Null

Set-Content -Path (Join-Path $Work 'eula.txt') -Value 'eula=true' -Encoding ascii
@'
level-name=LBWorld
level-seed=1234567
gamemode=creative
difficulty=peaceful
online-mode=false
spawn-protection=0
view-distance=6
max-players=1
motd=lb-test-world
enable-rcon=false
'@ | Set-Content -Path (Join-Path $Work 'server.properties') -Encoding ascii

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $Java
$psi.Arguments = '-Xmx2g -jar "' + $ServerJar + '" nogui'
$psi.WorkingDirectory = $Work
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$p = [System.Diagnostics.Process]::Start($psi)
Write-Host "[gen] server pid $($p.Id)"

$log = Join-Path $Work 'gen.log'
$done = $false
$sw = [System.Diagnostics.Stopwatch]::StartNew()
while (-not $p.HasExited -and $sw.Elapsed.TotalSeconds -lt 420) {
    $line = $p.StandardOutput.ReadLine()
    if ($null -eq $line) { break }
    Add-Content -Path $log -Value $line
    if ($line -match 'Done \(' ) { $done = $true; break }
}
if ($done) {
    Write-Host '[gen] server up; issuing stop'
    $p.StandardInput.WriteLine('stop')
    $p.StandardInput.Flush()
    # drain remaining output so the process can exit
    while (-not $p.HasExited) {
        $line = $p.StandardOutput.ReadLine()
        if ($null -eq $line) { break }
        Add-Content -Path $log -Value $line
    }
    $p.WaitForExit(60000) | Out-Null
}
if (-not $p.HasExited) { $p.Kill(); Write-Host '[gen] FORCED KILL (timeout)'; exit 1 }
Write-Host "[gen] server exited rc=$($p.ExitCode)"

$world = Join-Path $Work 'LBWorld'
if (-not (Test-Path (Join-Path $world 'level.dat'))) { Write-Host '[gen] FAIL: no level.dat'; exit 1 }
$dest = Join-Path $PSScriptRoot 'saves\LBWorld'
if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
Copy-Item $world $dest -Recurse
# session.lock must not be carried over
Remove-Item (Join-Path $dest 'session.lock') -Force -ErrorAction SilentlyContinue
Write-Host "[gen] OK -> $dest"
