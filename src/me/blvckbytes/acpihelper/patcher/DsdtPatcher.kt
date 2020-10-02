package me.blvckbytes.acpihelper.patcher

import java.io.File
import kotlin.text.StringBuilder

class DsdtPatcher(
        filePath: String
) {

    private var fileLines: MutableList<String> = mutableListOf()

    init {
        File(filePath).forEachLine { fileLines.add(it) }


        println("Finding all EC regions...")
        val ecMemLines = findEcMemLines()

        println("Finding all target fields > 8b...")
        val targetFields = findTargetFields(ecMemLines)

        println("Splitting fields...")
        val splittedFields = splitFields(targetFields)

        println("Finding all affected methods...")
        val targetMethodData = findTargetMethods(splittedFields)

        println("These fields have been affected (after filtering)...")
        targetMethodData.second.forEach {
            println("${it.key}:")
            it.value?.forEach { splitField ->
                println("> $splitField")
            }
            println("end of field\n")
        }

        println("Generating OperationRegion overrides from field list...")
        println(fieldMapToCode(targetMethodData.second))

        targetMethodData.first.forEach {
            println(it)
        }
    }

    private fun findEcMemLines(): Map<Int, Int> {
        val ecMemLines = mutableMapOf<Int, Int>()

        // Iterate all lines and find the ones that match the simple rule for an operation
        // region, which just means it contains both keywords seen below
        for(i in fileLines.indices) {
            val line = fileLines[i]

            // Find OperationRegion definitions for the EmbeddedControl's memory
            if(!(line.contains("OperationRegion") && line.contains("EmbeddedControl")))
                continue

            // Get the region information by splitting on the parameter-delimiter ","
            // Also get it's name, to search for multiple field declarations for one region, which can be the case
            val regionInformation = line.split(",")
            val regionName = regionInformation[0].substring(regionInformation[0].indexOf("(") + 1)

            // Convert the region offset to an integer. It may be Zero, One, or a hex-number.
            // Parse the starting offset of the OperationRegion, because it doesn't always start at 0x0,
            // and the first entry doesn't need to be an Offset too, so the tool relies on this information
            val regionOffset = when (val regionStart = regionInformation[2].trim()) {
                "Zero" -> 0
                "One" -> 1
                else -> regionStart.substring(2, regionStart.length).toInt(16)
            }

            // Only iterate the lines containing the region name, and remove the OR-definition
            // Then, for each field-block, create a new ec mem line entry for further processing
            // But first, the indexes need to be mapped to the lines, since all field definition lines will (mostly)
            // look the same, and thus would loose the line number information. By mapping them to their indexes, a
            // filter will still keep the right line numbers, and they can be used later on
            fileLines.mapIndexed { index, s -> index to s }.filter { it.second.contains(regionName) && fileLines.indexOf(it.second) != i }.forEach {
                // This definition-line will be assigned to it's offset, which the parent OR dictates
                ecMemLines[it.first] = regionOffset
            }
        }

        return ecMemLines
    }

    private fun findTargetFields(ecFieldLines: Map<Int, Int>): List<EcField> {
        val fieldLines = mutableListOf<EcField>()

        // Iterate all embedded control memory declaration lines. There is only one EC, from 0x0 to 0xFF. For some
        // reason those declarations may be split apart, but the fields can be collected to one list, as they will later
        // be overridden all at once too.
        for(fieldEntry in ecFieldLines.entries) {

            val currLine = fileLines[fieldEntry.key]
            val fieldsetScope = calculateItemScope(fieldEntry.key, currLine.length - currLine.trimStart().length)

            /*
                linePointer: Points to the current line of the file, is the OperationRegion line + 3
                for the first field or offset declaration

                currOffset: Current offset in bytes, either gets set by Offset instructions or
                incremented by new incoming field sizes divided by 8

                bitOffsetBuf: Buffer for field sizes that are not a multiple of 8, so they get accumulated
                here until big enough to add. Stuff like: 4, 2, 1, 1 would be a byte spread over 4 fields.
             */
            // Initial value will be the offset of the OperationRegion, passed
            // from above by the map of lineNumber to offset
            var currOffset = fieldEntry.value
            var linePointer = fieldEntry.key + 2
            var bitOffsetBuf = 0

            // Increment line pointer until end of declaration block
            while(!fileLines[linePointer].contains("}")) {
                val currData = fileLines[linePointer].replace(" ", "").split(",")

                // Add bit offset to byte offset counter, if applicable
                if(bitOffsetBuf % 8 == 0) {
                    currOffset += bitOffsetBuf / 8
                    bitOffsetBuf = 0
                }

                // This will be an offset, so set it to the current offset
                // Also reset the bitOffsetBuffer at this point
                if(currData[0].contains("Offset")) {
                    currOffset = currData[0].substring(9, currData[0].length - 1).toInt(16)
                    bitOffsetBuf = 0
                }

                // This will be a field definition: XXXX, S,
                else if(currData.size >= 2 && currData[1].toIntOrNull() != null) {
                    val bitSize = currData[1].toInt()

                    // Only look at fields > 8b, which will be problematic
                    if(bitSize > 8) {

                        // Iterate through all lines with their index where this field occurs
                        var fieldUsages = 0
                        for(line in fileLines.mapIndexed { index, line -> line to index }.toMap().filter { it.key.contains(currData[0]) }) {
                            val lineIndex = line.value

                            // Check if it's inside of a string, ignore that
                            if (isInsideOfString(lineIndex, currData[0]))
                                continue

                            // Check if this has the possibility to be a field declaration
                            // Also check if it's inside an OR
                            if (line.key.replace(" ", "").matches(Regex("[A-Z0-9]{4},[0-9]{1,5},?")) && isFieldDefinition(lineIndex))
                                continue

                            fieldUsages++
                        }

                        // Only add fields bigger than 8 bits to the tracker list
                        // Also, check if this field is used anywhere, definition-usages get ignored
                        if(fieldUsages > 0)
                            fieldLines.add(EcField(currOffset, currData[0], fieldsetScope, bitSize, linePointer))
                    }

                    // Add multiples of 8 to the byte offset, hold the bits in a separate buffer for later
                    if(bitSize % 8 != 0)
                        bitOffsetBuf += bitSize
                    else
                        currOffset += bitSize / 8
                }

                linePointer++
            }
        }

        // Sort the fields by their offset, ascending
        fieldLines.sortBy { it.offset }
        return fieldLines
    }

    private fun splitFields(targetFields: List<EcField>): Map<EcField, List<EcField>?> {
        val splitMap = mutableMapOf<EcField, MutableList<EcField>?>()

        for(targetField in targetFields) {

            // Just split either 2- or 4 byte fields, for later use with either B1B2 or B1B4
            // Ignore other values, they will be taken care of later
            val byteSize = targetField.size / 8
            if (byteSize != 2 && byteSize != 4) {
                splitMap[targetField] = null
                continue
            }

            // Generate subnames until the name list is full - and thus all names were successful
            var charPointer = 0
            val parName = targetField.name
            val subNames = mutableListOf<String>()
            while (subNames.size != byteSize && charPointer < parName.length) {
                for (i in 0 until byteSize) {
                    val subName = parName.substring(0, charPointer) + i.toString() + parName.substring(charPointer + 1)

                    if (
                        // Check if the child field name is contained in any of the file lines
                        fileLines.any { it.contains(subName, ignoreCase = true) } ||

                        // Check if the child field name has already been created for any other field
                        splitMap.values.any { it != null && it.any { name -> name.name.equals(subName, ignoreCase = true) } } ||

                        // Check if this subname collection already has this name
                        subNames.contains(subName)
                    ) {
                        // Failed attempt, get rid of the generated names and start fresh, go
                        // to the next char for index trials and break this current loop
                        subNames.clear()
                        charPointer++
                        break
                    }

                    // So far, so good, add the name to the list, it's unique
                    subNames.add(subName)
                }
            }

            // Now create the entries with the generated charPointer index
            splitMap[targetField] = mutableListOf()
            for ((c, subName) in subNames.withIndex()) {
                // Create the new name, and set the fields offset to + c, since one field is one byte in size
                splitMap[targetField]?.add(EcField(targetField.offset + c, subName, targetField.scope, 8, null))
            }
        }

        return splitMap
    }

    private fun fieldMapToCode(fieldMap: Map<EcField, List<EcField>?>): String {
        val sb = StringBuilder()

        // Null lists not going to be handled in an ECOR override (>32, thus buffered R/W)
        val targetFields = fieldMap.filterKeys { fieldMap[it] != null }

        // Create the embedded control override region with it's field block
        sb.appendLine("OperationRegion (ECOR, EmbeddedControl, Zero, 0xFF)")
        sb.appendLine("Field (ECOR, ByteAcc, NoLock, Preserve)")
        sb.appendLine("{")

        // Print initial offset, if it exists and it's not at 0x0
        if(targetFields.keys.isNotEmpty() && targetFields.keys.first().offset != 0)
            sb.appendLine("Offset (${targetFields.keys.first().hexOffset()}),")

        var lastOffset = targetFields.keys.firstOrNull()?.offset ?: 0
        for(targ in targetFields.keys) {

            // If the offset to the last printed field is greater than a byte (which will be the unit of
            // all override fields), append an offset instruction
            val deltaOffset = targ.offset - lastOffset
            if(deltaOffset > 1)
                sb.appendLine("Offset (${targ.hexOffset()}),")

            // Print field and set last offset to that sub-field's offset
            fieldMap[targ]?.forEachIndexed { index, ecField ->
                val trailingComma = if(index + 1 == fieldMap[targ]?.size) "" else ","
                sb.appendLine("${ecField.name}, ${ecField.size}$trailingComma")
                lastOffset = ecField.offset
            }
        }

        // Terminate declaration block
        sb.appendLine("}")
        return sb.toString()
    }

    private fun findTargetMethods(splittedFields: Map<EcField, List<EcField>?>): Pair<List<AslMethod>, Map<EcField, List<EcField>?>> {
        val methodList = mutableListOf<AslMethod>()
        val affecteds = mutableMapOf<EcField, List<EcField>?>()

        splittedFields.keys.forEach { field ->

            // Find all lines, where this field occurs, except the definition itself
            val occurrences = fileLines
                    // Map to line numbers
                    .mapIndexed { index, line -> index to line }
                    // Only usages of this field
                    .filter { it.second.contains(field.name) }
                    // Filter out the field definition
                    .filter { it.first != field.lineOfDefinition }

            // For each occurrence, build the enclosing method
            for((index, occurrance) in occurrences) {
                val methodBody = StringBuilder()
                var linePointer = index

                // Go up to the nearest method signature, where this method will begin
                while(!fileLines[linePointer].contains("Method"))
                    linePointer--

                // Cache the beginning line number, also add the signature to body buffer
                // Calculate it's indentation-level, which will be useful when calculating the scope later on
                // Indentation is just how many leading whitespace characters there are
                val methodBegin = linePointer
                val methodSig = fileLines[linePointer]
                val methodIndent = methodSig.length - methodSig.trimStart().length
                val methodScope = AslScope(calculateItemScope(methodBegin, methodIndent))

                // Parse the method name from it's signature
                val methodName = fileLines[methodBegin].split(",")[0].substring(fileLines[methodBegin].indexOf("(") + 1)

                // Check if this field is a valid one to patch within the method, by checking if it's an effected EC field
                // using it's scope. It needs to reach the EC scope, if it doesn't, scrap it, it's just a name overlap,
                // but different scope
                val fieldPattern = Regex("""\^*([A-Z0-9]+\.)*${field.name}""")
                val targetFieldScope = AslScope(field.scope)
                var affectedScopedField: AslScope? = null
                for(match in fieldPattern.findAll(occurrance)) {
                    val currTarg = AslScope(match.value, "${methodScope.toValue()}.$methodName")

                    // This can't be a valid usage, since it could never reach the target field - the
                    // names are just the same
                    if(!currTarg.canReach(targetFieldScope))
                        continue

                    affectedScopedField = currTarg
                    break
                }

                // This method is not affected by the current field
                if(affectedScopedField == null)
                    continue

                // Append method signature to buffer
                methodBody.appendLine(methodSig)

                // Shift the line pointer by one, so it's at the body block begin ({), also append to body buffer
                linePointer++
                methodBody.appendLine(fileLines[linePointer])

                // Starting out with one open bracket, the method body begin
                // To find the end of this method, there needs to be a balance between opening and closing
                // brackets, opening = closing. This will be the case, when openBrackets is zero. So, loop until zero.
                var openBrackets = 1
                while(openBrackets > 0) {
                    linePointer++

                    // Line contains opening bracket
                    if(fileLines[linePointer].contains("{"))
                        openBrackets++

                    // Line contains closing bracket
                    if(fileLines[linePointer].contains("}"))
                        openBrackets--

                    // Append current line to body buffer
                    methodBody.appendLine(fileLines[linePointer])
                }

                val targFields = splittedFields.filter { entry -> entry.key == field }.toMutableMap()
                affecteds.putAll(targFields)

                // Check if there has already been a method encountered with the same start- and endline
                val existingMethod = methodList.firstOrNull { aslm -> aslm.startLine == methodBegin && aslm.endLine == linePointer }
                if(existingMethod != null) {
                    // Just add to the affected fields to this method's map
                    existingMethod.affectedFields.putAll(splittedFields.filter { entry -> entry.key == field })
                }

                // This method has not been found yet, thus create a new one
                else {
                    methodList.add(AslMethod(
                            methodBegin,
                            linePointer, // Line number of method end will be at current line pointer value
                            methodIndent,
                            methodName,
                            methodScope,
                            methodBody.toString().trimEnd(), // Always trim the end of the method body
                            // Only the entry for the current field, filter out all other fields
                            targFields
                    ))
                }
            }
        }

        return Pair(methodList, affecteds)
    }

    private fun calculateItemScope(targetLine: Int, itemIndent: Int): String {

        val scopeEntries = mutableListOf<String>()
        var currentIndent = itemIndent
        var linePointer = targetLine

        // Search and build until the current indent is 4, since that's where the top level scope instructions begin
        while(currentIndent != 4) {
            linePointer--

            // Searching for scope or device instructions, which dictate the total absolute scope together
            var line = fileLines[linePointer]
            if(!(line.trim().startsWith("Scope") || line.trim().startsWith("Device")))
                continue

            // Calculate the indent difference, relative to the current indent
            val lineIndent = line.length - line.trimStart().length
            val indentDelta = currentIndent - lineIndent

            // Containing scope needs to be less indented than the current indent level
            if(indentDelta <= 0)
                continue

            // The current indent will be the indent of the new scope instruction, since
            // it could be offset by a multiple of 4, if there are other indentations between
            currentIndent = line.length - line.trimStart().length

            // Append the (partial) scope to the scope entry list
            line = line.trim()
            scopeEntries.add(line.substring(line.indexOf("(") + 1, line.length - 1))
        }

        // Reverse the entries, since they get built up from the inside out, then join them with a dot
        scopeEntries.reverse()
        return scopeEntries.joinToString(".")
    }

    private fun isInsideOfString(linePointer: Int, target: String): Boolean {
        val line = fileLines[linePointer]
        var charPointer = line.indexOf(target)

        // Skip empty lines
        if(line.isEmpty())
            return false

        // Iterate to start of string
        var isString = false
        while(charPointer >= 0) {
            if(line[charPointer] == '"') {
                charPointer = line.indexOf(target)

                // Iterate to end of string
                while(charPointer < line.length) {
                    if(line[charPointer] == '"') {

                        // At this point, the target is enclosed in " at both sides
                        isString = true
                        break
                    }
                    charPointer++
                }
                break
            }
            charPointer--
        }

        return isString
    }

    private fun isFieldDefinition(linePointer: Int): Boolean {
        val line = fileLines[linePointer]

        // This is a direct field definition outside an OR
        if(line.trim().startsWith("Name"))
            return true

        // Now, check if it's inside of an OR field block
        // Let's set a timeout of 200 lines, so this doesn't run itself into the ground
        val timeout = 200
        var enclosingBrackets = 0
        var currLine = linePointer
        while(linePointer - currLine < timeout) {
            val curr = fileLines[currLine]

            // Make sure we know how the target is enclosed in brackets.
            // It should be exactly 1 deep, and if a OR starts then, it's inside of that
            if(curr.contains("{"))
                enclosingBrackets++

            // A block closed... This can't be within the OR
            if(curr.contains("}"))
                return false

            // Came accross a region definition
            if(curr.trim().startsWith("OperationRegion") && enclosingBrackets == 1)
                return true

            currLine--
        }

        return false
    }
}
