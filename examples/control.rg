/#
  if / elif / else, while, and for-in over arrays and ranges.
#/
fn sign(n: Int): Int {
    if n > 0 {
        return 1;
    } elif n < 0 {
        return -1;
    } else {
        return 0;
    }
}

fn main(): Int {
    print(sign(4));
    print(sign(-2));
    print(sign(0));

    var i = 0;
    var s = 0;
    while i < 4 {
        s = s + i;
        i = i + 1;
    }
    print(s);

    var sum = 0;
    for x in [1, 2, 3] {
        sum = sum + x;
    }
    print(sum);
    for n in 0..3 {
        print(n);
    }
    for n in 1..=2 {
        print(n);
    }
    return 0;
}
