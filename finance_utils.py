# finance_utils.py
TAX_RATE = 0.08

def calculate_final_price(subtotal):
    """Applies a standard 8% tax to the subtotal."""
    return subtotal * (1 + TAX_RATE)

def format_currency(amount):
    return f"${amount:,.2f}"
