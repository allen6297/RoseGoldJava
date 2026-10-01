/#
  Nested modules in one file. Hover and go-to-def work on add / double.
#/
mod math {
    pub fn add(a: Int, b: Int): Int {
        return a + b;
    }

    pub fn double(n: Int): Int {
        return add(n, n);
    }
}

fn main(): Int {
    print(math.add(2, 3));
    print(math.double(4));
    return 0;
}
