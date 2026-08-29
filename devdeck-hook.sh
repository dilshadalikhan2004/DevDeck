#!/usr/bin/env bash
# DevDeck Universal Shell Hook for Bash / Zsh

devdeck_check_exit() {
    local exit_code=$?
    local last_cmd
    last_cmd=$(fc -ln -1 2>/dev/null | sed 's/^[ \t]*//')

    if [ "$exit_code" -ne 0 ] && [ -n "$last_cmd" ] && [[ "$last_cmd" != *devdeck* ]]; then
        local payload
        payload=$(printf '{"command":"%s","exit_code":%d,"cwd":"%s","source":"bash_hook"}' \
            "$(echo "$last_cmd" | sed 's/"/\\"/g')" "$exit_code" "$(pwd)")

        (curl -s -m 1 -X POST http://127.0.0.1:8766/incident \
            -H "Content-Type: application/json" \
            -d "$payload" > /dev/null 2>&1 || \
         curl -s -m 1 -X POST http://127.0.0.1:8765/incident \
            -H "Content-Type: application/json" \
            -d "$payload" > /dev/null 2>&1) &
    fi
}

if [ -n "$ZSH_VERSION" ]; then
    precmd_functions+=(devdeck_check_exit)
elif [ -n "$BASH_VERSION" ]; then
    if [[ "$PROMPT_COMMAND" != *"devdeck_check_exit"* ]]; then
        PROMPT_COMMAND="devdeck_check_exit;${PROMPT_COMMAND}"
    fi
fi
