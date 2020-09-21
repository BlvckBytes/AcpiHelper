package me.blvckbytes.acpihelper.patcher

import java.io.File
import java.lang.StringBuilder

class DsdtPatcher(
        filePath: String
) {

    private var fileLines: MutableList<String> = mutableListOf()

    init {
        File(filePath).forEachLine { fileLines.add(it) }

        val ecMemLines = findEcMemLines()
        val targetFields = findTargetFields(ecMemLines)
        val splittedFields = splitFields(targetFields)

        splittedFields.forEach {
            println("${it.key}:")
            it.value?.forEach { splitField ->
                println("> $splitField")
            }
            println("end of field\n")
        }

        println("Generating OperationRegion overrides...\n")
        println(fieldMapToCode(splittedFields))
    }

    private fun findEcMemLines(): List<Int> {
        val ecMemLines = mutableListOf<Int>()

        // Iterate all lines and add the line index to the list, if it matches the simple
        // rule for an operation region, which just means it contains both words seen below
        for(i in fileLines.indices) {
            val line = fileLines[i]
            if(!(line.contains("OperationRegion") && line.contains("EmbeddedControl")))
                continue
            ecMemLines.add(i)
        }

        return ecMemLines
    }

    private fun findTargetFields(ecMemLines: List<Int>): List<EcField> {
        val fieldLines = mutableListOf<EcField>()

        // Iterate all embedded control memory declaration lines. There is only one EC, from 0x0 to 0xFF. For some
        // reason those declarations may be split apart, but the fields can be collected to one list, as they will later
        // be overridden all at once too.
        for(lineIndex in ecMemLines) {

            // Parse the starting offset of the OperationRegion, because it doesn't always start at 0x0,
            // and the first entry doesn't need to be an Offset too, so the tool relies on this information

            // Convert the region offset to an integer. It may be Zero, One, or a hex-number.
            val regionStartInt = when (val regionStart = fileLines[lineIndex].split(",")[2].trim()) {
                "Zero" -> 0
                "One" -> 1
                else -> regionStart.substring(2, regionStart.length).toInt(16)
            }

            /*
                linePointer: Points to the current line of the file, is the OperationRegion line + 3
                for the first field or offset declaration

                currOffset: Current offset in bytes, either gets set by Offset instructions or
                incremented by new incoming field sizes divided by 8

                bitOffsetBuf: Buffer for field sizes that are not a multiple of 8, so they get accumulated
                here until big enough to add. Stuff like: 4, 2, 1, 1 would be a byte spread over 4 fields.
             */
            var linePointer = lineIndex + 3
            var currOffset = regionStartInt
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

                    // Only add fields bigger than 8 bits to the tracker list
                    // Also, check if this field is used anywhere, if not, don't track it
                    if(bitSize > 8 && fileLines.count { it.contains(currData[0]) } > 1)
                        fieldLines.add(EcField(currOffset, currData[0], bitSize))

                    // Add multiples of 8 to the byte offset, hold the bits in a separate buffer for later
                    if(bitSize % 8 != 0)
                        bitOffsetBuf += bitSize
                    else
                        currOffset += bitSize / 8
                }

                linePointer++
            }
        }

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
                        subNames.clear()
                        charPointer++
                        break
                    }

                    subNames.add(subName)
                }
            }

            // Now create the entries with the generated charPointer index
            splitMap[targetField] = mutableListOf()
            for ((c, subName) in subNames.withIndex()) {
                // Create the new name, and set the fields offset to + c, since one field is one byte in size
                splitMap[targetField]?.add(EcField(targetField.offset + c, subName, 8))
            }
        }

        return splitMap
    }

    private fun fieldMapToCode(fieldMap: Map<EcField, List<EcField>?>): String {
        val sb = StringBuilder()

        // Create the embedded control override region with it's field block
        sb.appendLine("OperationRegion (ECOR, EmbeddedControl, Zero, 0xFF)")
        sb.appendLine("Field (ECOR, ByteAcc, NoLock, Preserve)")
        sb.appendLine("{")

        // Print initial offset, if it exists and it's not at 0x0
        if(fieldMap.keys.isNotEmpty() && fieldMap.keys.first().offset != 0)
            sb.appendLine("Offset (${fieldMap.keys.first().hexOffset()}),")

        var lastOffset = fieldMap.keys.firstOrNull()?.offset ?: 0
        for(targ in fieldMap.keys) {

            // If the offset to the last printed field is greater than a byte (which will be the unit of
            // all override fields), append an offset instrcution
            val deltaOffset = targ.offset - lastOffset
            if(deltaOffset > 1)
                sb.appendLine("Offset (${targ.hexOffset()}),")

            // Null lists not going to be handled in an ECOR override
            val fields = fieldMap[targ] ?: continue

            // Print field and set last offset to that sub-field's offset
            fields.forEach {
                sb.appendLine("${it.name}, ${it.size},")
                lastOffset = it.offset
            }
        }

        // Terminate declaration block
        sb.appendLine("}")
        return sb.toString()
    }
}
