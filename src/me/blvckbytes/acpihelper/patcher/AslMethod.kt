package me.blvckbytes.acpihelper.patcher

class AslMethod(
        val startLine: Int,
        val endLine: Int,
        val indentation: Int,
        val name: String,
        val scope: AslScope,
        val body: String,
        val affectedFields: MutableMap<EcField, List<EcField>?>,
) {
    // Format this toString multiline, because the affectedFields and the body may be quite long
    // Also, collect all split fields corresponding to this method into one array to display
    override fun toString(): String {
        val fieldNames = affectedFields.keys.map { it.name }
        return "AslMethod(startLine=$startLine,endLine=$endLine,indentation=$indentation,scope=${scope.toValue()},name=$name,affectedFields=$fieldNames)"
    }

    fun toFullString(): String {
        val fieldNames = affectedFields.keys.map { it.name }
        return "AslMethod(\nstartLine=$startLine,\nendLine=$endLine,\nindentation=$indentation,\nscope=${scope.toValue()},\nname=$name,\naffectedFields=$fieldNames,\nbody=\n$body\n)"
    }
}
