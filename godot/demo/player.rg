class Player extends Node2D {
	export var speed: Float = 200.0;
	export var variants: Map = {"1" : "one"};
	signal died();

	fn _ready() {
		print(speed);
	}

	fn _process(delta: Float) {
		position.x = position.x + speed * delta;
	}
	
	fn _input() {
			
	}
}

struct variant {
	
}
