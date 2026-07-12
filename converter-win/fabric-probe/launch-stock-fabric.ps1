# launch-stock-fabric.ps1 — start a STOCK Fabric 26.2 client (Mojmap/dev namespace, no LiquidBounce mod)
# by calling KnotClient directly with loom's captured dev recipe. Persistent (outlives this script).
# Writes the PID to fabric.pid and the game log to logs\stock-fabric.log.
$ErrorActionPreference = 'Stop'
$here = "C:/Dev/ProjectsSorted/LiquidBounceSpecial/LiquidBounce/converter-win/fabric-probe"
$JavaExe = 'C:/Users/Raphael/.jdks/graalvm-ce-25.0.2/bin/java.exe'   # no jcef module (MCEF-safe)

# game dir (own temp so we don't touch the repo)
$gameDir = Join-Path $env:TEMP 'fabricprobe/gamedir'
New-Item -ItemType Directory -Force -Path (Join-Path $gameDir 'saves') | Out-Null
@'
onboardAccessibility:false
narrator:0
pauseOnLostFocus:false
'@ | Set-Content -Path (Join-Path $gameDir 'options.txt') -Encoding ascii

# classpath (forward slashes so the java @argfile doesn't treat '\' as an escape)
$cp = (Get-Content (Join-Path $here 'stock-cp.txt') | Where-Object { $_.Trim() -ne '' } |
        ForEach-Object { $_.Replace('\','/') }) -join ';'
$gd = $gameDir.Replace('\','/')

$argfile = Join-Path $here 'knot.args'
$lines = @(
  '-Xmx2g'
  '-Dfile.encoding=UTF-8'
  '-Duser.country=GB'
  '-Duser.language=en'
  '--sun-misc-unsafe-memory-access=allow'
  '--enable-native-access=ALL-UNNAMED'
  '-Dfabric.development=true'
  '-Dfabric.defaultModDistributionNamespace=official'
  '-Dfabric.defaultMixinRemapType=static'
  '-Dlog4j2.formatMsgNoLookups=true'
  '-cp'
  ('"' + $cp + '"')
  'net.fabricmc.loader.impl.launch.knot.KnotClient'
  '--assetIndex'; '26.2-32'
  '--assetsDir'; 'C:/Users/Raphael/.gradle/caches/fabric-loom/assets'
  '--gameDir'; $gd
  '--username'; 'Dev'
  '--uuid'; '00000000000000000000000000000000'
  '--accessToken'; '0'
  '--userType'; 'legacy'
  '--version'; '26.2'
  '--width'; '1280'
  '--height'; '800'
)
$lines | Set-Content -Path $argfile -Encoding ascii

$env:CI = '1'
$log = Join-Path $here 'logs/stock-fabric.log'
$proc = Start-Process -FilePath $JavaExe -ArgumentList "`"@$argfile`"" `
          -WorkingDirectory $here `
          -RedirectStandardOutput $log -RedirectStandardError "$log.err" `
          -PassThru -WindowStyle Normal
$proc.Id | Set-Content -Path (Join-Path $here 'fabric.pid')
Write-Host "[stock] launched KnotClient PID=$($proc.Id) log=$log"
