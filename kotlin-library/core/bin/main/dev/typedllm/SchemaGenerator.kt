package dev.typedllm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a JSON Schema from a serializer descriptor. Descriptors are generated at compile time
 * by the kotlinx.serialization plugin, so no runtime reflection is involved.
 *
 * Handles:
 *  - Primitives, enums, lists, maps, nullables
 *  - Recursive types via `$defs` + `$ref` (a type referencing itself through a chain of
 *    other types is emitted once in `$defs` and referenced wherever it appears)
 *  - Sealed-class polymorphism via `oneOf`, each branch pinning the discriminator to its
 *    serial name. The discriminator key defaults to kotlinx.serialization's `type`.
 *  - `@Description("…")` on classes and properties, emitted as JSON Schema `description`
 *
 * Does not yet handle:
 *  - Open polymorphism (`PolymorphicKind.OPEN`) — requires runtime registration
 *  - Validation annotations beyond description (length, range, pattern)
 */
@OptIn(ExperimentalSerializationApi::class)
object SchemaGenerator {

    /** kotlinx.serialization's default discriminator key for sealed hierarchies. */
    private const val DISCRIMINATOR = "type"

    fun generate(descriptor: SerialDescriptor): JsonObject {
        val ctx = GenerationContext()
        val root = ctx.schemaFor(descriptor)

        // Inline when there are no shared definitions — keeps simple schemas simple
        // and matches what every existing caller and test expects.
        if (ctx.defs.isEmpty()) return root

        return buildJsonObject {
            root.forEach { (k, v) -> put(k, v) }
            putJsonObject("\$defs") {
                ctx.defs.forEach { (name, schema) -> put(name, schema) }
            }
        }
    }

    private class GenerationContext {
        /** Shared `$defs` section. Keyed by sanitised type name. */
        val defs = linkedMapOf<String, JsonObject>()

        /** Names currently being generated (ancestors). A hit here means we need a `$ref`. */
        private val inProgress = mutableSetOf<String>()

        fun schemaFor(d: SerialDescriptor): JsonObject {
            val base = when (d.kind) {
                PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string")
                PrimitiveKind.BOOLEAN -> type("boolean")
                PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer")
                PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number")

                SerialKind.ENUM -> buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") { for (i in 0 until d.elementsCount) add(d.getElementName(i)) }
                    descriptionFor(d.annotations)?.let { put("description", it) }
                }

                StructureKind.LIST -> buildJsonObject {
                    put("type", "array")
                    put("items", schemaFor(d.getElementDescriptor(0)))
                    descriptionFor(d.annotations)?.let { put("description", it) }
                }

                StructureKind.MAP -> buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", schemaFor(d.getElementDescriptor(1)))
                    descriptionFor(d.annotations)?.let { put("description", it) }
                }

                StructureKind.CLASS, StructureKind.OBJECT -> structureFor(d)

                PolymorphicKind.SEALED -> sealedFor(d)
                PolymorphicKind.OPEN -> throw UnsupportedTypeException(
                    "Open polymorphism not supported yet: ${d.serialName}. Use a sealed class instead.",
                )

                else -> throw UnsupportedTypeException("Unsupported kind ${d.kind} for ${d.serialName}")
            }
            return if (d.isNullable) nullable(base) else base
        }

        /**
         * Handles a regular class, with cycle detection: once a type's name appears in
         * [inProgress] (i.e. we're already building its schema higher up the stack), subsequent
         * occurrences emit a `$ref` into `$defs` and we materialize the full schema there.
         *
         * Non-recursive types are inlined as before, so simple schemas stay flat.
         */
        private fun structureFor(d: SerialDescriptor): JsonObject {
            val name = sanitize(d.serialName)

            if (name in inProgress) {
                // We're currently building this type higher up the stack. Promote it to a
                // definition (if we haven't already) and return a reference.
                ensureDef(name, d)
                return ref(name)
            }

            if (name in defs) return ref(name)

            inProgress += name
            val schema = try {
                buildObjectSchema(d)
            } finally {
                inProgress -= name
            }

            // Only promote to $defs if this type referenced itself during generation.
            // ensureDef put a placeholder there; replace it with the finished schema.
            if (name in defs) {
                defs[name] = schema
                return ref(name)
            }
            return schema
        }

        private fun buildObjectSchema(d: SerialDescriptor): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for (i in 0 until d.elementsCount) {
                    val fieldSchema = schemaFor(d.getElementDescriptor(i))
                    val fieldDesc = descriptionFor(d.getElementAnnotations(i))
                    val withDesc = if (fieldDesc != null) withDescription(fieldSchema, fieldDesc) else fieldSchema
                    put(d.getElementName(i), withDesc)
                }
            }
            putJsonArray("required") {
                for (i in 0 until d.elementsCount) if (!d.isElementOptional(i)) add(d.getElementName(i))
            }
            put("additionalProperties", false)
            descriptionFor(d.annotations)?.let { put("description", it) }
        }

        /**
         * Emits a sealed hierarchy as `oneOf`: one branch per concrete subclass, each pinning
         * the discriminator (`type`) to that subclass's serial name. kotlinx.serialization
         * encodes sealed instances as `{"type": "<serialName>", ...value fields}` by default,
         * so the generated schema matches the wire format verbatim.
         *
         * Sealed descriptors expose two synthetic elements: index 0 is the discriminator
         * key (ENUM of allowed serial names), index 1 is a CONTEXTUAL element whose
         * child descriptors are the branch classes.
         */
        private fun sealedFor(d: SerialDescriptor): JsonObject {
            val branchesDescriptor = d.getElementDescriptor(1)
            val branches = (0 until branchesDescriptor.elementsCount)
                .map { branchesDescriptor.getElementDescriptor(it) }

            return buildJsonObject {
                descriptionFor(d.annotations)?.let { put("description", it) }
                put("oneOf", buildJsonArray {
                    for (branch in branches) {
                        add(branchObjectSchema(branch))
                    }
                })
            }
        }

        /**
         * Builds the object schema for one sealed branch, splicing the discriminator property
         * in at the top so the model knows exactly which concrete type to produce.
         */
        private fun branchObjectSchema(branch: SerialDescriptor): JsonObject {
            // Branches that are themselves recursive need to live in $defs too.
            val innerName = sanitize(branch.serialName)
            val name = "${innerName}__branch"

            if (name in defs) return refWithDiscriminator(name, branch.serialName)

            inProgress += innerName
            val built = try {
                buildBranchSchema(branch)
            } finally {
                inProgress -= innerName
            }
            // Collapse into $defs only if the branch's own fields were recursive.
            if (innerName in defs) {
                defs[name] = built
                return refWithDiscriminator(name, branch.serialName)
            }
            return built
        }

        private fun buildBranchSchema(branch: SerialDescriptor): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                put(DISCRIMINATOR, buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") { add(branch.serialName) }
                })
                for (i in 0 until branch.elementsCount) {
                    val fieldSchema = schemaFor(branch.getElementDescriptor(i))
                    val fieldDesc = descriptionFor(branch.getElementAnnotations(i))
                    val withDesc = if (fieldDesc != null) withDescription(fieldSchema, fieldDesc) else fieldSchema
                    put(branch.getElementName(i), withDesc)
                }
            }
            putJsonArray("required") {
                add(DISCRIMINATOR)
                for (i in 0 until branch.elementsCount) if (!branch.isElementOptional(i)) add(branch.getElementName(i))
            }
            put("additionalProperties", false)
            descriptionFor(branch.annotations)?.let { put("description", it) }
        }

        /**
         * Reserves a definition slot before the type's schema is finished building. We
         * can't fill in the schema yet because we're mid-recursion; a later call to
         * [structureFor] completes it. The placeholder is enough to let siblings and
         * descendants emit `$ref`s.
         */
        private fun ensureDef(name: String, @Suppress("UNUSED_PARAMETER") d: SerialDescriptor) {
            if (name !in defs) defs[name] = EMPTY_PLACEHOLDER
        }

        private fun ref(name: String): JsonObject = buildJsonObject {
            put("\$ref", "#/\$defs/$name")
        }

        private fun refWithDiscriminator(name: String, discriminatorValue: String): JsonObject = buildJsonObject {
            // A plain $ref can't add extra constraints, so wrap in allOf.
            putJsonArray("allOf") { add(ref(name)) }
            putJsonObject("properties") {
                putJsonObject(DISCRIMINATOR) {
                    put("type", "string")
                    putJsonArray("enum") { add(discriminatorValue) }
                }
            }
        }
    }

    private val EMPTY_PLACEHOLDER = buildJsonObject { }

    private fun type(name: String) = buildJsonObject { put("type", name) }

    private fun nullable(schema: JsonObject) = buildJsonObject {
        putJsonArray("anyOf") {
            add(schema)
            add(type("null"))
        }
    }

    /**
     * Adds a `description` to an existing schema object. If the schema is a `$ref` it's
     * wrapped in `allOf` so the reference stays usable alongside the description, which
     * matches how JSON Schema expects `$ref` to be composed with sibling constraints.
     */
    private fun withDescription(schema: JsonObject, description: String): JsonObject {
        if (schema.containsKey("\$ref")) {
            return buildJsonObject {
                put("description", description)
                putJsonArray("allOf") { add(schema) }
            }
        }
        return buildJsonObject {
            schema.forEach { (k, v) -> put(k, v) }
            put("description", description)
        }
    }

    private fun descriptionFor(annotations: List<Annotation>): String? =
        annotations.filterIsInstance<Description>().firstOrNull()?.value

    /** Serial names can contain dots and generics; collapse to a safe `$defs` key. */
    private fun sanitize(serialName: String): String =
        serialName.removeSuffix("?").replace(Regex("[^A-Za-z0-9_]"), "_")
}