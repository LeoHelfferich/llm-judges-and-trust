package dev.helfferich.judgelies

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Cli.run(args))
}
