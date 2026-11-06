package link.wyrd.managedconfig

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

object JsonBundleCodec {

    class CodecError(val code: String, message: String) : RuntimeException("$code: $message")

    fun fromJson(root: JsonObject): BundleValue.Obj = fromElement(root, "") as BundleValue.Obj

    private fun fromElement(el: JsonElement, path: String): BundleValue {
        return when (el) {
            is JsonNull -> throw CodecError(
                "null_values_not_supported",
                "null at ${pathLabel(path)}"
            )
            is JsonPrimitive -> fromPrimitive(el, path)
            is JsonObject -> BundleValue.Obj(el.mapValues { (k, v) -> fromElement(v, child(path, k)) })
            is JsonArray -> fromArray(el, path)
        }
    }

    private fun fromPrimitive(p: JsonPrimitive, path: String): BundleValue {
        if (!p.isString) {
            p.booleanOrNull?.let { return BundleValue.Bool(it) }
            val content = p.content
            if (content.contains('.') || content.contains('e') || content.contains('E')) {
                content.toDoubleOrNull()?.let { return BundleValue.DoubleV(it) }
                throw CodecError("invalid_number", "not a number at ${pathLabel(path)}: $content")
            }
            content.toIntOrNull()?.let { return BundleValue.IntV(it) }
            content.toLongOrNull()?.let { return BundleValue.LongV(it) }
            content.toDoubleOrNull()?.let { return BundleValue.DoubleV(it) }
            throw CodecError("invalid_number", "unparseable number at ${pathLabel(path)}: $content")
        }
        val s = p.content
        if (s.startsWith("long:")) {
            val rest = s.substring(5)
            val parsed = rest.toLongOrNull()
                ?: throw CodecError("invalid_long_prefix", "\"$s\" at ${pathLabel(path)} — digits must parse as i64")
            return BundleValue.LongV(parsed)
        }
        return BundleValue.Str(s)
    }

    private fun fromArray(arr: JsonArray, path: String): BundleValue {
        if (arr.isEmpty()) return BundleValue.StrArray(emptyList())

        val kinds = arr.mapIndexed { i, e -> elementKind(e, child(path, "[$i]")) }
        val first = kinds.first()
        if (kinds.any { it != first }) {
            throw CodecError("heterogeneous_array", "mixed types at ${pathLabel(path)}")
        }

        return when (first) {
            ElementKind.STRING -> BundleValue.StrArray(
                arr.mapIndexed { i, e -> stringOf(e, child(path, "[$i]")) }
            )
            ElementKind.INT -> BundleValue.IntArray(
                arr.mapIndexed { i, e -> intOf(e, child(path, "[$i]")) }
            )
            ElementKind.OBJECT -> BundleValue.ObjArray(
                arr.mapIndexed { i, e ->
                    val inner = fromElement(e, child(path, "[$i]")) as BundleValue.Obj
                    inner.v
                }
            )
            ElementKind.BOOL -> throw CodecError(
                "bool_array_unsupported",
                "boolean array at ${pathLabel(path)} — Android managed configs don't support this"
            )
            ElementKind.DOUBLE -> throw CodecError(
                "double_array_unsupported",
                "double array at ${pathLabel(path)}"
            )
        }
    }

    private enum class ElementKind { STRING, INT, DOUBLE, BOOL, OBJECT }

    private fun elementKind(el: JsonElement, path: String): ElementKind {
        return when (el) {
            is JsonNull -> throw CodecError("null_values_not_supported", "null at $path")
            is JsonObject -> ElementKind.OBJECT
            is JsonArray -> throw CodecError("nested_array", "array-of-arrays not supported at $path")
            is JsonPrimitive -> when {
                el.isString -> ElementKind.STRING
                el.booleanOrNull != null -> ElementKind.BOOL
                el.content.contains('.') || el.content.contains('e') || el.content.contains('E') ->
                    ElementKind.DOUBLE
                else -> ElementKind.INT
            }
        }
    }

    private fun stringOf(el: JsonElement, path: String): String {
        val p = el as? JsonPrimitive ?: throw CodecError("heterogeneous_array", "expected string at $path")
        if (!p.isString) throw CodecError("heterogeneous_array", "expected string at $path")
        return p.content
    }

    private fun intOf(el: JsonElement, path: String): Int {
        val p = el as? JsonPrimitive ?: throw CodecError("heterogeneous_array", "expected int at $path")
        if (p.isString) throw CodecError("heterogeneous_array", "expected int at $path")
        return p.content.toIntOrNull()
            ?: throw CodecError("int_out_of_range", "not an i32 at $path: ${p.content}")
    }

    fun toJson(root: BundleValue.Obj): JsonObject = objToJson(root)

    private fun objToJson(obj: BundleValue.Obj): JsonObject =
        buildJsonObject {
            for ((k, v) in obj.v) put(k, valueToJson(v))
        }

    private fun valueToJson(v: BundleValue): JsonElement = when (v) {
        is BundleValue.Bool -> JsonPrimitive(v.v)
        is BundleValue.IntV -> JsonPrimitive(v.v)
        is BundleValue.LongV -> JsonPrimitive("long:${v.v}")
        is BundleValue.DoubleV -> JsonPrimitive(v.v)
        is BundleValue.Str -> JsonPrimitive(v.v)
        is BundleValue.StrArray -> buildJsonArray { v.v.forEach { add(JsonPrimitive(it)) } }
        is BundleValue.IntArray -> buildJsonArray { v.v.forEach { add(JsonPrimitive(it)) } }
        is BundleValue.Obj -> objToJson(v)
        is BundleValue.ObjArray -> buildJsonArray {
            v.v.forEach { add(objToJson(BundleValue.Obj(it))) }
        }
    }

    private fun pathLabel(path: String): String = if (path.isEmpty()) "<root>" else path
    private fun child(parent: String, field: String): String =
        if (parent.isEmpty()) field else if (field.startsWith("[")) "$parent$field" else "$parent.$field"

    private val JsonPrimitive.booleanOrNull: Boolean?
        get() = if (!isString) when (content) {
            "true" -> true
            "false" -> false
            else -> null
        } else null
}
