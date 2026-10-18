class Player extends Node2D {
    var speed: Float = 200.0;
    signal died();

    fn _ready() {
        print(speed);
    }

    fn _process(delta: Float) {
        position.x = position.x + speed * delta;
    }
}
