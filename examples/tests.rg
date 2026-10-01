/#
  @test functions. Gutter Run Test or ./gradlew rg --args="test examples/tests.rg".
#/
@test
fn add() {
    assert(2 + 2 == 4);
}

@test
fn len_array() {
    assert(len([1, 2, 3]) == 3);
}

fn main(): Int {
    return 0;
}
