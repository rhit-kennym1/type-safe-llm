package dev.typedllm.demoui

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/** Turns a type's compile-time description into the plain "Fields we asked for" table. */
@OptIn(ExperimentalSerializationApi::class)
internal object FieldDescriber {

    fun describe(descriptor: SerialDescriptor): List<FieldView> =
        (0 until descriptor.elementsCount).map { i ->
            FieldView(
                name = descriptor.getElementName(i),
                type = label(descriptor.getElementDescriptor(i)),
                required = !descriptor.isElementOptional(i),
            )
        }

    private fun label(d: SerialDescriptor): String = when (d.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> "text"
        PrimitiveKind.BOOLEAN -> "yes or no"
        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "whole number"
        PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "number"
        SerialKind.ENUM -> "one of " + (0 until d.elementsCount).joinToString(", ") { d.getElementName(it) }
        StructureKind.LIST -> "list of " + label(d.getElementDescriptor(0))
        StructureKind.MAP -> "lookup table"
        StructureKind.CLASS, StructureKind.OBJECT -> "group of fields"
        else -> "value"
    }
}
