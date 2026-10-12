# SamCDP - Windows one-line installer (PowerShell)
# Usage: irm https://raw.githubusercontent.com/samaidev/samcdp/main/install.ps1 | iex

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "=======================================" -ForegroundColor Cyan
Write-Host "      SamCDP Installer (Windows)       " -ForegroundColor Cyan
Write-Host "=======================================" -ForegroundColor Cyan
Write-Host ""

$InstallDir = "$env:LOCALAPPDATA\SamCDP"
$ConfigDir = "$env:APPDATA\cdpclient"

# 1. Check Go
Write-Host "[1/5] Checking dependencies..." -ForegroundColor Yellow
$goInstalled = $false
try {
    $goVersion = go version 2>&1
    Write-Host "  Go found: $goVersion"
    $goInstalled = $true
} catch {
    Write-Host "  Go not found. Please install Go 1.23+ from https://go.dev/dl/" -ForegroundColor Red
    Write-Host "  After installing Go, re-run this script." -ForegroundColor Red
    exit 1
}

# 2. Clone repository
Write-Host ""
Write-Host "[2/5] Cloning SamCDP repository..." -ForegroundColor Yellow
$tmpDir = "$env:TEMP\samcdp-src"
if (Test-Path $tmpDir) { Remove-Item $tmpDir -Recurse -Force }
git clone --depth 1 https://github.com/samaidev/samcdp.git $tmpDir 2>&1 | Out-Null
Write-Host "  Repository cloned" -ForegroundColor Green

# 3. Build
Write-Host ""
Write-Host "[3/5] Building SamCDP..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Push-Location $tmpDir
go build -ldflags "-H windowsgui" -o "$InstallDir\cdpclient.exe" . 2>&1
Pop-Location
Write-Host "  Built: $InstallDir\cdpclient.exe" -ForegroundColor Green

# 4. Download aitun-client
Write-Host ""
Write-Host "[4/5] Downloading aitun-client..." -ForegroundColor Yellow
$aitunPath = "$InstallDir\aitun-client.exe"
if (-not (Test-Path $aitunPath)) {
    try {
        Invoke-WebRequest -Uri "https://aitun.cc/downloads/aitun-client-windows-amd64.exe" -OutFile $aitunPath -UseBasicParsing
        Write-Host "  aitun-client downloaded" -ForegroundColor Green
    } catch {
        Write-Host "  Warning: Could not download aitun-client. Please download manually." -ForegroundColor DarkYellow
    }
} else {
    Write-Host "  aitun-client already exists, skipping" -ForegroundColor Green
}

# 5. Create default config
Write-Host ""
Write-Host "[5/5] Creating default config..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
$configPath = "$ConfigDir\config.json"
if (-not (Test-Path $configPath)) {
    # Generate random auth token
    $bytes = New-Object byte[] 18
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $authToken = [System.Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    
    $config = @{
        aitun_key = ""
        auth_token = $authToken
        lang = "zh"
        profiles = @(
            @{ name = "agent-1"; port = 9334; enabled = $true },
            @{ name = "agent-2"; port = 9335; enabled = $true },
            @{ name = "agent-3"; port = 9336; enabled = $true }
        )
    }
    $config | ConvertTo-Json -Depth 5 | Out-File -FilePath $configPath -Encoding UTF8
    Write-Host "  Config created: $configPath" -ForegroundColor Green
    Write-Host "  Auth token: $authToken" -ForegroundColor Cyan
} else {
    Write-Host "  Config already exists, skipping" -ForegroundColor Green
}

# Cleanup
Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=======================================" -ForegroundColor Green
Write-Host " SamCDP installed successfully!" -ForegroundColor Green
Write-Host "=======================================" -ForegroundColor Green
Write-Host ""
Write-Host "Binary:  $InstallDir\cdpclient.exe" -ForegroundColor White
Write-Host "Config:  $configPath" -ForegroundColor White
Write-Host ""
Write-Host "Next steps:" -ForegroundColor Cyan
Write-Host "  1. Edit config.json and add your aitun key" -ForegroundColor White
Write-Host "  2. Run: $InstallDir\cdpclient.exe" -ForegroundColor White
Write-Host ""
