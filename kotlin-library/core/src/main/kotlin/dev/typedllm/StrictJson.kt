package dev.typedllm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks a JSON answer against the target type before kotlinx decodes it. kotlinx accepts "40" for an Int,
 * "true" for a Boolean, and silently keeps the last of two duplicate keys; this pass rejects all three and
 * reports every problem at once, so a retry can fix them together instead of one per attempt.
 * Domain rules in init blocks still run afterwards, during decoding.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object StrictJson {
    private val INTEGER = Regex("""-?\d+""")
    private val PLAIN_TEXT = Regex("""^\s*(?!(?:true|false|null)\b)\p{L}""")

    /** Parses [text], rejecting duplicate keys and plain text. Syntax errors keep kotlinx's message. */
    fun parse(text: String): JsonElement {
        // kotlinx reads a leading bare word like `Sure!` as an unquoted literal, so its error doesn't say this is prose.
        require(PLAIN_TEXT.find(text) == null) { "Expected JSON, but the response is plain text: \"${text.trim().take(40)}\"" }
        val element = Json.parseToJsonElement(text)
        val duplicates = duplicateKeys(text)
        require(duplicates.isEmpty()) { report(duplicates.map { "$it: duplicate key" }) }
        return element
    }

    /** Throws IllegalArgumentException listing every place [element] doesn't fit [descriptor]. */
    fun check(descriptor: SerialDescriptor, element: JsonElement) {
        val problems = mutableListOf<String>()
        visit(descriptor, element, "$", problems)
        require(problems.isEmpty()) { report(problems) }
    }

    private fun report(problems: List<String>) =
        "The response doesn't match the required type:\n" + problems.joinToString("\n")

    private fun visit(d: SerialDescriptor, e: JsonElement, path: String, problems: MutableList<String>) {
        if (e is JsonNull) {
            if (!d.isNullable) problems += "$path: null is not allowed"
            return
        }
        when (d.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> if (!(e is JsonPrimitive && e.isString)) problems += "$path: expected text, got ${describe(e)}"
            PrimitiveKind.BOOLEAN -> if (!(e is JsonPrimitive && !e.isString && e.content in setOf("true", "false"))) problems += "$path: expected true or false, got ${describe(e)}"
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                if (!(e is JsonPrimitive && !e.isString && INTEGER.matches(e.content))) problems += "$path: expected a whole number, got ${describe(e)}"
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE ->
                if (!(e is JsonPrimitive && !e.isString && e.content.toDoubleOrNull() != null)) problems += "$path: expected a number, got ${describe(e)}"
            SerialKind.ENUM -> {
                val allowed = (0 until d.elementsCount).map(d::getElementName)
                if (!(e is JsonPrimitive && e.isString && e.content in allowed)) problems += "$path: ${describe(e)} is not one of ${allowed.joinToString(", ")}"
            }
            StructureKind.LIST ->
                if (e is JsonArray) e.forEachIndexed { i, item -> visit(d.getElementDescriptor(0), item, "$path[$i]", problems) }
                else problems += "$path: expected a list, got ${describe(e)}"
            StructureKind.MAP ->
                if (e is JsonObject) e.forEach { (k, v) -> visit(d.getElementDescriptor(1), v, "$path.$k", problems) }
                else problems += "$path: expected an object, got ${describe(e)}"
            StructureKind.CLASS, StructureKind.OBJECT ->
                if (e is JsonObject) fields(d, e, path, problems, ignore = null)
                else problems += "$path: expected an object, got ${describe(e)}"
            PolymorphicKind.SEALED -> sealed(d, e, path, problems)
            else -> Unit // contextual and open polymorphic types: left to kotlinx
        }
    }

    private fun fields(d: SerialDescriptor, e: JsonObject, path: String, problems: MutableList<String>, ignore: String?) {
        val names = (0 until d.elementsCount).map(d::getElementName)
        for (key in e.keys) if (key !in names && key != ignore) problems += "$path.$key: unknown field"
        for ((i, name) in names.withIndex()) {
            val value = e[name]
            if (value == null) {
                if (!d.isElementOptional(i)) problems += "$path.$name: required field is missing"
            } else visit(d.getElementDescriptor(i), value, "$path.$name", problems)
        }
    }

    private fun sealed(d: SerialDescriptor, e: JsonElement, path: String, problems: MutableList<String>) {
        if (e !is JsonObject) return run { problems += "$path: expected an object, got ${describe(e)}" }
        val branches = d.getElementDescriptor(1).let { b -> (0 until b.elementsCount).map(b::getElementDescriptor) }
        val kinds = branches.map { it.serialName }
        val type = (e["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val branch = branches.firstOrNull { it.serialName == type }
        when {
            type == null -> problems += "$path: missing \"type\", which must be one of ${kinds.joinToString(", ")}"
            branch == null -> problems += "$path.type: \"$type\" is not one of the allowed kinds: ${kinds.joinToString(", ")}"
            else -> fields(branch, e, path, problems, ignore = "type")
        }
    }

    private fun describe(e: JsonElement): String = when {
        e is JsonObject -> "an object"
        e is JsonArray -> "a list"
        e is JsonPrimitive && e.isString -> "the text \"${e.content.take(40)}\""
        else -> (e as JsonPrimitive).content
    }

    /** Paths of keys that appear twice in the same object. Assumes [text] is valid JSON. */
    private fun duplicateKeys(text: String): List<String> {
        class Frame(val isObject: Boolean, val path: String) {
            val keys = mutableSetOf<String>()
            var expectKey = isObject
            var lastKey = ""
            var index = 0
            fun child() = if (isObject) "$path.$lastKey" else "$path[$index]"
        }
        val stack = ArrayDeque<Frame>()
        val duplicates = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> {
                    val start = ++i
                    while (text[i] != '"') i += if (text[i] == '\\') 2 else 1
                    val top = stack.lastOrNull()
                    if (top != null && top.isObject && top.expectKey) {
                        val key = Json.parseToJsonElement(text.substring(start - 1, i + 1)).let { (it as JsonPrimitive).content }
                        if (!top.keys.add(key)) duplicates += "${top.path}.$key"
                        top.lastKey = key
                        top.expectKey = false
                    }
                }
                '{', '[' -> stack.addLast(Frame(c == '{', stack.lastOrNull()?.child() ?: "$"))
                '}', ']' -> stack.removeLast()
                ',' -> stack.lastOrNull()?.let { if (it.isObject) it.expectKey = true else it.index++ }
            }
            i++
        }
        return duplicates
    }
}
