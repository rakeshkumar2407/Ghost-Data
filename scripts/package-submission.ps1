$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("ghostdata-submission-" + [guid]::NewGuid().ToString("N"))
$archive = Join-Path $root "code.zip"
$entries = @(
    "README.md",
    "docker-compose.yml",
    "backend\pom.xml",
    "backend\Dockerfile",
    "backend\src",
    "frontend\Dockerfile",
    "frontend\nginx.conf",
    "frontend\index.html",
    "frontend\package.json",
    "frontend\package-lock.json",
    "frontend\tsconfig.json",
    "frontend\vite.config.ts",
    "frontend\src",
    "dataset",
    "evaluation",
    "scripts\package-submission.ps1"
)

try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    foreach ($entry in $entries) {
        $source = Join-Path $root $entry
        if (-not (Test-Path -LiteralPath $source)) {
            throw "Required submission file is missing: $entry"
        }
        $destination = Join-Path $stage $entry
        $parent = Split-Path -Parent $destination
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
        Copy-Item -LiteralPath $source -Destination $destination -Recurse
    }
    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }
    Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $archive -CompressionLevel Optimal
    Write-Output "Created $archive"
} finally {
    if (Test-Path -LiteralPath $stage) {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}
