#!/usr/bin/env bash
echo "==================================================="
echo "  Starting DevDeck Relay Bridge"
echo "==================================================="
if command -v python3 &>/dev/null; then
    python3 relay_server.py
else
    python relay_server.py
fi
