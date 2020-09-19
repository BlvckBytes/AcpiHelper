package me.blvckbytes.acpihelper

import me.blvckbytes.acpihelper.patcher.DsdtPatcher

fun main(args: Array<String>) {
    DsdtPatcher(args.getOrNull(0) ?: "/Users/blvckbytes/Desktop/test1/DSDT.dsl")
}
