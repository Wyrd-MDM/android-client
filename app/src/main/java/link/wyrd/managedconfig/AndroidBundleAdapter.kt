package link.wyrd.managedconfig

import android.os.Bundle
import android.os.Parcelable

object AndroidBundleAdapter {

    fun toBundle(obj: BundleValue.Obj): Bundle {
        val b = Bundle()
        for ((k, v) in obj.v) put(b, k, v)
        return b
    }

    private fun put(b: Bundle, k: String, v: BundleValue) {
        when (v) {
            is BundleValue.Bool -> b.putBoolean(k, v.v)
            is BundleValue.IntV -> b.putInt(k, v.v)
            is BundleValue.LongV -> b.putLong(k, v.v)
            is BundleValue.DoubleV -> b.putDouble(k, v.v)
            is BundleValue.Str -> b.putString(k, v.v)
            is BundleValue.StrArray -> b.putStringArray(k, v.v.toTypedArray())
            is BundleValue.IntArray -> b.putIntArray(k, v.v.toIntArray())
            is BundleValue.Obj -> b.putBundle(k, toBundle(v))
            is BundleValue.ObjArray -> {
                val arr: Array<Parcelable> =
                    v.v.map { toBundle(BundleValue.Obj(it)) as Parcelable }.toTypedArray()
                b.putParcelableArray(k, arr)
            }
        }
    }

    fun fromBundle(b: Bundle?): BundleValue.Obj {
        if (b == null) return BundleValue.Obj(emptyMap())
        val out = linkedMapOf<String, BundleValue>()
        for (k in b.keySet()) {
            val raw = @Suppress("DEPRECATION") b.get(k) ?: continue
            readInto(out, k, raw)
        }
        return BundleValue.Obj(out)
    }

    private fun readInto(out: MutableMap<String, BundleValue>, k: String, raw: Any) {
        val v: BundleValue = when (raw) {
            is Boolean -> BundleValue.Bool(raw)
            is Int -> BundleValue.IntV(raw)
            is Long -> BundleValue.LongV(raw)
            is Float -> BundleValue.DoubleV(raw.toDouble())
            is Double -> BundleValue.DoubleV(raw)
            is String -> BundleValue.Str(raw)
            is Array<*> -> {
                @Suppress("UNCHECKED_CAST")
                when {
                    raw.isArrayOf<String>() -> BundleValue.StrArray((raw as Array<String>).toList())
                    raw.all { it is Bundle } ->
                        BundleValue.ObjArray((raw as Array<Bundle>).map { fromBundle(it).v })
                    else -> return
                }
            }
            is IntArray -> BundleValue.IntArray(raw.toList())
            is Bundle -> fromBundle(raw)
            else -> return
        }
        out[k] = v
    }
}
