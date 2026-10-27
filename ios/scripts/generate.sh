#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v xcodegen >/dev/null || { echo 'Install XcodeGen with: brew install xcodegen'; exit 1; }
xcodegen generate --spec project.yml
