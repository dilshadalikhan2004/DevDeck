# crash_test.py
def divide_by_count(total, count):
    # BUG: This will crash if count is 0
    return total / count if count != 0 else 0

print(f"Result: {divide_by_count(100, 0)}")
