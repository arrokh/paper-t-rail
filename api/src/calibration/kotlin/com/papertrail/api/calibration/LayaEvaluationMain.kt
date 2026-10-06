package com.papertrail.api.calibration

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.external.laya.LayaSystemOneProvider
import com.papertrail.api.external.laya.LayaSystemOneSettings
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Locale
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        runLayaEvaluationCommand(args, System.getenv())
    } catch (_: Exception) {
        System.err.println("Laya evaluation failed. Detailed errors were withheld because local inputs may contain research text or credentials.")
        exitProcess(1)
    }
}

private fun runLayaEvaluationCommand(args: Array<String>, environment: Map<String, String>) {
    require(args.size in 4..5) {
        "Usage: evaluate-laya <dataset.json> <calibration|held-out> <results.json> <report.md> [pre-registration.json]"
    }
    val datasetPath = Path.of(args[0]).toAbsolutePath().normalize()
    val split = when (args[1].lowercase(Locale.ROOT)) {
        "calibration" -> LayaEvaluationDataset.Split.CALIBRATION
        "held-out" -> LayaEvaluationDataset.Split.HELD_OUT
        else -> throw IllegalArgumentException("Split must be 'calibration' or 'held-out'.")
    }
    val resultsPath = Path.of(args[2]).toAbsolutePath().normalize()
    val reportPath = Path.of(args[3]).toAbsolutePath().normalize()
    val planPath = args.getOrNull(4)?.let { Path.of(it).toAbsolutePath().normalize() }
    require(resultsPath != reportPath && resultsPath != datasetPath && reportPath != datasetPath &&
        (planPath == null || planPath != datasetPath && planPath != resultsPath && planPath != reportPath)
    ) { "Dataset, plan, results, and report paths must be distinct." }
    if (split == LayaEvaluationDataset.Split.HELD_OUT) {
        require(planPath != null) { "Held-out evaluation requires a separately versioned pre-registration plan." }
    }

    val mapper = jacksonObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    val datasetBytes = Files.readAllBytes(datasetPath)
    val dataset = mapper.readValue(datasetBytes, LayaEvaluationDataset::class.java)
    dataset.validate()
    val datasetSha256 = sha256(datasetBytes)
    val planAndHash = planPath?.let { path ->
        val bytes = Files.readAllBytes(path)
        mapper.readValue(bytes, LayaEvaluationPlan::class.java) to sha256(bytes)
    }

    val apiKey = environment["LAYA_API_KEY"]
    val baseUrl = environment["LAYA_BASE_URL"].orEmpty()
    val trustedHosts = environment["LAYA_TRUSTED_HOSTS"]
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.toSet()
        .orEmpty()
    val settings = LayaSystemOneSettings(
        enabled = true,
        baseUrl = baseUrl,
        apiKey = apiKey,
        trustedHosts = trustedHosts,
        requestTimeoutMillis = environment["LAYA_REQUEST_TIMEOUT_MILLIS"]
            ?.toLongOrNull()
            ?: LayaSystemOneSettings.DEFAULT_REQUEST_TIMEOUT_MILLIS,
    )
    require(settings.isSelectable) {
        "Evaluation requires an authenticated endpoint on an explicitly trusted local/private host."
    }
    val applicationRevision = environment["PAPER_TRAIL_APPLICATION_REVISION"]
        ?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("PAPER_TRAIL_APPLICATION_REVISION must identify the prompt implementation commit.")

    val run = LayaEvaluationHarness().evaluate(
        dataset = dataset,
        split = split,
        datasetSha256 = datasetSha256,
        applicationRevision = applicationRevision,
        provider = LayaSystemOneProvider(settings),
        plan = planAndHash?.first,
        planSha256 = planAndHash?.second,
    )
    val runBytes = mapper.writeValueAsBytes(run)
    val report = LayaEvaluationReport().render(
        dataset = dataset,
        run = run,
        plan = planAndHash?.first,
        frozenOutputSha256 = sha256(runBytes),
        preRegistrationSha256 = planAndHash?.second,
    )
    writeAtomically(resultsPath, runBytes)
    writeAtomically(reportPath, report.toByteArray(Charsets.UTF_8))
    val completed = run.caseResults.count { it.prediction != null }
    println("Laya ${split.name.lowercase(Locale.ROOT)} evaluation complete: $completed/${run.caseResults.size} cases completed; ${run.caseResults.size - completed} incomplete.")
}

private fun writeAtomically(path: Path, contents: ByteArray) {
    val parent = path.parent ?: Path.of(".").toAbsolutePath().normalize()
    Files.createDirectories(parent)
    val temporary = Files.createTempFile(
        parent,
        ".laya-evaluation-",
        ".tmp",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
    )
    try {
        Files.write(temporary, contents)
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
