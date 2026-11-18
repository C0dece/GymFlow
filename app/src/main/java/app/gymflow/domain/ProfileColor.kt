package app.gymflow.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

object ProfileColor : KSerializer<Int?> {
    private val canonical = Int.serializer().nullable
    override val descriptor: SerialDescriptor get() = canonical.descriptor

    fun parse(text: String): Int? {
        val value = text.trim()
        val hex = when {
            value.startsWith("#") -> value.drop(1)
            value.startsWith("0x", ignoreCase = true) -> value.drop(2)
            else -> null
        }
        val argb = if (hex != null) {
            hex.takeIf { it.length == 6 || it.length == 8 }?.toLongOrNull(16)
        } else value.toLongOrNull()
        return argb?.takeIf { it in Int.MIN_VALUE.toLong()..0xFFFFFFFFL }
            ?.let { it.toInt() or 0xFF000000.toInt() }
    }

    override fun deserialize(decoder: Decoder): Int? = if (decoder is JsonDecoder) {
        (decoder.decodeJsonElement() as? JsonPrimitive)?.content?.let(::parse)
    } else canonical.deserialize(decoder)

    override fun serialize(encoder: Encoder, value: Int?) = canonical.serialize(encoder, value?.let { it or 0xFF000000.toInt() })
}
