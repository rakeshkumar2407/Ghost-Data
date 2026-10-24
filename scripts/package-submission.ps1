$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("ghostdata-submission-" + [guid]::NewGuid().ToString("N"))
$archive = Join-Path $root "code.zip"
$entries = @(
    @{ Source = "README.md"; Destination = "README.md" },
    @{ Source = "code\README.md"; Destination = "code\README.md" },
    @{ Source = "backend\pom.xml"; Destination = "code\backend\pom.xml" },
    @{ Source = "backend\src"; Destination = "code\backend\src" },
    @{ Source = "frontend\index.html"; Destination = "code\frontend\index.html" },
    @{ Source = "frontend\package.json"; Destination = "code\frontend\package.json" },
    @{ Source = "frontend\package-lock.json"; Destination = "code\frontend\package-lock.json" },
    @{ Source = "frontend\tsconfig.json"; Destination = "code\frontend\tsconfig.json" },
    @{ Source = "frontend\vite.config.ts"; Destination = "code\frontend\vite.config.ts" },
    @{ Source = "frontend\src"; Destination = "code\frontend\src" },
    @{ Source = "scripts\validate-output.ps1"; Destination = "code\scripts\validate-output.ps1" },
    @{ Source = "evaluation\evaluation_report.md"; Destination = "code\evaluation\evaluation_report.md" },
    @{ Source = "evaluation\usage_report.md"; Destination = "code\evaluation\usage_report.md" }
)

try {
    New-Item -ItemType Directory -Path $stage | Out-Null
    foreach ($entry in $entries) {
        $source = Join-Path $root $entry.Source
        if (-not (Test-Path -LiteralPath $source)) {
            throw "Required submission file is missing: $($entry.Source)"
        }
        $destination = Join-Path $stage $entry.Destination
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
