pluginManagement {
    val mavenProperties =
        java.util.Properties().apply {
            val source = settingsDir.resolve("maven.properties")
            require(source.isFile) { "Missing Maven mode configuration: $source" }
            source.inputStream().use { load(it) }
        }
    val localMaven = mavenProperties.getProperty("maven.local")?.trim()?.toBooleanStrictOrNull()
    require(localMaven != null) { "maven.properties must set maven.local=true or false" }
    val localMavenValidation =
        providers
            .gradleProperty("project.repositories.mavenLocal")
            .map { it.trim().toBooleanStrict() }
            .getOrElse(false)
    val localRepository =
        if (localMavenValidation) {
            val localMavenPath = providers.systemProperty("maven.repo.local").orNull
            require(localMavenPath != null && File(localMavenPath).isAbsolute) {
                "Local Maven validation requires -Dmaven.repo.local=/absolute/path/to/isolated-repository"
            }
            File(localMavenPath)
        } else {
            val configured =
                mavenProperties
                    .getProperty("maven.localRepository")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { File(it) }
                    ?: File(System.getProperty("user.home"), ".m2/repository")
            require(configured.isAbsolute) { "maven.localRepository must be an absolute path" }
            configured
        }

    System.setProperty("maven.repo.local", localRepository.absolutePath)
    settings.extra["mavenLocalEnabled"] = localMaven
    settings.extra["mavenLocalValidation"] = localMavenValidation

    repositories {
        if (localMaven) mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

val localMavenEnabled = extra["mavenLocalEnabled"] as Boolean
val localMavenValidation = extra["mavenLocalValidation"] as Boolean

dependencyResolutionManagement {
    if (localMavenValidation) {
        val localMavenPath = providers.systemProperty("maven.repo.local").orNull
        require(localMavenPath != null && File(localMavenPath).isAbsolute) {
            "Local Maven validation requires -Dmaven.repo.local=/absolute/path/to/isolated-repository"
        }
        repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    } else {
        repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    }
    repositories {
        if (localMavenValidation) {
            exclusiveContent {
                forRepository {
                    mavenLocal { name = "kotlinManiaLocalValidation" }
                }
                filter { includeGroup("io.github.kotlinmania") }
            }
        } else if (localMavenEnabled) {
            mavenLocal()
        }
        google()
        mavenCentral()
        if (localMavenValidation) {
            ivy("https://nodejs.org/dist") {
                name = "Node Distributions"
                patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                metadataSources { artifact() }
                content { includeModule("org.nodejs", "node") }
            }
            ivy("https://github.com/yarnpkg/yarn/releases/download") {
                name = "Yarn Distributions"
                patternLayout { artifact("v[revision]/[artifact]-v[revision].[ext]") }
                metadataSources { artifact() }
                content { includeModule("com.yarnpkg", "yarn") }
            }
            ivy("https://github.com/WebAssembly/binaryen/releases/download") {
                name = "Binaryen Distributions"
                patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
                metadataSources { artifact() }
                content { includeModule("com.github.webassembly", "binaryen") }
            }
        }
    }
}

gradle.beforeProject {
    if (!localMavenValidation) {
        repositories {
            if (localMavenEnabled) mavenLocal()
            google()
            mavenCentral()
        }
    }
    buildscript.repositories {
        if (localMavenEnabled) mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = providers.gradleProperty("project.name").get()
