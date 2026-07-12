<#
  run-menu.ps1 — Windows port of converter-core/run-menu2world.sh (the menu -> attach -> enter-world harness).

  WHAT THE LINUX SCRIPT DID (and the Windows equivalent):
    Linux                                             Windows (this script)
    -----                                             ---------------------
    export DISPLAY=:121 / Xvfb                         N/A — Windows has a real desktop; MC opens a real window.
    pkill -9 -f 'gameDir /tmp/fullmc'                  Stop-Process on a tracked PID (we do NOT pkill by cmdline).
    CI=1 (suppress LB's blocking error dialog)         $env:CI = '1'  (still honored by LB's ErrorHandler).
    XDG_RUNTIME_DIR / WAYLAND_DISPLAY unset            N/A on Windows.
    MC_CP=$(paste -sd: mc-cp.txt)   (':' separator)    ($lines -join ';')  — Windows classpath separator is ';'.
    java ... net.minecraft.client.main.Main &          Start-Process -PassThru (detached, persistent; keeps PID).
    JPID via pgrep + /proc/$p/comm=java                $mc.Id from Start-Process -PassThru (exact, no guessing).
    java -cp .../out Injector $JPID full-agent.jar      same Injector (see converter-win\Injector.java).
    import -window root  (screenshot)                  Add-Type / graphics capture (see Capture-Window helper).

  PERSISTENCE: Start-Process -PassThru launches MC as an independent process that OUTLIVES this script.
  This script never kills MC on exit (matching the "persistent MC" rule in 04-environment-gotchas.md).
  To stop it later:  Stop-Process -Id <pid>.

  >>> FILL IN THE TODO PATHS BELOW (the baseline-repro step resolves these against the real staged instance). <<<
#>

$ErrorActionPreference = 'Stop'

# =====================================================================================================
# CONFIG — machine-specific. TODO: the baseline-repro step confirms/overrides these.
# =====================================================================================================

# --- JDK 25 (REQUIRED; the repo/mixins need JAVA_25). VERIFIED 2026-07-12: -------------------------
# MC MUST run on a JDK 25 WITHOUT a built-in jcef module. The JBR 'jbrsdk_jcef-25.0.3' ships org.cef.*
# as a platform module (jrt:/jcef) which SHADOWS MCEF's bundled JCEF -> NoSuchMethodError
# CefApp.getInstance(String[],CefSettings) and a client crash (see evidence\crash-jbrjcef-cefapp.txt).
# GraalVM CE 25 (plain HotSpot-based, no jcef) works. JBR is still fine for javac/Injector.
$JavaExe = 'C:\Users\Raphael\.jdks\graalvm-ce-25.0.2\bin\java.exe'                 # MC's JVM (no jcef module!)
$InjectorJava = 'C:\Users\Raphael\.jdks\jbrsdk_jcef-25.0.3\bin\java.exe'           # JVM for the attach injector

# --- Minecraft version + loom cache -----------------------------------------------------------------
# 26.2 (index 32) — the full-agent mixins are compiled against 26.2; do NOT fall back to 26.1.2.
$McVersion  = '26.2'
$AssetIndex = '32'                # staged locally: converter-win\assets\indexes\32.json (5057/5057 objects)
$LoomRoot   = Join-Path $env:USERPROFILE '.gradle\caches\fabric-loom'

# --- Bare-vanilla MC classpath ----------------------------------------------------------------------
# mc-cp.txt EXISTS (generated 2026-07-12): 71 entries = 70 libs from the 26.2 version manifest
# (fabric-loom\26.2\mojang_minecraft_info.json, keeping non-natives + '-natives-windows.jar', resolved
# by basename from modules-2\files-2.1) + the loom Mojmap client jar. Only java-objc-bridge (macOS) is absent.
$McCpFile = Join-Path $PSScriptRoot 'mc-cp.txt'
$McClientJar = Join-Path $LoomRoot "$McVersion\minecraft-client.jar"   # at minimum this must be on the cp

# --- Assets + game dir + world ----------------------------------------------------------------------
# converter-win\assets: indexes\32.json fetched from piston-meta (assetIndex.url in the version manifest);
# objects\ is a junction to the loom shared objects store (all 5057 objects of index 32 present).
$AssetsDir = Join-Path $PSScriptRoot 'assets'                          # staged 26.2 assets (indexes\32.json + objects junction)
$RunRoot   = Join-Path $env:TEMP 'fullmc'                              # Windows analogue of /tmp/fullmc
$GameDir   = Join-Path $RunRoot 'gamedir'
# TODO: a ready-made test world to copy into $GameDir\saves\ (the Linux harness copied "New World").
#       If you have none, MC boots to the menu fine; world-entry (enterworld.jar) needs a world to exist.
$SeedWorld = Join-Path $PSScriptRoot 'saves\LBWorld'                   # generated headlessly by gen-world.ps1 (26.2 dedicated server)

# --- Agent + injector artifacts (built by the OTHER agents / assembly step) --------------------------
$FullAgentJar  = Join-Path $PSScriptRoot '..\build\full-agent\full-agent.jar'     # the converter agent (Agent-Class: FullInjectAgent)
$InjectorOut   = Join-Path $PSScriptRoot 'out'                                    # javac -d out Injector.java
$EnterWorldJar = Join-Path $PSScriptRoot '..\build\enterworld\enterworld.jar'     # optional: programmatic world entry
$OpenGuiJar    = Join-Path $PSScriptRoot '..\build\opengui\opengui.jar'           # optional: enable render modules

$Width = 1280; $Height = 800

# =====================================================================================================
# 1. Prep run dir + options (mirrors the Linux options.txt + saves copy)
# =====================================================================================================
$env:CI = '1'   # suppress LB's blocking native error dialog (ErrorHandler.fatal -> log instead of modal)

if (Test-Path $RunRoot) { Remove-Item $RunRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $GameDir 'saves') | Out-Null
@'
onboardAccessibility:false
narrator:0
renderDistance:6
simulationDistance:5
pauseOnLostFocus:false
'@ | Set-Content -Path (Join-Path $GameDir 'options.txt') -Encoding ascii

if ($SeedWorld -and (Test-Path $SeedWorld)) {
    Copy-Item $SeedWorld (Join-Path $GameDir 'saves') -Recurse -Force
    Write-Host "[run] seeded world: $(Split-Path $SeedWorld -Leaf)"
} else {
    Write-Host "[run] WARNING: no SeedWorld set — MC will reach the menu, but enterworld.jar needs a world."
}

# =====================================================================================================
# 2. Build the MC classpath (';' separated on Windows)
# =====================================================================================================
if (Test-Path $McCpFile) {
    $cpEntries = Get-Content $McCpFile | Where-Object { $_.Trim() -ne '' }
} else {
    Write-Host "[run] TODO: $McCpFile missing — falling back to client jar ONLY (will NOT boot without MC libs)."
    $cpEntries = @($McClientJar)
}
$McCp = ($cpEntries -join ';')

# =====================================================================================================
# 3. Launch MC to the MENU, detached + persistent (Start-Process -PassThru => exact PID, outlives script)
# =====================================================================================================
$LogF = Join-Path $RunRoot ("fullmc-{0}.log" -f ([DateTimeOffset]::Now.ToUnixTimeSeconds()))
$mcArgs = @(
    '-Xmx2g', '-cp', $McCp, 'net.minecraft.client.main.Main',
    '--username', 'Dev', '--version', $McVersion, '--gameDir', $GameDir,
    '--assetsDir', $AssetsDir, '--assetIndex', $AssetIndex,
    '--accessToken', '0', '--uuid', '00000000000000000000000000000000',
    '--userType', 'legacy', '--width', $Width, '--height', $Height
)
Write-Host "[run] launching MC ($McVersion) to menu; log -> $LogF"
$mc = Start-Process -FilePath $JavaExe -ArgumentList $mcArgs `
        -RedirectStandardOutput $LogF -RedirectStandardError "$LogF.err" `
        -PassThru -WindowStyle Normal
$JPID = $mc.Id
Write-Host "[run] MC PID = $JPID  (persistent; stop later with: Stop-Process -Id $JPID)"

# =====================================================================================================
# 4. Wait for MC to reach the menu (poll the log for a backend/narrator marker)
# =====================================================================================================
function Wait-ForLog([string]$path, [string]$pattern, [int]$tries, [int]$sleepSec) {
    for ($i = 0; $i -lt $tries; $i++) {
        if (Test-Path $path) {
            $hit = Select-String -Path $path -Pattern $pattern -Quiet -ErrorAction SilentlyContinue
            if ($hit) { return $true }
        }
        if ($mc.HasExited) { Write-Host "[run] MC exited early (code $($mc.ExitCode)) — see $LogF"; return $false }
        Start-Sleep -Seconds $sleepSec
    }
    return $false
}
if (-not (Wait-ForLog $LogF 'Backend library|Narrator library|Reloading ResourceManager' 60 3)) {
    Write-Host "[run] menu marker not seen; check $LogF (continuing anyway)"
}
Start-Sleep -Seconds 12   # let the menu settle (matches Linux 'sleep 12')

# =====================================================================================================
# 5. Attach full-agent.jar (converter path) — LB initializes calmly at the menu
# =====================================================================================================
Write-Host "[run] ATTACH full-agent -> pid $JPID"
& $InjectorJava -cp $InjectorOut Injector $JPID (Resolve-Path $FullAgentJar) "" 2>&1 |
    Tee-Object -FilePath (Join-Path $RunRoot 'attach.log')
Write-Host "[run] attach rc=$LASTEXITCODE"

# wait for LB to finish initializing at the menu
Wait-ForLog $LogF 'successfully initialized|An error occurred|Game crashed' 50 3 | Out-Null
Start-Sleep -Seconds 6

# =====================================================================================================
# 6. (OPTIONAL) enter a world, then enable render modules — same pattern as the Linux harness
# =====================================================================================================
if ((Test-Path $EnterWorldJar) -and $SeedWorld) {
    Write-Host "[run] ENTERWORLD"
    & $InjectorJava -cp $InjectorOut Injector $JPID (Resolve-Path $EnterWorldJar) (Split-Path $SeedWorld -Leaf) 2>&1 |
        Tee-Object -FilePath (Join-Path $RunRoot 'enterworld.log')
    Wait-ForLog $LogF 'advancements|Time elapsed|Changing dimension|Preparing spawn|Game crashed' 60 3 | Out-Null
    Start-Sleep -Seconds 25
}
if (Test-Path $OpenGuiJar) {
    $mods = 'net.ccbluex.liquidbounce.features.module.modules.render.ModuleTracers,' +
            'net.ccbluex.liquidbounce.features.module.modules.render.esp.ModuleESP,' +
            'net.ccbluex.liquidbounce.features.module.modules.render.nametags.ModuleNametags,' +
            'net.ccbluex.liquidbounce.features.module.modules.render.ModuleFullBright'
    Write-Host "[run] enable render modules"
    & $InjectorJava -cp $InjectorOut Injector $JPID (Resolve-Path $OpenGuiJar) $mods 2>&1 |
        Tee-Object -FilePath (Join-Path $RunRoot 'opengui.log')
    Start-Sleep -Seconds 12
}

Write-Host "[run] DONE. MC alive = $(-not $mc.HasExited).  PID $JPID.  Log: $LogF"
Write-Host "[run] To screenshot the LB window, capture by window handle (see PORT-NOTES.md 'Screenshot on Windows')."
