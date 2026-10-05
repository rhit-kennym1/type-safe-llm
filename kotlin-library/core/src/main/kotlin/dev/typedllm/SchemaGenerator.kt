package dev.typedllm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a JSON Schema from a serializer descriptor. Descriptors are generated at compile time
 * by the kotlinx.serialization plugin, so no runtime reflection is involved.
 */
@OptIn(ExperimentalSerializationApi::class)
object SchemaGenerator {

    fun generate(descriptor: SerialDescriptor): JsonObject = schemaFor(descriptor, emptySet())

    private fun schemaFor(d: SerialDescriptor, stack: Set<String>): JsonObject {
        val base = when (d.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string")
            PrimitiveKind.BOOLEAN -> type("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")

            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { for (i in 0 until d.elementsCount) add(d.getElementName(i)) }
            }

            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schemaFor(d.getElementDescriptor(0), stack))
            }

            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", schemaFor(d.getElementDescriptor(1), stack))
            }

            StructureKind.CLASS, StructureKind.OBJECT -> {
                val name = d.serialName.removeSuffix("?")
                if (name in stack) throw UnsupportedTypeException("Recursive type not supported yet: $name")
                val inner = stack + name
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        for (i in 0 until d.elementsCount) put(d.getElementName(i), schemaFor(d.getElementDescriptor(i), inner))
                    }
                    putJsonArray("required") {
                        for (i in 0 until d.elementsCount) if (!d.isElementOptional(i)) add(d.getElementName(i))
                    }
                    put("additionalProperties", false)
                }
            }

            else -> throw UnsupportedTypeException("Unsupported kind ${d.kind} for ${d.serialName}")
        }
        return if (d.isNullable) nullable(base) else base
    }

    private fun type(name: String) = buildJsonObject { put("type", name) }

    private fun nullable(schema: JsonObject) = buildJsonObject {
        putJsonArray("anyOf") {
            add(schema)
            add(type("null"))
        }
    }
}
