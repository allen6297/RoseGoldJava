from ui import Color;

pub mod test{
    abstract class animal {
        fn makenoise(){pass;}
    }
    /#
    this is a bird
    ---
    #/
    //TODO: better import for abstract methods
    class bird extends animal {
        fn makenoise() {
            print("caw");
        }
    }
}

enum te {
    one,
    three,
    two
}

fn main(){
    var a:te = te.one;
    a = te.two;
    var b : Color = Color.Green;

    switch a {
        one { print("one");}
        two {print("two");}
        _ { pass; }
}
}