# checkout_service.py
from models import CartItem
import finance_utils

def process_checkout(items):
    total = sum(item.get_subtotal() for item in items)

    # BUG: Typo! 'apply_tax_logic' does not exist.
    # The AI must find 'calculate_final_price' in the repo context to fix this.
    final = finance_utils.calculate_final_price(total)

    print(f"Checkout successful: {finance_utils.format_currency(final)}")

# Setup test data
cart = [
    CartItem("Laptop", 1200, 1),
    CartItem("Mouse", 25, 2)
]

process_checkout(cart)
