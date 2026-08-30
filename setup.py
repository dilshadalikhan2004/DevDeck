from setuptools import setup

setup(
    name="devdeck",
    version="0.1.0",
    description="DevDeck • On-Device Autonomous Code Repair & Knowledge Graph Runtime",
    py_modules=[
        "devdeck",
        "relay_server",
        "repo_context",
        "patch_manager",
        "sandbox_verifier",
        "sandbox_runner",
        "repair_memory",
        "bridge_protocol",
        "bridge_security",
        "pairing_state",
        "pipeline_events",
        "subprocess_guard",
        "file_transaction",
        "git_transaction_engine",
    ],
    install_requires=[
        "websockets>=11.0",
        "qrcode>=7.4",
    ],
    entry_points={
        "console_scripts": [
            "devdeck = devdeck:cli_entry",
            "devdeck-relay = relay_server:entry_point",
        ],
    },
    python_requires=">=3.8",
)