signal collected(amount: Int);

struct Coin {
    signal grabbed(amount: Int);

    fn grab(n: Int) {
        grabbed.emit(n);
    }
}

fn log_coin(amount: Int) {
    print(amount);
}

fn main(): Int {
    collected.connect(log_coin);
    collected.emit(5);

    var c = Coin {};
    c.grabbed.connect(fn (amount: Int) { print(amount); });
    c.grab(3);
    return 0;
}
