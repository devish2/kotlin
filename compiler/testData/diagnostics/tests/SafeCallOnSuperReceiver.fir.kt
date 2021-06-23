// http://youtrack.jetbrains.net/issue/KT-413

open class A {
    fun f() {}
}

class B : A() {
    fun g() {
        super<!UNNECESSARY_SAFE_CALL!>?.<!>f()
    }
}
