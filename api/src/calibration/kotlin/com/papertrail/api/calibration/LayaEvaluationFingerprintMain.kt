package com.papertrail.api.calibration

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        require(args.size == 1) { "Usage: fingerprint-laya-evaluation <dataset.json>" }
        val datasetBytes = Files.readAllBytes(Path.of(args.single()))
        val dataset = jacksonObjectMapper().readValue(datasetBytes, LayaEvaluationDataset::class.java)
        dataset.validate()
        val applicationRevision = System.getenv("PAPER_TRAIL_APPLICATION_REVISION")
            ?.takeIf { it.matches(GIT_REVISION_PATTERN) }
            ?: throw IllegalArgumentException("A 40-character application revision is unavailable.")
        println("datasetId=${dataset.datasetId}")
        println("datasetVersion=${dataset.datasetVersion}")
        println("datasetSha256=${sha256(datasetBytes)}")
        println("heldOutSplitSha256=${dataset.heldOutSplitSha256()}")
        println("heldOutCitedPaperIds=${dataset.cases.filter { it.split == LayaEvaluationDataset.Split.HELD_OUT }
            .map(LayaEvaluationDataset.Case::citedPaperId).distinct().sorted().joinToString(",")}")
        println("applicationRevision=$applicationRevision")
    } catch (_: Exception) {
        System.err.println("Laya evaluation fingerprint failed. Detailed errors were withheld because local inputs may contain research text.")
        exitProcess(1)
    }
}

private val GIT_REVISION_PATTERN = Regex("[0-9a-f]{40}")

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
