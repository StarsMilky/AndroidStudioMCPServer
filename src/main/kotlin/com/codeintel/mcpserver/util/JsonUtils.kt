@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.codeintel.mcpserver.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

val MCP_JSON = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
    ignoreUnknownKeys = true
}
