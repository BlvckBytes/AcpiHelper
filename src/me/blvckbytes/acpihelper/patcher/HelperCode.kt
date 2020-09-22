package me.blvckbytes.acpihelper.patcher

enum class HelperCode(private val code: String) {

    // Combine two 8 bit fields into a 16 bit value
    B1B2("""
        Method (B1B2, 2, NotSerialized)
        {
            Return (Or (Arg0, ShiftLeft (Arg1, 0x08)))
        }
    """.trimIndent()),

    // Combine four 8 bit fields into a 32 bit value
    B1B4("""
        Method (B1B4, 4, NotSerialized)
        {
            Store(Arg3, Local0)
            Or(Arg2, ShiftLeft(Local0, 8), Local0)
            Or(Arg1, ShiftLeft(Local0, 8), Local0)
            Or(Arg0, ShiftLeft(Local0, 8), Local0)
            Return(Local0)
        }
    """.trimIndent()),

    // Read one byte from the embedded control at a given offset
    RE1B("""
        Method (RE1B, 1, NotSerialized)
        {
            OperationRegion(ERAM, EmbeddedControl, Arg0, 1)
            Field(ERAM, ByteAcc, NoLock, Preserve) { BYTE, 8 }
            Return(BYTE)
        }
    """.trimIndent()),

    // Reads as many bits from the EC buffer as passed arg1, from the zero-based offset arg0
    RECB("""
        Method (RECB, 2, Serialized)
        {
            ShiftRight(Add(Arg1,7), 3, Arg1)
            Name(TEMP, Buffer(Arg1) { })
            Add(Arg0, Arg1, Arg1)
            Store(0, Local0)
            While (LLess(Arg0, Arg1))
            {
                Store(RE1B(Arg0), Index(TEMP, Local0))
                Increment(Arg0)
                Increment(Local0)
            }
            Return(TEMP)
        }
    """.trimIndent())

    // Terminate enum definitions
    ;

    // ToString should always return the defined code above
    override fun toString(): String {
        return code
    }
}
