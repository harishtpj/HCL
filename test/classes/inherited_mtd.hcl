class Foo {
  inFoo() {
    println "in foo";
  }
}

class Bar : Foo {
  inBar() {
    println "in bar";
  }
}

class Baz : Bar {
  inBaz() {
    println "in baz";
  }
}

let baz := Baz();
baz.inFoo(); # expect: in foo
baz.inBar(); # expect: in bar
baz.inBaz(); # expect: in baz