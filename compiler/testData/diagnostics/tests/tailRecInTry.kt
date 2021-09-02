// FIR_IDENTICAL
<!NO_TAIL_CALLS_FOUND!>tailrec fun foo()<!> {
    try {
        <!TAIL_RECURSION_IN_TRY_IS_NOT_SUPPORTED!>foo<!>()
    } catch (e: Exception) {
        <!TAIL_RECURSION_IN_TRY_IS_NOT_SUPPORTED!>foo<!>()
    } finally {
        <!TAIL_RECURSION_IN_TRY_IS_NOT_SUPPORTED!>foo<!>()
    }
}
