#!/data/data/com.termux/files/usr/bin/bash
# Pakai:  bash push_termux.sh https://github.com/SenzaProject/SenzaStudioFX.git
set -e
URL="$1"; [ -n "$URL" ] || { echo "Pakai: bash push_termux.sh <url-repo>"; exit 1; }
cd "$(dirname "$0")"
[ -d .git ] || git init -b main
git add -A
git commit -m "libsenzastudiofx: global audio effect" || true
git remote remove origin 2>/dev/null || true
git remote add origin "$URL"
git push -u origin main
