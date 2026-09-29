package com.papertrail.api.calibration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: calibrate <fixture.json> <report.md>" }
    val fixturePath = Path.of(args[0])
    val reportPath = Path.of(args[1])
    val mapper = jacksonObjectMapper()
    val fixture = Files.newBufferedReader(fixturePath).use { mapper.readValue<CalibrationFixture>(it) }
    fixture.validate()
    val report = CalibrationHarness().renderReport(fixture)
    Files.createDirectories(reportPath.toAbsolutePath().parent)
    Files.writeString(reportPath, report)
    println("Calibration report written to ${reportPath.toAbsolutePath()}")
    println("Fixture ${fixture.fixtureId}: ${fixture.fixtureStatus}; calibration research status ${fixture.releaseCalibrationStatus}.")
}
