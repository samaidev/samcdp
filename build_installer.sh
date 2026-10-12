#!/bin/bash
# build_installer.sh — 构建 CDP Client 安装包
# 使用: bash build_installer.sh
# 需要: Go, rsrc, Inno Setup 6

set -e

echo "=== CDP Client Installer Build ==="

# 切换到脚本目录
cd "$(dirname "$0")"
SCRIPT_DIR="$(pwd)"
echo "Working dir: $SCRIPT_DIR"

# 1. 构建 Go 二进制
LDFLAGS="-H windowsgui"
echo ""
echo "[1/4] Building Go binary..."
go build -ldflags "$LDFLAGS" -o cdpclient.exe .
echo "  -> cdpclient.exe built"

# 2. 嵌入图标和 manifest
echo ""
echo "[2/4] Embedding icon and manifest..."
if command -v rsrc &>/dev/null; then
    rsrc -ico appicon.ico -manifest cdpclient.exe.manifest -o rsrc_windows.syso
    go build -ldflags "$LDFLAGS" -o cdpclient.exe .
    echo "  -> Icon embedded via rsrc"
elif command -v windres &>/dev/null; then
    windres -o rsrc_windows.syso cdpclient.rc
    go build -ldflags "$LDFLAGS" -o cdpclient.exe .
    echo "  -> Icon embedded via windres"
else
    echo "  WARNING: rsrc/windres not found, using external appicon.ico"
fi

# 3. 确认 aitun-client.exe 存在
echo ""
echo "[3/4] Checking aitun-client.exe..."
if [ ! -f "aitun-client.exe" ]; then
    if [ -f "bin/aitun-client.exe" ]; then
        cp bin/aitun-client.exe aitun-client.exe
        echo "  -> Copied from bin/"
    else
        echo "  WARNING: aitun-client.exe not found"
    fi
fi

# 4. 构建 Inno Setup 安装包
echo ""
echo "[4/4] Building installer with Inno Setup..."
ISCC=""
for p in \
    "/c/Program Files (x86)/Inno Setup 6/iscc.exe" \
    "/c/Program Files/Inno Setup 6/iscc.exe" \
    "$(which iscc 2>/dev/null)"; do
    if [ -x "$p" ]; then
        ISCC="$p"
        break
    fi
done

if [ -z "$ISCC" ]; then
    echo "  ERROR: Inno Setup (iscc.exe) not found!"
    echo "  Install from: https://jrsoftware.org/isdl.php"
    exit 1
fi

mkdir -p dist
"$ISCC" cdpclient.iss
echo "  -> Installer built in dist/"

# 完成
echo ""
echo "=== Build complete ==="
ls -la dist/*.exe 2>/dev/null
echo ""
echo "Installers are in: $SCRIPT_DIR/dist/"
