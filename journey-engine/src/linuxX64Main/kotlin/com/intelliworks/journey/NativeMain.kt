package com.intelliworks.journey

/**
 * Native entry point: prints all sample journeys as JSON to stdout.
 * This is what the Docker image runs (see Dockerfile).
 */
fun main() {
    println(SampleJourneys.samplesJson())
}
