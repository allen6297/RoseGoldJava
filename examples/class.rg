/#
  Abstract base with a required speak() method.
#/
abstract class Animal {
    pub var hp: Int = 1;
    abstract fn speak(): String;
    pub fn hit(): Int {
        return hp;
    }
}

/#
  A dog. Overrides speak() and inherits hit().
#/
class Dog extends Animal {
    fn speak(): String {
        return "woof";
    }
}

final class Cat extends Animal {
    fn speak(): String {
        return "meow";
    }
}

fn main(): Int {
    var d = Dog {};
    print(d.speak());
    print(d.hit());
    print(Cat {}.speak());
    return 0;
}
