package me.blvckbytes.acpihelper.patcher

data class EcField(
        val offset: Int,
        val name: String,
        val size: Int
) {
    // Override the toString to format offset as a HEX-number, which will be easier for debugging with ACPI
    override fun toString(): String {
        return "EcField(offset=0x${offset.toString(16).toUpperCase()}, name=$name, size=$size)"
    }
}
