import java.security.MessageDigest

plugins {
    id("com.android.application")
}

val releaseStoreFile = providers.environmentVariable("PIDECK_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("PIDECK_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("PIDECK_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("PIDECK_RELEASE_KEY_PASSWORD").orNull
val productionSigningAvailable = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "dev.pideck.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.pideck.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.3.0-alpha8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (productionSigningAvailable) {
            create("production") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Without injected production credentials Gradle creates an unsigned release APK.
            // A debug key is never silently substituted.
            signingConfig = signingConfigs.findByName("production")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = true
    }
}

val verifyModelManifest by tasks.registering {
    group = "verification"
    description = "Validates models-v2.json critical schema and immutable metadata"
    val manifestFile = layout.projectDirectory.file("src/main/assets/models-v2.json")
    val schemaFile = rootProject.layout.projectDirectory.file("schemas/models-v2.schema.json")
    inputs.files(manifestFile, schemaFile)
    doLast {
        require(schemaFile.asFile.isFile) { "schemas/models-v2.schema.json is missing" }
        val root = groovy.json.JsonSlurper().parse(manifestFile.asFile) as Map<*, *>
        require(root["schemaVersion"] == 2) { "models-v2 schemaVersion must be 2" }
        val models = root["models"] as? List<*> ?: error("models must be an array")
        require(models.isNotEmpty()) { "models-v2 must not be empty" }
        val ids = mutableSetOf<String>()
        var defaults = 0
        models.forEach { raw ->
            val model = raw as? Map<*, *> ?: error("model entry must be an object")
            val id = model["id"] as? String ?: error("model.id is required")
            require(ids.add(id)) { "duplicate model id: $id" }
            if (model["status"] == "DEFAULT") defaults++
            val source = model["source"] as? Map<*, *> ?: error("$id source is required")
            val artifact = model["artifact"] as? Map<*, *> ?: error("$id artifact is required")
            val license = model["license"] as? Map<*, *> ?: error("$id license is required")
            val runtime = model["runtime"] as? Map<*, *> ?: error("$id runtime is required")
            require(id.length <= 128 && id.matches(Regex("[a-z0-9][a-z0-9._-]+"))) {
                "$id model ID is unsafe"
            }
            require(
                (source["repository"] as? String)
                    ?.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) == true
            ) {
                "$id repository must be owner/name"
            }
            require((source["revision"] as? String)?.matches(Regex("[0-9a-f]{40}")) == true) {
                "$id revision must be an immutable 40-hex commit"
            }
            require(
                (artifact["file"] as? String)
                    ?.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.gguf")) == true
            ) {
                "$id artifact filename is unsafe"
            }
            require((artifact["sha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true) {
                "$id sha256 must be 64 lowercase hex"
            }
            require((artifact["bytes"] as? Number)?.toLong()?.let { it > 0 } == true) {
                "$id bytes must be positive"
            }
            require(license["spdx"] in setOf("Apache-2.0", "MIT")) {
                "$id license is not allowlisted"
            }
            val provenanceStatus = source["provenanceStatus"] as? String
                ?: error("$id provenanceStatus is required")
            require(provenanceStatus in setOf("VERIFIED", "INCOMPLETE")) {
                "$id provenanceStatus is invalid"
            }
            val managedFlags = setOf(
                "-m", "--model", "--alias", "--host", "--port",
                "-c", "--ctx-size", "-np", "--parallel", "-t", "--threads",
                "-tb", "--threads-batch", "-Cr", "--cpu-range", "--cpu-strict",
                "-Crb", "--cpu-range-batch", "--cpu-strict-batch",
                "--jinja", "--reasoning", "--temp", "--top-p", "--top-k",
                "--min-p", "--presence-penalty", "--api-key",
                "--spec-type", "--spec-draft", "--spec-draft-n",
                "--spec-draft-n-min", "--spec-draft-n-max",
            )
            val extraArgs = runtime["serverArgs"] as? List<*>
                ?: error("$id serverArgs must be an array")
            require(extraArgs.all { raw ->
                raw is String
                        && '\u0000' !in raw
                        && raw.length <= 512
                        && raw.substringBefore('=') !in managedFlags
            }) {
                "$id serverArgs is unsafe or overrides an app-managed flag"
            }
            if (model["status"] in setOf("DEFAULT", "SUPPORTED")) {
                require(provenanceStatus == "VERIFIED") {
                    "$id cannot be promoted without verified provenance"
                }
                val benchmark = model["benchmark"] as? Map<*, *>
                    ?: error("$id benchmark is required")
                require(benchmark["lastPassedAt"] is String && benchmark["report"] is String) {
                    "$id cannot be promoted without a benchmark report"
                }
            }
        }
        require(defaults <= 1) { "Only one DEFAULT model is allowed" }
    }
}

val verifyPinnedRuntime by tasks.registering {
    group = "verification"
    description = "Rejects floating runtime dependencies in production sources"
    val productionTree = fileTree("src/main") {
        include("**/*.java", "**/*.json", "**/*.py", "**/*.ts", "**/*.md")
    }
    inputs.files(productionTree)
    doLast {
        val offenders = productionTree.files.filter { it.readText().contains("@latest") }
        require(offenders.isEmpty()) {
            "Floating @latest dependency found in: ${offenders.joinToString()}"
        }
    }
}

val verifyBenchmarkSuite by tasks.registering {
    group = "verification"
    description = "Validates the checked-in 28-task benchmark contract"
    val suiteFile = rootProject.layout.projectDirectory.file("benchmarks/suite-v1/tasks.json")
    val reportSchema = rootProject.layout.projectDirectory.file(
        "schemas/benchmark-report.schema.json"
    )
    inputs.files(suiteFile, reportSchema)
    doLast {
        require(reportSchema.asFile.isFile) { "Benchmark report schema is missing" }
        val root = groovy.json.JsonSlurper().parse(suiteFile.asFile) as Map<*, *>
        require(root["schemaVersion"] == 1 && root["suiteVersion"] == "suite-v1") {
            "Unsupported benchmark suite"
        }
        val tasks = root["tasks"] as? List<*> ?: error("Benchmark tasks must be an array")
        require(tasks.size >= 24) { "At least 24 benchmark tasks are required" }
        val ids = tasks.map { task ->
            val value = task as? Map<*, *> ?: error("Benchmark task must be an object")
            val id = value["id"] as? String ?: error("Benchmark task ID is missing")
            require(id.matches(Regex("T[0-9]{2}"))) { "Invalid benchmark task ID: $id" }
            require(value["prompt"] is String && value["expectedSignals"] is List<*>) {
                "Benchmark task $id is incomplete"
            }
            id
        }
        require(ids.toSet().size == ids.size) { "Duplicate benchmark task ID" }
    }
}

tasks.named("preBuild") {
    dependsOn(
        verifyModelManifest,
        verifyPinnedRuntime,
        verifyBenchmarkSuite,
        "verifyNativeRuntime",
    )
}

tasks.register("verifyNativeRuntime") {
    group = "verification"
    description = "Verifies every bundled llama.cpp ELF against the pinned manifest"
    val manifestFile = layout.projectDirectory.file("src/main/assets/native-runtime.json")
    val nativeDirectory = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")
    inputs.file(manifestFile)
    inputs.dir(nativeDirectory)
    doLast {
        val root = groovy.json.JsonSlurper().parse(manifestFile.asFile) as Map<*, *>
        require(root["schemaVersion"] == 1 && root["build"] == "b10092") {
            "Unsupported native runtime manifest"
        }
        val entries = root["files"] as? List<*> ?: error("native runtime files are missing")
        val expected = entries.associate { raw ->
            val item = raw as? Map<*, *> ?: error("native runtime entry must be an object")
            val name = item["name"] as? String ?: error("native runtime filename is missing")
            require(name.matches(Regex("lib[A-Za-z0-9._-]+\\.so"))) {
                "Unsafe native runtime filename: $name"
            }
            name to item
        }
        val actual = nativeDirectory.asFile.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.associateBy { it.name }
            ?: emptyMap()
        require(actual.keys == expected.keys) {
            "Bundled native files differ from native-runtime.json"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        expected.forEach { (name, metadata) ->
            val file = actual.getValue(name)
            require(file.length() == (metadata["bytes"] as Number).toLong()) {
                "$name byte length differs from native-runtime.json"
            }
            digest.reset()
            val hash = digest.digest(file.readBytes()).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
            require(hash == metadata["sha256"]) {
                "$name SHA-256 differs from native-runtime.json"
            }
        }
    }
}

tasks.register("verifyProductionSigning") {
    group = "verification"
    doLast {
        require(productionSigningAvailable) {
            "Production keystore environment is required; debug signing is forbidden"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
