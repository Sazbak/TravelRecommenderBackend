package com.example

import kotlinx.serialization.json.Json

/** Frames complete objects in the strict recommendations array across arbitrary token boundaries. */
internal class RecommendationStreamParser {
    private enum class State { PREFIX, ITEM_OR_END, ITEM, OBJECT, SEPARATOR, END_OBJECT, DONE }
    private var state = State.PREFIX
    private val text = StringBuilder()
    private val item = StringBuilder()
    private var depth = 0
    private var quoted = false
    private var escaped = false

    suspend fun append(delta: String, emit: suspend (Recommendation) -> Unit) {
        check(text.length.toLong() + delta.length <= 1_048_576) { "Recommendation output too large" }
        for (char in delta) {
            text.append(char)
            when (state) {
                State.PREFIX -> if (char == '[') {
                    check(Regex("""\s*\{\s*"recommendations"\s*:\s*\[""").matches(text))
                    state = State.ITEM_OR_END
                }
                State.ITEM_OR_END, State.ITEM -> when {
                    char.isJsonWhitespace() -> Unit
                    char == ']' && state == State.ITEM_OR_END -> state = State.END_OBJECT
                    char == '{' -> {
                        item.clear().append(char)
                        depth = 1
                        state = State.OBJECT
                    }
                    else -> error("Expected recommendation object")
                }
                State.OBJECT -> {
                    item.append(char)
                    if (quoted) {
                        if (escaped) escaped = false
                        else if (char == '\\') escaped = true
                        else if (char == '"') quoted = false
                    } else when (char) {
                        '"' -> quoted = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val recommendation = Json.decodeFromString<Recommendation>(item.toString())
                                state = State.SEPARATOR
                                emit(recommendation)
                            }
                        }
                    }
                }
                State.SEPARATOR -> when {
                    char.isJsonWhitespace() -> Unit
                    char == ',' -> state = State.ITEM
                    char == ']' -> state = State.END_OBJECT
                    else -> error("Expected array separator")
                }
                State.END_OBJECT -> when {
                    char.isJsonWhitespace() -> Unit
                    char == '}' -> state = State.DONE
                    else -> error("Expected end of response object")
                }
                State.DONE -> check(char.isJsonWhitespace()) { "Trailing output" }
            }
        }
    }

    fun finish(): RecommendationResponse {
        check(state == State.DONE) { "Incomplete recommendation JSON" }
        return Json.decodeFromString<RecommendationResponse>(text.toString())
    }

    private fun Char.isJsonWhitespace() = this == ' ' || this == '\t' || this == '\r' || this == '\n'
}
