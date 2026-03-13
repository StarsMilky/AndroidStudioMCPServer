package com.androidstudio.mcpserver.models.results
import kotlinx.serialization.Serializable

@Serializable
data class SearchMatchResult(
    val total: Int,
    val matches: List<SearchMatch>,
    val truncated: Boolean = false,
    val hint: String? = null
)

@Serializable
data class SearchMatch(val file: String, val line: Int, val matchedCode: String)
