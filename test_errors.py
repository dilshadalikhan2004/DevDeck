import sys
import os

def trigger_type_error():
    print("[Test] Triggering Python TypeError (Concatenation with None)...")
    name = None
    print("Hello " + name)

def trigger_attribute_error():
    print("[Test] Triggering Python AttributeError (NoneType object)...")
    user = None
    if user.is_authenticated():
        print("Logged in")

def trigger_key_error():
    print("[Test] Triggering Python KeyError...")
    config = {"port": 8080}
    print(config.get("database_url", None))

def trigger_zero_division():
    print("[Test] Triggering Python ZeroDivisionError...")
    total = 100
    count = 0
    print(total / count)

def trigger_java_npe():
    print("[Test] Triggering Simulated Java NullPointerException...")
    sys.stderr.write("""Exception in thread "main" java.lang.NullPointerException
    at com.example.App.processData(App.java:42)
    at com.example.App.main(App.java:15)
""")
    sys.exit(1)

def trigger_node_missing_module():
    print("[Test] Triggering Simulated Node.js Missing Module...")
    sys.stderr.write("""Error: Cannot find module 'express'
Require stack:
- /home/user/project/index.js
    at Function.Module._resolveFilename (internal/modules/cjs/loader.js:880:15)
    at Function.Module._load (internal/modules/cjs/loader.js:725:27)
""")
    sys.exit(1)

def trigger_pytest_failure():
    print("[Test] Triggering Simulated Pytest Assertion Failure...")
    sys.stderr.write("""_________________________ test_calculate_total _________________________
def test_calculate_total():
>       assert calculate_total([10, 20]) == 35
E       AssertionError: assert 30 == 35
E         -30
E         +35
test_app.py:12: AssertionError
""")
    sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python test_errors.py <typeerror|attributeerror|keyerror|zerodiv|pytest|java|node>")
        sys.exit(1)

    error_type = sys.argv[1].lower()
    handlers = {
        "typeerror": trigger_type_error,
        "attributeerror": trigger_attribute_error,
        "keyerror": trigger_key_error,
        "zerodiv": trigger_zero_division,
        "pytest": trigger_pytest_failure,
        "java": trigger_java_npe,
        "node": trigger_node_missing_module
    }

    if error_type in handlers:
        handlers[error_type]()
    else:
        print(f"Unknown error type: {error_type}")
        print("Available types: " + ", ".join(handlers.keys()))
        sys.exit(1)
