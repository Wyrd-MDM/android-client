val WYRD_VERSION = "1.0.1"

fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map {
        it.toIntOrNull() ?: throw GradleException("version '$name' is not major.minor[.patch]")
    }
    require(parts.size in 2..3) { "version '$name' is not major.minor[.patch]" }
    require(parts.drop(1).all { it < 100 }) { "version '$name': minor and patch are two digits" }
    val (major, minor) = parts
    return major * 10000 + minor * 100 + (parts.getOrNull(2) ?: 0)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "link.wyrd"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "link.wyrd"
        minSdk = 33
        targetSdk = 36
        versionName = WYRD_VERSION
        versionCode = versionCodeOf(WYRD_VERSION)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
}

abstract class CheckTranslations : DefaultTask() {
    @get:InputDirectory
    abstract val resDir: DirectoryProperty

    private companion object {
        const val DEFAULT_LOCALE = "en"
        const val CATALOG = "strings.xml"
        const val LOCALES_CONFIG = "xml/locales_config.xml"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        val PLURAL_FORMS = mapOf(
            "en" to setOf("one", "other"),
            "de" to setOf("one", "other"),
            "ru" to setOf("one", "few", "many", "other"),
        )

        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(<]*\d*(\.\d+)?[a-zA-Z%]""")
    }

    private class Catalog(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun read(file: java.io.File): Catalog {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val children = doc.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? org.w3c.dom.Element ?: continue
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> if (node.getAttribute("translatable") != "false") strings[name] = node.textContent
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    plurals[name] = (0 until items.length).associate {
                        val item = items.item(it) as org.w3c.dom.Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return Catalog(strings, plurals)
    }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text).map { it.value }.sorted().toList()

    @TaskAction
    fun check() {
        val res = resDir.get().asFile
        val config = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(res.resolve(LOCALES_CONFIG))
        val localeNodes = config.getElementsByTagName("locale")
        val offered = (0 until localeNodes.length)
            .map { (localeNodes.item(it) as org.w3c.dom.Element).getAttributeNS(ANDROID_NS, "name") }
            .toSet()
        val catalogued = res.listFiles().orEmpty()
            .filter { it.name.startsWith("values-") && it.resolve(CATALOG).isFile }
            .map { it.name.removePrefix("values-") }
            .toSet()

        val problems = mutableListOf<String>()
        if (DEFAULT_LOCALE !in offered) problems += "$LOCALES_CONFIG does not offer the default language $DEFAULT_LOCALE"
        for (l in offered - DEFAULT_LOCALE - catalogued) problems += "$LOCALES_CONFIG offers $l, but values-$l/$CATALOG is missing"
        for (l in catalogued - offered) problems += "values-$l/$CATALOG exists, but $LOCALES_CONFIG does not offer $l"

        val base = read(res.resolve("values/$CATALOG"))
        val locales = listOf(DEFAULT_LOCALE to base) +
            (offered - DEFAULT_LOCALE).filter { it in catalogued }.sorted()
                .map { it to read(res.resolve("values-$it/$CATALOG")) }

        for ((locale, cat) in locales) {
            val where = if (locale == DEFAULT_LOCALE) "values/$CATALOG" else "values-$locale/$CATALOG"
            val forms = PLURAL_FORMS[locale]
            if (forms == null) {
                problems += "$where: no plural rules for $locale - add them to PLURAL_FORMS"
                continue
            }
            for (name in base.strings.keys - cat.strings.keys) problems += "$where: missing string $name"
            for (name in cat.strings.keys - base.strings.keys) problems += "$where: string $name is not in the default catalog"
            for (name in base.plurals.keys - cat.plurals.keys) problems += "$where: missing plurals $name"
            for (name in cat.plurals.keys - base.plurals.keys) problems += "$where: plurals $name is not in the default catalog"
            for ((name, text) in cat.strings) {
                if (text.isBlank()) problems += "$where: string $name is blank"
                val expected = base.strings[name] ?: continue
                if (placeholders(text) != placeholders(expected)) {
                    problems += "$where: string $name has ${placeholders(text)}, the default has ${placeholders(expected)}"
                }
            }
            for ((name, items) in cat.plurals) {
                if (items.keys != forms) problems += "$where: plurals $name has ${items.keys.sorted()}, $locale needs ${forms.sorted()}"
                val expected = base.plurals[name]?.get("other") ?: continue
                for ((quantity, text) in items) {
                    if (text.isBlank()) problems += "$where: plurals $name/$quantity is blank"
                    if (placeholders(text) != placeholders(expected)) {
                        problems += "$where: plurals $name/$quantity has ${placeholders(text)}, the default has ${placeholders(expected)}"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Translations are incomplete:\n" + problems.joinToString("\n") { "  $it" })
        }
    }
}

val checkTranslations by tasks.registering(CheckTranslations::class) {
    resDir.set(layout.projectDirectory.dir("src/main/res"))
}

tasks.named("preBuild") { dependsOn(checkTranslations) }

abstract class ThirdPartyNotices : DefaultTask() {
    @get:Input
    abstract val components: ListProperty<String>

    @get:InputDirectory
    abstract val licenseDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private companion object {
        const val FILE = "third_party_notices.txt"
        const val WIDTH = 80

        val LICENSES = listOf(
            "androidx." to "Apache-2.0.txt",
            "com.google.android.material" to "Apache-2.0.txt",
            "com.google.code.gson" to "Apache-2.0.txt",
            "com.google.crypto.tink" to "Apache-2.0.txt",
            "com.google.errorprone" to "Apache-2.0.txt",
            "com.google.guava" to "Apache-2.0.txt",
            "com.squareup." to "Apache-2.0.txt",
            "org.jetbrains" to "Apache-2.0.txt",
            "org.jspecify" to "Apache-2.0.txt",
            "org.bouncycastle" to "BouncyCastle.txt",
        )

        val BUNDLED = listOf(
            "font: IBM Plex Mono" to "OFL-IBMPlexMono.txt",
            "font: Manrope" to "OFL-Manrope.txt",
            "font: Oswald" to "OFL-Oswald.txt",
            "icon: Material Design Icons, information-outline" to "Apache-2.0.txt",
        )
    }

    @TaskAction
    fun write() {
        val resolved = components.get().map { id ->
            id to (LICENSES.firstOrNull { id.startsWith(it.first) }?.second
                ?: throw GradleException("$id: its license is not in LICENSES of app/build.gradle.kts"))
        }
        val byLicense = (resolved + BUNDLED).groupBy({ it.second }, { it.first })
        val rule = "=".repeat(WIDTH)
        val text = buildString {
            append("Wyrd for Android\nCopyright 2026 Wyrd. Licensed under the MIT License.\n\n")
            append("This application includes the third-party software listed below. Each license\n")
            append("text follows the list of components it covers.\n")
            for ((license, ids) in byLicense.toSortedMap()) {
                append("\n$rule\n${ids.joinToString("\n")}\n${"-".repeat(WIDTH)}\n")
                append(licenseDir.file(license).get().asFile.readText())
            }
        }
        outputDir.file(FILE).get().asFile.writeText(text)
    }
}

androidComponents {
    onVariants { variant ->
        val notices = tasks.register<ThirdPartyNotices>("${variant.name}ThirdPartyNotices") {
            licenseDir.set(layout.projectDirectory.dir("licenses"))
            components.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                val seen = mutableSetOf(root)
                val todo = ArrayDeque(listOf(root))
                while (todo.isNotEmpty()) {
                    for (dep in todo.removeFirst().dependencies) {
                        val next = (dep as? ResolvedDependencyResult)?.selected ?: continue
                        if (seen.add(next)) todo.add(next)
                    }
                }
                seen.mapNotNull { (it.id as? ModuleComponentIdentifier)?.displayName }.sorted()
            })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(notices, ThirdPartyNotices::outputDir)
    }
}
