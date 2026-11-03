#!/data/data/com.termux/files/usr/bin/bash
# Run inside the SenzaStudio folder in Termux: bash push_termux.sh
set -e
cd "$(dirname "$0")"

git config --global user.name "SenzaProject"
git config --global user.email "dionoberutu06@gmail.com"

[ -d .git ] || git init -b main
git add -A
git update-index --chmod=+x gradlew
git commit -m "P2 Core DSP in native C++ (senzafx): biquad EQ, compressor, limiter, chain + tests" || true

git remote remove origin 2>/dev/null || true
git remote add origin https://github.com/SenzaProject/SenzaStudio.git
git branch -M main
# Password prompt = GitHub Personal Access Token (not your account password).
git push -u origin main
