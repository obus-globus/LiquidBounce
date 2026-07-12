# launch-bare.ps1 — boot a BARE vanilla MC 26.2 to the menu (no agent), print the PID, leave it running.
# Subset of run-menu.ps1 steps 1-4; gamedir %TEMP%\baremc to not clash with the full harness.
$ErrorActionPreference = 'Stop'
$JavaExe = 'C:\Users\Raphael\.jdks\graalvm-ce-25.0.2\bin\java.exe'
$McVersion = '26.2'; $AssetIndex = '32'
$AssetsDir = Join-Path $PSScriptRoot 'assets'
$RunRoot = Join-Path $env:TEMP 'baremc'
$GameDir = Join-Path $RunRoot 'gamedir'
$McCp = (Get-Content (Join-Path $PSScriptRoot 'mc-cp.txt') | Where-Object { $_.Trim() -ne '' }) -join ';'
$env:CI = '1'
if (Test-Path $RunRoot) { Remove-Item $RunRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $GameDir 'saves') | Out-Null
@'
onboardAccessibility:false
narrator:0
renderDistance:6
simulationDistance:5
pauseOnLostFocus:false
'@ | Set-Content -Path (Join-Path $GameDir 'options.txt') -Encoding ascii
$LogF = Join-Path $RunRoot ("baremc-{0}.log" -f ([DateTimeOffset]::Now.ToUnixTimeSeconds()))
$mcArgs = @('-Xmx2g','-cp',$McCp,'net.minecraft.client.main.Main','--username','Dev','--version',$McVersion,
    '--gameDir',$GameDir,'--assetsDir',$AssetsDir,'--assetIndex',$AssetIndex,'--accessToken','0',
    '--uuid','00000000000000000000000000000000','--userType','legacy','--width','1280','--height','800')
$mc = Start-Process -FilePath $JavaExe -ArgumentList $mcArgs -RedirectStandardOutput $LogF -RedirectStandardError "$LogF.err" -PassThru -WindowStyle Normal
Write-Host "[bare] MC PID = $($mc.Id)  log = $LogF"
for ($i=0; $i -lt 60; $i++) {
    if ($mc.HasExited) { Write-Host "[bare] MC exited early rc=$($mc.ExitCode)"; exit 1 }
    if ((Test-Path $LogF) -and (Select-String -Path $LogF -Pattern 'Backend library|Narrator library|Reloading ResourceManager' -Quiet -ErrorAction SilentlyContinue)) { break }
    Start-Sleep -Seconds 3
}
Start-Sleep -Seconds 12
Write-Host "[bare] menu ready. PID=$($mc.Id)"
