#!/bin/bash
# SamCDP - Linux one-line installer
# Usage: curl -fsSL https://raw.githubusercontent.com/samaidev/samcdp/main/install.sh | bash
set -e

INSTALL_DIR="${INSTALL_DIR:-/opt/samcdp}"
CONFIG_DIR="${HOME}/.config/cdpclient"

echo "╔══════════════════════════════════════╗"
echo "║      SamCDP Installer (Linux)        ║"
echo "╚══════════════════════════════════════╝"

# Check root or suggest sudo
if [ "$(id -u)" -ne 0 ]; then
    echo "⚠ Not running as root. Will install to ${HOME}/.local/share/samcdp"
    INSTALL_DIR="${HOME}/.local/share/samcdp"
fi

# 1. Check dependencies
echo ""
echo "[1/5] Checking dependencies..."

if ! command -v go &>/dev/null; then
    echo "✗ Go is not installed. Installing Go..."
    GO_VERSION="1.23.4"
    wget -q "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" -O /tmp/go.tar.gz
    tar -C /usr/local -xzf /tmp/go.tar.gz
    rm /tmp/go.tar.gz
    export PATH=$PATH:/usr/local/go/bin
    echo "✓ Go ${GO_VERSION} installed"
else
    echo "✓ Go $(go version) found"
fi

# 2. Clone repository
echo ""
echo "[2/5] Cloning SamCDP repository..."
rm -rf /tmp/samcdp-src
git clone --depth 1 https://github.com/samaidev/samcdp.git /tmp/samcdp-src
echo "✓ Repository cloned"

# 3. Build
echo ""
echo "[3/5] Building SamCDP..."
mkdir -p "${INSTALL_DIR}"
cd /tmp/samcdp-src
CGO_ENABLED=0 go build -o "${INSTALL_DIR}/cdpclient" .
echo "✓ Built: ${INSTALL_DIR}/cdpclient"

# 4. Download aitun-client
echo ""
echo "[4/5] Downloading aitun-client..."
if [ ! -f "${INSTALL_DIR}/aitun-client" ]; then
    wget -q -O "${INSTALL_DIR}/aitun-client" "https://aitun.cc/downloads/aitun-client-linux-amd64"
    chmod +x "${INSTALL_DIR}/aitun-client"
    echo "✓ aitun-client downloaded"
else
    echo "✓ aitun-client already exists, skipping"
fi

# 5. Create default config
echo ""
echo "[5/5] Creating default config..."
mkdir -p "${CONFIG_DIR}"
if [ ! -f "${CONFIG_DIR}/config.json" ]; then
    AUTH_TOKEN=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 24 | head -n 1)
    cat > "${CONFIG_DIR}/config.json" << EOF
{
  "aitun_key": "",
  "auth_token": "${AUTH_TOKEN}",
  "lang": "zh",
  "profiles": [
    {"name": "agent-1", "port": 9334, "enabled": true},
    {"name": "agent-2", "port": 9335, "enabled": true},
    {"name": "agent-3", "port": 9336, "enabled": true}
  ]
}
EOF
    echo "✓ Config created: ${CONFIG_DIR}/config.json"
    echo "  Auth token: ${AUTH_TOKEN}"
else
    echo "✓ Config already exists, skipping"
fi

# Cleanup
rm -rf /tmp/samcdp-src

echo ""
echo "════════════════════════════════════════"
echo "✅ SamCDP installed successfully!"
echo "════════════════════════════════════════"
echo ""
echo "Binary:     ${INSTALL_DIR}/cdpclient"
echo "Config:     ${CONFIG_DIR}/config.json"
echo ""
echo "Next steps:"
echo "  1. Edit config.json and add your aitun key"
echo "  2. Set DISPLAY if running in a desktop environment:"
echo "     export DISPLAY=:1"
echo "  3. Run: ${INSTALL_DIR}/cdpclient"
echo ""
