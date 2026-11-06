package link.wyrd.managedconfig

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log
import link.wyrd.admin.WyrdDeviceAdminReceiver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ManagedConfigActions(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    private val admin = WyrdDeviceAdminReceiver.component(context)

    fun apply(packageName: String, config: JsonObject) {
        val bundleValue = JsonBundleCodec.fromJson(config)
        val bundle = AndroidBundleAdapter.toBundle(bundleValue)
        Log.i(TAG, "applying managed config to $packageName (${config.size} keys)")
        dpm.setApplicationRestrictions(admin, packageName, bundle)
    }

    fun getSchema(packageName: String): JsonObject =
        SchemaCodec.fetchAndEncode(context, packageName)

    fun getApplied(packageName: String): JsonObject {
        val bundle = dpm.getApplicationRestrictions(admin, packageName)
        val bv = AndroidBundleAdapter.fromBundle(bundle)
        return buildJsonObject {
            put("packageName", packageName)
            put("config", JsonBundleCodec.toJson(bv))
        }
    }

    companion object {
        private const val TAG = "ManagedConfig"
    }
}
