@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.androidstudio.mcpserver.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

val McpJson = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    encodeDefaults = true
    ignoreUnknownKeys = true
}
