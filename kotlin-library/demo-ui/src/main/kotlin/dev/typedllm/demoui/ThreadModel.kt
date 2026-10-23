package dev.typedllm.demoui

import dev.typedllm.Description
import kotlinx.serialization.Serializable

// Showcases RECURSIVE TYPES. The library promotes CommentNode into `$defs` and references
// it from `replies.items`, so the model knows the shape is uniform at every depth.

@Serializable
@Description("One node in a nested comment thread. Reply trees can be arbitrarily deep.")
data class CommentNode(
    @Description("Display name of whoever wrote this comment")
    val author: String,
    @Description("The body of this specific comment, in plain text")
    val body: String,
    @Description("Direct replies to this comment, each itself a CommentNode")
    val replies: List<CommentNode> = emptyList(),
)

internal val THREAD_SOURCE = """
    @Serializable
    data class CommentNode(
        val author: String,
        val body: String,
        val replies: List<CommentNode> = emptyList(),   // recursive!
    )
""".trimIndent()
