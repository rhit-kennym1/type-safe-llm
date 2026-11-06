package dev.typedllm

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

/**
 * Attaches a human-readable description to a field or class, surfaced to the model via
 * the generated JSON Schema's `description` key. Field-level descriptions dramatically
 * improve model output quality because the model sees what each field *means*, not just
 * its name and type.
 *
 * ```
 * @Serializable
 * data class Invoice(
 *     @Description("ISO-4217 currency code, uppercase (e.g. USD, EUR)")
 *     val currency: String,
 *     @Description("Total before tax, in minor units (cents)")
 *     val subtotalCents: Int,
 * )
 * ```
 *
 * The annotation is retained in the compile-time descriptor by kotlinx.serialization's
 * `@SerialInfo` mechanism, so no runtime reflection is involved.
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Description(val value: String)
