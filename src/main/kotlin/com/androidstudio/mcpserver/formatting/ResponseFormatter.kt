package com.androidstudio.mcpserver.formatting

import com.androidstudio.mcpserver.util.McpJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ResponseFormatter {

    fun <T> format(result: T, serializer: KSerializer<T>, policy: SizePolicy): String {
        val jsonString = McpJson.encodeToString(serializer, result)
        if (jsonString.toByteArray(Charsets.UTF_8).size <= policy.maxBytes) {
            return jsonString
        }
        return truncate(jsonString, policy)
    }

    private fun truncate(jsonString: String, policy: SizePolicy): String {
        val jsonElement = McpJson.parseToJsonElement(jsonString)
        if (jsonElement !is JsonObject) return jsonString

        val mutable = jsonElement.toMap().toMutableMap()
        mutable["truncated"] = JsonPrimitive(true)
        mutable["hint"] = JsonPrimitive(
            "Result exceeds ${policy.maxBytes}B limit. Use detail mode or narrow scope."
        )

        val arrayFields = jsonElement.entries
            .filter { it.value is JsonArray && (it.value as JsonArray).size > 1 }
            .sortedByDescending { (it.value as JsonArray).size }

        for ((key, value) in arrayFields) {
            val array = value as JsonArray
            val originalSize = array.size
            mutable.putIfAbsent("total", JsonPrimitive(originalSize))

            var topN = originalSize
            while (topN > 0) {
                topN /= 2
                if (topN == 0) topN = 1
                mutable[key] = JsonArray(array.take(topN))
                val candidate = JsonObject(mutable).toString()
                if (candidate.toByteArray(Charsets.UTF_8).size <= policy.maxBytes) {
                    return candidate
                }
                if (topN == 1) break
            }
        }

        return JsonObject(mutable).toString()
    }
}
