param(
    [int]$Port = 8000,
    [string]$SharedDirectory = "shared"
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $projectRoot "out\classes"
$sources = Get-ChildItem -Path (Join-Path $projectRoot "src\main\java\p2p") -Filter "*.java" | ForEach-Object { $_.FullName }
$webDirectory = Join-Path $projectRoot "src\main\resources\web"
$shared = Join-Path $projectRoot $SharedDirectory

$javacCmd = "javac"
if (Test-Path "C:\Program Files\Java\jdk-21\bin\javac.exe") {
    $javacCmd = "C:\Program Files\Java\jdk-21\bin\javac.exe"
}
$javaCmd = "java"
if (Test-Path "C:\Program Files\Java\jdk-21\bin\java.exe") {
    $javaCmd = "C:\Program Files\Java\jdk-21\bin\java.exe"
}

New-Item -ItemType Directory -Force $classes, $shared | Out-Null
& $javacCmd -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed."
}

& $javaCmd -cp $classes p2p.PeerServer `
    --port $Port `
    --shared $shared `
    --web $webDirectory
