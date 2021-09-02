// FIR_IDENTICAL
class A(val a: A) {
    <!NO_TAIL_CALLS_FOUND!>tailrec fun foo1()<!> {
        a.<!NON_TAIL_RECURSIVE_CALL!>foo1<!>()
    }

    tailrec fun foo2() {
        this.foo2()
    }

    tailrec fun foo3() {
        foo3()
    }
}
