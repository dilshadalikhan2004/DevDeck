$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "          ⚡ Installing DevDeck Autonomous Runtime        " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

$installDir = "$env:LOCALAPPDATA\Programs\DevDeck"
$exePath = "$installDir\DevDeck.exe"
$downloadUrl = "https://github.com/dilshadalikhan2004/DevDeck/releases/latest/download/DevDeck.exe"

# 1. Ensure directory exists
if (!(Test-Path $installDir)) {
    New-Item -ItemType Directory -Force -Path $installDir | Out-Null
}

# 2. Download binary
Write-Host "Downloading DevDeck..." -ForegroundColor Yellow
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $downloadUrl -OutFile $exePath -UseBasicParsing
    Write-Host "Download complete!" -ForegroundColor Green
} catch {
    Write-Host "Failed to download from releases. Error: $_" -ForegroundColor Red
    exit 1
}

# 3. Add to user PATH
$userPath = [Environment]::GetEnvironmentVariable("PATH", "User")
if ($userPath -notlike "*$installDir*") {
    [Environment]::SetEnvironmentVariable("PATH", "$userPath;$installDir", "User")
    $env:Path += ";$installDir"
    Write-Host "Added DevDeck to User PATH." -ForegroundColor Green
}

Write-Host ""
Write-Host "Installation successful! Starting DevDeck..." -ForegroundColor Cyan
Write-Host ""

# 4. Launch application
Start-Process -FilePath $exePath