package me.blvckbytes.acpihelper.patcher

import java.io.File

class DsdtPatcher(
        filePath: String
) {

    private var fileLines: MutableList<String> = mutableListOf()

    init {
        File(filePath).forEachLine { fileLines.add(it) }

        val ecMemLines = findEcMemLines()
        val targetFields = findTargetFields(ecMemLines)
        targetFields.forEach {
            println(it)
        }
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
                    if(bitSize > 8)
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

        return fieldLines
    }
}