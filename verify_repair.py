# verify_repair.py
import os
import time

filename = "target_bug.py"

# 1. Create a broken file (Absolute path to avoid AI confusion)
abs_filename = os.path.abspath(filename)
with open(abs_filename, "w") as f:
    f.write("def greet(name):\n    print('Hello ' + name)\n\ngreet(None)")

print(f"[1] Created {abs_filename} with a bug.")
print("[2] Running it through DevDeck...")

# 2. Run it through devdeck
os.system(f'python devdeck.py run "python {filename}"')

print("\n[3] ERROR SENT! Check your phone.")
print("    - Look for the 'Apply autonomous repair' button.")

# 3. Wait and check if it got fixed
print("[4] Monitoring for fixes...")
initial_content = open(abs_filename, "r").read()

for i in range(60):
    time.sleep(1)
    if os.path.exists(abs_filename):
        with open(abs_filename, "r") as f:
            content = f.read()

            # AGENTIC CHECK: Did the content change?
            if content != initial_content:
                print(f"\n[Agent] Change detected! Verifying execution...")
                # Run the code and check if it succeeds (exit code 0)
                status = os.system(f"python {abs_filename}")
                if status == 0:
                    print(f"\n[SUCCESS] THE AGENT FIXED THE CODE AND IT RUNS!")
                    print("Fixed Content:\n" + content)
                    break
                else:
                    print("[Agent] Code modified but still failing. Waiting for next attempt...")
                    initial_content = content
                    print("New code content:")
                    print("----------------")
                    print(content)
                    print("----------------")
                    break
    if i == 59:
        print("\n[TIMEOUT] No fix detected after 60 seconds.")
