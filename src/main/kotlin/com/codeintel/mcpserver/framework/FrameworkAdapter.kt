package com.codeintel.mcpserver.framework

import com.codeintel.mcpserver.models.results.FrameworkViewResult
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project

/**
 * Framework-specific inspector for [query_framework].
 *
 * One adapter per framework (ROOM, RETROFIT, HILT, COMPOSE, NAVIGATION, ...).
 * Each adapter lives in the language module that owns that framework — e.g. Room /
 * Hilt / Compose adapters ship under `mcp-kotlin.xml`, while something like
 * SwiftUI-equivalent adapters would ship under their own optional descriptor.
 *
 * The core `FrameworkAnalyzer` discovers adapters through the
 * `com.codeintel.mcpserver.frameworkAdapter` extension point and dispatches by
 * [name].
 */
interface FrameworkAdapter {
    /**
     * Framework identifier, must match one of `FrameworkType` enum values
     * (ROOM / RETROFIT / HILT / COMPOSE / NAVIGATION / ...).
     */
    val name: String

    /**
     * Scan the project and return the framework view, or null if the framework
     * is not present in the project.
     *
     * [detailTarget] lets the caller drill into a specific entity/interface by
     * name; when null, the adapter returns an overview.
     */
    fun scan(project: Project, detailTarget: String? = null): FrameworkViewResult?

    companion object {
        val EP_NAME: ExtensionPointName<FrameworkAdapter> =
            ExtensionPointName.create("com.codeintel.mcpserver.frameworkAdapter")

        /** Lookup an adapter by framework name, or null if no adapter is registered. */
        fun find(name: String): FrameworkAdapter? =
            EP_NAME.extensionList.firstOrNull { it.name.equals(name, ignoreCase = true) }

        /** All installed framework adapters (order = declaration in plugin.xml). */
        fun all(): List<FrameworkAdapter> = EP_NAME.extensionList
    }
}
