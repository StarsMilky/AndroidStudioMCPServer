plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    kotlin("plugin.serialization") version "2.1.20"
}

group = "com.codeintel.mcpserver"
version = "2.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.2.6")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("com.intellij.modules.json")
    }

    implementation("io.modelcontextprotocol:kotlin-sdk:0.9.0")
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }

        changeNotes = """
            <h3>2.0.0</h3>
            <ul>
              <li>🆕 New tool: <b>find_symbol</b> — dedicated name-based lookup (simple name / FQN / member / kind filter), replaces overloaded resolve_symbol</li>
              <li>🆕 Every tool response now carries a <code>nextAction</code> hint to guide AI follow-up calls</li>
              <li>✏️ All tool descriptions rewritten for LLM pickability (explicit "USE THIS INSTEAD OF grep" triggers)</li>
              <li>🏗️ Architectural revamp — decoupled services from Kotlin/Java PSI via <code>LanguageAdapter</code> extension point; services/ directory now zero direct kotlin-PSI references</li>
              <li>🔌 New extension points <code>com.codeintel.mcpserver.languageAdapter</code> and <code>com.codeintel.mcpserver.frameworkAdapter</code> for third-party extension</li>
              <li>⚠️ <b>Breaking:</b> <code>resolve_symbol</code> is position-only now; migrate name-based lookups to <code>find_symbol</code></li>
            </ul>

            <h3>1.0.0 — Initial Release</h3>
            <ul>
              <li>12 MCP tools: resolve_symbol, find_references, get_scope, query_project,
                  query_framework, analyze_data_flow, analyze_quality, check_rules,
                  structural_search, refactor, checkpoint, sandbox</li>
              <li>Embedded Ktor HTTP server with Streamable MCP transport</li>
              <li>Built-in Tool Window with server status, Cursor config, and per-tool metrics</li>
              <li>One-click Cursor auto-configuration</li>
              <li>K1 and K2 Kotlin compiler support</li>
            </ul>
        """.trimIndent()
    }

    signing {
        certificateChainFile = file("signing/chain.crt")
        privateKeyFile = file("signing/private.pem")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    buildSearchableOptions {
        enabled = false
    }

    named("prepareJarSearchableOptions") {
        dependsOn.clear()
        enabled = false
    }

    named("jarSearchableOptions") {
        dependsOn.clear()
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
