param(
    [int]$Port = 8000,
    [string]$SharedDirectory = "shared"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $projectRoot "out\classes"
$source = Join-Path $projectRoot "src\main\java\p2p\PeerServer.java"
$webDirectory = Join-Path $projectRoot "src\main\resources\web"
$shared = Join-Path $projectRoot $SharedDirectory

New-Item -ItemType Directory -Force $classes, $shared | Out-Null
javac -d $classes $source
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed."
}

& java -cp $classes p2p.PeerServer `
    --port $Port `
    --shared $shared `
    --web $webDirectory
