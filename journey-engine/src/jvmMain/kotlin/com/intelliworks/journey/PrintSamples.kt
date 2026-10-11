package com.intelliworks.journey

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Prints every sample journey as JSON. With an argument, also writes
 * examples/<name>.json into that directory.
 *
 * Used by the Dockerfile to demonstrate serialization in the container.
 */
fun main(args: Array<String>) {
    val json = Json { prettyPrint = true }
    val outDir = args.firstOrNull()?.let { File(it) }
    outDir?.mkdirs()
    for ((name, journey) in SampleJourneys.all()) {
        val text = json.encodeToString(journey)
        println("=== $name ===")
        println(text)
        outDir?.resolve("$name.json")?.writeText(text + "\n")
    }
}
