@echo off
echo ===================================================
echo   Starting DevDeck Relay Bridge
echo ===================================================
python relay_server.py
if %ERRORLEVEL% NEQ 0 (
    py relay_server.py
)
