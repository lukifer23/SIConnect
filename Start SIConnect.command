#!/bin/zsh
set -eu
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
cd "${0:A:h}"
python3 tools/start_builtin.py
printf '\nPress Return to close. '
read -r
