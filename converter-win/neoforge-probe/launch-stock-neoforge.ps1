# launch-stock-neoforge.ps1 — start a STOCK NeoForge 26.2 client (Mojmap/dev, NO LiquidBounce) by
# replaying ModDevGradle's captured dev recipe (clientRunClasspath/VmArgs/ProgramArgs) MINUS LB's 3
# build dirs and MINUS -Dfml.modFolders, so LB is not a discovered mod. Persistent (Start-Process
# -PassThru); PID -> nf.pid, log -> logs/stock-neoforge.log. MC JVM = GraalVM CE 25 (MCEF-safe).
$ErrorActionPreference = 'Stop'
$here    = 'C:/Dev/ProjectsSorted/LiquidBounceSpecial/LiquidBounce/converter-win/neoforge-probe'
$moddev  = 'C:/Dev/ProjectsSorted/LiquidBounceSpecial/LiquidBounce/neoforge/build/moddev'
$runDir  = 'C:/Dev/ProjectsSorted/LiquidBounceSpecial/LiquidBounce/neoforge/run'
$JavaExe = 'C:/Users/Raphael/.jdks/graalvm-ce-25.0.2/bin/java.exe'

New-Item -ItemType Directory -Force -Path $runDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $runDir 'saves') | Out-Null
@'
onboardAccessibility:false
narrator:0
pauseOnLostFocus:false
tutorialStep:none
'@ | Set-Content -Path (Join-Path $runDir 'options.txt') -Encoding ascii

# --- build stock -classpath argfile: drop LB's 3 dev build dirs so LB is absent from the TCL --------
$cpLines = Get-Content (Join-Path $moddev 'clientRunClasspath.txt')
# line 0 = "-classpath", line 1 = ;-joined paths (with doubled backslashes, java-argfile escaped)
$paths = $cpLines[1] -split ';'
# Strip LB's dev build dirs (LB absent) AND the mcef jar (its MCEF.<clinit> references Sodium classes absent from
# sodium-neoforge, fatally crashing the browser stage; absent -> LB skips the browser and completes init).
$lbDirs = @('neoforge\\build\\classes\\java\\main','neoforge\\build\\classes\\kotlin\\main','neoforge\\build\\resources\\main')
$stock  = $paths | Where-Object { $p=$_; ($p -notlike '*mcef*') -and -not ($lbDirs | Where-Object { $p -like "*$_" }) }
$removed = $paths.Count - $stock.Count
$stockCp = Join-Path $here 'stock-cp.args'
@('-classpath', ($stock -join ';')) | Set-Content -Path $stockCp -Encoding ascii
Write-Host "[stock-nf] classpath: $($paths.Count) -> $($stock.Count) entries (removed $removed LB build dirs)"

$env:CI = '1'
# Skip LB's CEF/MCEF browser stage (stretch): without a working MCEF backend it fatally NoClassDefFounds on the
# render thread, aborting init. LB_BROWSER_SKIP makes BrowserBackendManager.makeDependenciesAvailable return early.
$env:LB_BROWSER_SKIP = 'true'
$log = Join-Path $here 'logs/stock-neoforge.log'
$args = @(
  "`"@$stockCp`""
  "`"@$moddev/clientRunVmArgs.txt`""
  'net.neoforged.devlaunch.Main'
  "`"@$moddev/clientRunProgramArgs.txt`""
)
$proc = Start-Process -FilePath $JavaExe -ArgumentList $args `
          -WorkingDirectory $runDir `
          -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
          -PassThru -WindowStyle Normal
$proc.Id | Set-Content -Path (Join-Path $here 'nf.pid')
Write-Host "[stock-nf] launched FML Client PID=$($proc.Id) log=$log"
