# bootstrap.ps1 — run once from the pipeline directory to create all subdirectories
# Usage: cd pipeline; .\bootstrap.ps1

$base = Split-Path -Parent $MyInvocation.MyCommand.Path

$dirs = @(
    "shared\src\main\java\com\sreality\pipeline\shared\db",
    "shared\src\main\java\com\sreality\pipeline\shared\model",
    "shared\src\main\resources",
    "jar1-scraper\src\main\java\com\sreality\pipeline\scraper\db",
    "jar1-scraper\src\main\resources",
    "jar2-ruian\src\main\java\com\sreality\pipeline\ruian\model",
    "jar2-ruian\src\main\java\com\sreality\pipeline\ruian\extract",
    "jar2-ruian\src\main\java\com\sreality\pipeline\ruian\load",
    "jar2-ruian\src\main\resources",
    "jar3-csu\src\main\java\com\sreality\pipeline\csu\model",
    "jar3-csu\src\main\java\com\sreality\pipeline\csu\extract",
    "jar3-csu\src\main\java\com\sreality\pipeline\csu\load",
    "jar3-csu\src\main\resources",
    "jar4-enricher\src\main\java\com\sreality\pipeline\enricher\model",
    "jar4-enricher\src\main\java\com\sreality\pipeline\enricher\spatial",
    "jar4-enricher\src\main\java\com\sreality\pipeline\enricher\load",
    "jar4-enricher\src\main\resources",
    "jar5-reporter\src\main\java\com\sreality\pipeline\reporter",
    "jar5-reporter\src\main\resources",
    "airflow\dags"
)

foreach ($dir in $dirs) {
    $fullPath = Join-Path $base $dir
    New-Item -ItemType Directory -Force -Path $fullPath | Out-Null
    Write-Host "Created: $fullPath"
}

Write-Host ""
Write-Host "Done. All directories created."
Write-Host "Claude will now be able to write source files into these paths."
