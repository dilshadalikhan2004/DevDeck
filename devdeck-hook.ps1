# DevDeck Universal Shell Hook for PowerShell
# Automatically intercepts non-zero exit codes across PowerShell, VS Code, and Antigravity terminals.

function global:DevDeck-PromptHook {
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and $exitCode -ne $null) {
        $lastHistory = Get-History -Count 1 -ErrorAction SilentlyContinue
        $lastCmd = if ($lastHistory) { $lastHistory.CommandLine } else { "" }

        # Avoid catching devdeck CLI itself (which sends its own events) or hook prompts
        if ($lastCmd -and ($lastCmd -notmatch "(?i)devdeck") -and ($lastCmd -notlike "*DevDeck-PromptHook*")) {
            try {
                $errorMsg = ""
                if ($global:Error -and $global:Error.Count -gt 0) {
                    $errorMsg = ($global:Error[0] | Out-String).Trim()
                }

                $payload = @{
                    command    = $lastCmd
                    exit_code  = $exitCode
                    cwd        = (Get-Location).Path
                    error_text = $errorMsg
                    timestamp  = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
                    source     = "powershell_hook"
                } | ConvertTo-Json -Compress

                # HTTP Hook endpoint (Port 8766 ONLY)
                Invoke-RestMethod -Uri "http://127.0.0.1:8766/incident" `
                    -Method Post -Body $payload -ContentType "application/json" `
                    -TimeoutSec 1 -ErrorAction SilentlyContinue | Out-Null
            } catch {
                # Fail silently — never disrupt the user's terminal
            }
        }
    }
}

# Preserve existing prompt function without breaking Starship / Oh-My-Posh
if (Test-Path Function:\prompt) {
    if (-not $global:__devdeck_original_prompt) {
        $global:__devdeck_original_prompt = Get-Content Function:\prompt
    }
}

function global:prompt {
    DevDeck-PromptHook
    if ($global:__devdeck_original_prompt) {
        & $global:__devdeck_original_prompt
    } else {
        "PS $($executionContext.SessionState.Path.CurrentLocation)> "
    }
}
