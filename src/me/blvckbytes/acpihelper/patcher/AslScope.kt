package me.blvckbytes.acpihelper.patcher

class AslScope(
        // Initial value for this scope (since it can be changed with calculations later on)
        beginValue: String,

        // Scope of the parent holding this scope instruction, used if the value if relative (using "^")
        parentScope: String? = null
) {

    private val scopeList: MutableList<String> = mutableListOf()

    init {
        // Append parent scope, if exists
        if(parentScope != null)
            scopeList.addAll(parentScope.split("."))

        // Relative path given
        if(beginValue.startsWith("^")) {
            val absoluteScope = beginValue.trimStart('^')
            val upwardsCount = beginValue.length - absoluteScope.length

            // Only walk upwards if the upwards instructions make sense
            if(upwardsCount < scopeList.size) {
                // Remove the last part of this scope as often as the upwards-instruction "^" has been set
                for (i in 0 until upwardsCount)
                    scopeList.removeLast()

                // Append to list, now that the path has been walked upwards
                scopeList.addAll(absoluteScope.split("."))
            }
        }

        else {
            // Override list, non-relative path given, if it's a path and not just a field-name
            if(beginValue.contains("."))
                scopeList.clear()

            scopeList.addAll(beginValue.split("."))
        }
    }

    override fun toString(): String {
        return "AslScope{scopeList=${toValue()}}"
    }

    fun toValue(): String {
        // Get this scopes value, just concat all list items with dots
        return scopeList.joinToString(".")
    }

    fun canReach(other: AslScope): Boolean {
        // This scope can reach another scope, if it's within it
        // Both scopes need to be absolute, in order for this to work
        return this.toValue().startsWith(other.toValue())
    }
}
