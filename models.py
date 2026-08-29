# models.py
class CartItem:
    def __init__(self, name, price, qty):
        self.name = name
        self.price = price
        self.qty = qty

    def get_subtotal(self):
        return self.price * self.qty
