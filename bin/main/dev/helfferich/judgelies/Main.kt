package dev.helfferich.judgelies

import kotlin.system.exitProcess

import dev.helfferich.judgelies.cli.Cli

fun main(args: Array<String>) {
    exitProcess(Cli.run(args))
}
