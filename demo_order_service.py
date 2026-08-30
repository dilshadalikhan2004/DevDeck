import sys
import time

def calculate_discounted_price(base_price: float, discount_percent: float) -> float:
    """Applies a percentage discount to a base price."""
    return base_price * (1 - (discount_percent / 100))

def process_order(order_data: dict) -> float:
    print(f"[Order] Processing order ID: {order_data.get('order_id', 'UNKNOWN')}")
    
    items = order_data.get("items", [])
    total_amount = sum(item["price"] * item["qty"] for item in items)
    
    # Bug: Customer account tier may be missing or None for guest checkout
    user_tier = order_data.get("tier")
    
    # Faulty line: calling .upper() directly on user_tier which is None
    tier_name = user_tier.upper() if user_tier else None
    print(f"[Order] Applying tier privileges for: {tier_name}")
    
    discount = 15.0 if tier_name == "VIP" else 5.0
    final_price = calculate_discounted_price(total_amount, discount)
    return final_price

def main():
    print("[Service] Starting e-commerce transaction worker...")
    time.sleep(0.2)
    
    guest_order = {
        "order_id": "ORD-94821",
        "items": [
            {"name": "Mechanical Keyboard", "price": 89.99, "qty": 1},
            {"name": "USB-C Cable", "price": 12.50, "qty": 2}
        ],
        "tier": None # Guest checkout has no tier assigned
    }
    
    print("[Service] Computing order invoice...")
    final_total = process_order(guest_order)
    print(f"[Success] Order completed! Total due: ${final_total:.2f}")

if __name__ == "__main__":
    main()
