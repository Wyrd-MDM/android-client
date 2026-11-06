package link.wyrd.managedconfig

sealed class BundleValue {
    data class Bool(val v: Boolean) : BundleValue()
    data class IntV(val v: Int) : BundleValue()
    data class LongV(val v: Long) : BundleValue()
    data class DoubleV(val v: Double) : BundleValue()
    data class Str(val v: String) : BundleValue()
    data class StrArray(val v: List<String>) : BundleValue()
    data class IntArray(val v: List<Int>) : BundleValue()
    data class Obj(val v: Map<String, BundleValue>) : BundleValue()
    data class ObjArray(val v: List<Map<String, BundleValue>>) : BundleValue()
}
