$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $projectRoot "out\classes"
$sources = Get-ChildItem -Path (Join-Path $projectRoot "src\main\java\p2p") -Filter "*.java" | ForEach-Object { $_.FullName }
$webDirectory = Join-Path $projectRoot "src\main\resources\web"

$javacCmd = "javac"
if (Test-Path "C:\Program Files\Java\jdk-21\bin\javac.exe") {
    $javacCmd = "C:\Program Files\Java\jdk-21\bin\javac.exe"
}
$javaCmd = "java"
if (Test-Path "C:\Program Files\Java\jdk-21\bin\java.exe") {
    $javaCmd = "C:\Program Files\Java\jdk-21\bin\java.exe"
}

$peers = @(
    @{ Port = 8001; Directory = "shared-peer-1" },
    @{ Port = 8002; Directory = "shared-peer-2" },
    @{ Port = 8003; Directory = "shared-peer-3" },
    @{ Port = 8004; Directory = "shared-peer-4" },
    @{ Port = 8005; Directory = "shared-peer-5" }
)

New-Item -ItemType Directory -Force $classes | Out-Null
& $javacCmd -d $classes $sources
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed."
}

foreach ($peer in $peers) {
    $directory = Join-Path $projectRoot $peer["Directory"]
    New-Item -ItemType Directory -Force $directory | Out-Null
    $arguments = @("-cp", $classes, "p2p.PeerServer", "--port", $peer["Port"],
        "--shared", $directory, "--web", $webDirectory)
    Start-Process $javaCmd -ArgumentList $arguments -WorkingDirectory $projectRoot
    Write-Host "$($peer["Directory"]) started on port $($peer["Port"])"
}
