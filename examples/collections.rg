/#
  Arrays, maps, and the built-in len helper.
#/
fn main(): Int {
    var xs = [1, 2, 3];
    print(xs[0]);
    xs.push(4);
    xs[1] = 9;
    print(len(xs));
    print(xs.pop());

    var scores = {"ada": 10, "grace": 12};
    scores["linus"] = 9;
    print(scores["grace"]);
    print(scores.has("ada"));
    print(len(scores));
    return 0;
}
