import sys

def format_user_greeting(user_profile: dict) -> str:
    """Formats a greeting message for a user profile."""
    username = user_profile.get("name")
    
    # Bug: username is None if not provided, causing TypeError when concatenated
    message = "Welcome back, " + str(username)
    return message

def main():
    print("[System] Loading user session...")
    
    # User profile missing "name" key (simulating guest/unregistered user session)
    guest_session = {
        "user_id": 4092,
        "is_authenticated": False,
        "role": "guest"
    }
    
    print("[System] Generating session greeting...")
    greeting = format_user_greeting(guest_session)
    print(f"[Success] {greeting}")

if __name__ == "__main__":
    main()
