package me.blvckbytes.acpihelper.patcher

class AslMethod(
        val startLine: Int,
        val endLine: Int,
        val indentation: Int,
        val scope: String,
        val body: String,
        val affectedFields: Map<EcField, List<EcField>?>,
) {
    // Format this toString multiline, because the affectedFields and the body may be quite long
    // Also, collect all split fields corresponding to this method into one array to display
    override fun toString(): String {
        val fieldNames = affectedFields.keys.map { it.name }
        return "AslMethod(\nstartLine=$startLine,\nendLine=$endLine,\nindentation=$indentation\naffectedFields=$fieldNames,\nbody=\n$body\n)"
    }
}
