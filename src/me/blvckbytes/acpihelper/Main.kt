package me.blvckbytes.acpihelper

import me.blvckbytes.acpihelper.patcher.DsdtPatcher

fun main(args: Array<String>) {
    // Print welcome screen
    println("----------- AcpiHelper -----------")
    println("> An experimental tool by BlvckBytes")
    println("----------- AcpiHelper -----------")
    println()

    // Invoke the patcher with either the hardcoded path for me debugging it, or arg0 of command line invocation
    DsdtPatcher(args.getOrNull(0) ?: "/Users/blvckbytes/Desktop/test1/DSDT.dsl")

    // Print goodbye message :)
    println()
    println("Goodbye!")
}

