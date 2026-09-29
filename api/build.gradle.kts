import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.21"
    kotlin("plugin.spring") version "2.1.21"
}

group = "com.papertrail"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-scalar:2.8.17")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.apache.pdfbox:pdfbox:3.0.5") {
        // Spring JCL supplies the Commons Logging API and avoids a duplicate logging implementation.
        exclude(group = "commons-logging", module = "commons-logging")
    }
    implementation("com.optimaize.languagedetector:language-detector:0.6")
    implementation("io.minio:minio:8.5.17")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:postgresql:1.21.4")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.awaitility:awaitility-kotlin:4.2.2")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val calibrationSourceSet = sourceSets.create("calibration") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

sourceSets.named("test") {
    compileClasspath += calibrationSourceSet.output
    runtimeClasspath += calibrationSourceSet.output
}

configurations[calibrationSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get())
configurations[calibrationSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.runtimeOnly.get())

tasks.register<JavaExec>("calibrate") {
    group = "verification"
    description = "Benchmarks reference resolution and evidence aggregation against the versioned calibration fixture."
    dependsOn(tasks.named(calibrationSourceSet.classesTaskName))
    classpath = calibrationSourceSet.runtimeClasspath
    mainClass.set("com.papertrail.api.calibration.CalibrationHarnessMainKt")
    val repositoryRoot = project.projectDir.parentFile
    args(
        repositoryRoot.resolve("docs/benchmarks/v1-calibration-fixture.json").absolutePath,
        repositoryRoot.resolve("docs/benchmarks/v1-calibration.md").absolutePath,
    )
    workingDir = repositoryRoot
}

tasks.register<JavaExec>("evaluateLaya") {
    group = "verification"
    description = "Runs one pre-registered Laya evaluation split against the pinned local provider."
    dependsOn(tasks.named(calibrationSourceSet.classesTaskName))
    classpath = calibrationSourceSet.runtimeClasspath
    mainClass.set("com.papertrail.api.calibration.LayaEvaluationMainKt")
    workingDir = project.projectDir.parentFile
    doFirst {
        val dataset = project.findProperty("layaDataset")?.toString()
            ?: throw GradleException("Pass -PlayaDataset=<dataset.json>.")
        val split = project.findProperty("layaSplit")?.toString()
            ?: throw GradleException("Pass -PlayaSplit=calibration|held-out.")
        val results = project.findProperty("layaResults")?.toString()
            ?: throw GradleException("Pass -PlayaResults=<results.json>.")
        val report = project.findProperty("layaReport")?.toString()
            ?: throw GradleException("Pass -PlayaReport=<report.md>.")
        val plan = project.findProperty("layaPlan")?.toString()
        setArgs(listOfNotNull(dataset, split, results, report, plan))
    }
}

tasks.register<JavaExec>("fingerprintLaya") {
    group = "verification"
    description = "Prints local dataset, held-out split, and source revision pins without invoking Laya."
    dependsOn(tasks.named(calibrationSourceSet.classesTaskName))
    classpath = calibrationSourceSet.runtimeClasspath
    mainClass.set("com.papertrail.api.calibration.LayaEvaluationFingerprintMainKt")
    workingDir = project.projectDir.parentFile
    doFirst {
        val dataset = project.findProperty("layaDataset")?.toString()
            ?: throw GradleException("Pass -PlayaDataset=<dataset.json>.")
        setArgs(listOf(dataset))
    }
}

springBoot {
    mainClass = "com.papertrail.api.PaperTrailApplicationKt"
}
