#!/usr/bin/env bash
# Starts the analyst bridge again if it isn't running. Safe to call on every app start.
set -u
ARIN="/root/.arin"
PORT=8791

if (command -v curl >/dev/null 2>&1 && curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1); then
  exit 0
fi

pkill -f "mcp-bridge.mjs" >/dev/null 2>&1
sleep 1
mkdir -p "$ARIN" 2>/dev/null

if command -v node >/dev/null 2>&1; then
  setsid node "$ARIN/mcp-bridge.mjs" >>"$ARIN/analyst-mcp.log" 2>&1 < /dev/null &
fi

for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 1
  if (command -v curl >/dev/null 2>&1 && curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1); then
    exit 0
  fi
done
exit 1
