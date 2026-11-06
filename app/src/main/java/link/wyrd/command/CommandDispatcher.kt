package link.wyrd.command

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.UserManager
import android.util.Base64
import android.util.Log
import link.wyrd.admin.ClockLock
import link.wyrd.admin.DpmActions
import link.wyrd.admin.ProfileWipe
import link.wyrd.enroll.AttestationManager
import link.wyrd.geo.GeofenceEnforcer
import link.wyrd.install.ApkInstaller
import link.wyrd.inventory.InventoryCollector
import link.wyrd.inventory.LocationReporter
import link.wyrd.managedconfig.JsonBundleCodec
import link.wyrd.managedconfig.ManagedConfigActions
import link.wyrd.net.Command
import link.wyrd.net.CommandResult
import link.wyrd.net.onServer
import link.wyrd.prefs.WyrdPrefs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class CommandDispatcher(private val context: Context) {

    private val dpm = DpmActions(context)
    private val installer = ApkInstaller(context)
    private val managed = ManagedConfigActions(context)
    private val inventory = InventoryCollector(context)
    private val location = LocationReporter(context)
    private val compactJson = Json { encodeDefaults = true }

    fun handle(cmd: Command): CommandResult {
        return try {
            dispatch(cmd)
        } catch (t: Throwable) {
            Log.w(TAG, "command ${cmd.id}/${cmd.type} failed", t)
            CommandResult(cmd.id, false, t.message ?: t::class.java.simpleName)
        }
    }

    private fun dispatch(cmd: Command): CommandResult {
        when (cmd.type) {
            "LOCK_NOW" -> dpm.lockNow()
            "WIPE_PROFILE" -> return handleWipeProfile(cmd)
            "SET_AUTO_WIPE" -> {
                val days = cmd.payload.intField("days") ?: 0
                val prefs = WyrdPrefs.get(context)
                if (days != prefs.autoWipeDays) {
                    prefs.autoWipeArmedAt = System.currentTimeMillis() / 1000
                }
                prefs.autoWipeDays = days
                Log.i(TAG, "auto-wipe window set to $days day(s)")
                ClockLock.reconcile(context)
            }
            "SET_PROFILE_EXPIRY" -> {
                val at = cmd.payload.longField("at")
                val seq = cmd.payload.longField("seq") ?: return CommandResult(cmd.id, false, "invalid_seq")
                if (!ProfileExpiry.receive(context, at, seq)) return CommandResult(cmd.id, false, "invalid_at")
                Log.i(TAG, "profile end date $seq: $at")
            }
            "SET_SERVER_URL" -> {
                val url = cmd.payload.stringField("url")?.trim()
                    ?.takeIf { it.startsWith("https://") }
                    ?: return CommandResult(cmd.id, false, "invalid_url")
                WyrdPrefs.get(context).apply {
                    serverUrl = url
                    syncToDe()
                }
                Log.i(TAG, "server address moved")
            }
            "DISABLE_CAMERA" ->
                dpm.setCameraDisabled(cmd.payload.boolField("disabled") ?: true)
            "INSTALL_APK" -> {
                val url = cmd.payload.stringField("url")
                    ?: return CommandResult(cmd.id, false, "missing url")
                if (!onServer(url, WyrdPrefs.get(context).serverUrl)) {
                    return CommandResult(cmd.id, false, "url_not_on_server")
                }
                val pkg = cmd.payload.stringField("packageName")
                    ?: return CommandResult(cmd.id, false, "missing packageName")
                val expected = cmd.payload.longField("versionCode")
                return when (val r = installer.installFromUrl(url, pkg, cmd.id, expected)) {
                    is ApkInstaller.Result.Success -> CommandResult(cmd.id, true)
                    is ApkInstaller.Result.Failure -> CommandResult(cmd.id, false, r.reason)
                }
            }
            "SET_SCREEN_CAPTURE_DISABLED" ->
                dpm.setScreenCaptureDisabled(cmd.payload.boolField("disabled") ?: true)
            "SET_GEOFENCE" -> {
                val enabled = cmd.payload.boolField("enabled") ?: false
                val prefs = WyrdPrefs.get(context)
                val since = when {
                    !enabled -> 0L
                    prefs.geofence == null -> System.currentTimeMillis()
                    else -> prefs.geofenceSince
                }
                if (since != prefs.geofenceSince) {
                    prefs.geofenceSeenElapsed = 0L
                    prefs.geofenceConditionMissingAt = 0L
                }
                prefs.geofenceSince = since
                prefs.geofence = if (enabled) cmd.payload.toString() else null
                ClockLock.reconcile(context)
                if (enabled) GeofenceEnforcer.checkSoon(context)
            }
            "SET_GEOFENCE_TIMER" -> {
                val minutes = (cmd.payload.longField("minutes") ?: 0L).coerceAtLeast(0L)
                val prefs = WyrdPrefs.get(context)
                if (minutes != prefs.geofenceUnknownMinutes) {
                    prefs.geofenceTimerSince = System.currentTimeMillis()
                    prefs.geofenceSeenElapsed = 0L
                }
                prefs.geofenceUnknownMinutes = minutes
                GeofenceEnforcer.checkSoon(context)
            }
            "SET_USER_RESTRICTION" -> {
                val restriction = cmd.payload.stringField("restriction")
                    ?: return CommandResult(cmd.id, false, "missing restriction")
                if (restriction !in POLICY_RESTRICTIONS) {
                    return CommandResult(cmd.id, false, "restriction_not_allowed")
                }
                val enabled = cmd.payload.boolField("enabled") ?: true
                dpm.setUserRestriction(restriction, enabled)
            }
            "GET_DEVICE_INVENTORY" -> return ackWithJson(cmd.id, inventory.collect())
            "REPORT_LOCATION" -> {
                if (!WyrdPrefs.get(context).shareLocation) {
                    return CommandResult(cmd.id, false, "location_not_consented")
                }
                val json = location.getLocation()
                val failure = json["error"]?.jsonPrimitive?.contentOrNull
                if (failure != null) return CommandResult(cmd.id, false, failure)
                return ackWithJson(cmd.id, json)
            }
            "APPLY_MANAGED_CONFIG" -> return handleApplyManagedConfig(cmd)
            "GET_RESTRICTION_SCHEMA" -> return handleGetSchema(cmd)
            "GET_MANAGED_CONFIG" -> return handleGetApplied(cmd)
            "REQUEST_ATTESTATION" -> return handleRequestAttestation(cmd)
            "ENABLE_SECURITY_LOGGING" -> return handleSetSecurityLogging(cmd, true)
            "DISABLE_SECURITY_LOGGING" -> return handleSetSecurityLogging(cmd, false)
            "ENABLE_NETWORK_LOGGING" -> return handleSetNetworkLogging(cmd, true)
            "DISABLE_NETWORK_LOGGING" -> return handleSetNetworkLogging(cmd, false)
            else -> return CommandResult(cmd.id, false, "unknown command ${cmd.type}")
        }
        return CommandResult(cmd.id, true)
    }

    private fun handleWipeProfile(cmd: Command): CommandResult {
        ProfileWipe.wipe(context, "WIPE_PROFILE")
        return CommandResult(cmd.id, true)
    }

    private fun handleApplyManagedConfig(cmd: Command): CommandResult {
        val pkg = cmd.payload.stringField("packageName")
            ?: return CommandResult(cmd.id, false, "missing_package_name")
        val configEl = cmd.payload["config"]
            ?: return CommandResult(cmd.id, false, "invalid_config_field")
        val configObj = runCatching { configEl.jsonObject }.getOrNull()
            ?: return CommandResult(cmd.id, false, "invalid_config_field")
        return try {
            managed.apply(pkg, configObj)
            CommandResult(cmd.id, true)
        } catch (e: JsonBundleCodec.CodecError) {
            CommandResult(cmd.id, false, e.code)
        } catch (t: Throwable) {
            CommandResult(cmd.id, false, "dpm_error: ${t.message ?: t::class.java.simpleName}")
        }
    }

    private fun handleGetSchema(cmd: Command): CommandResult {
        val pkg = cmd.payload.stringField("packageName")
            ?: return CommandResult(cmd.id, false, "missing_package_name")
        val json = managed.getSchema(pkg)
        return ackWithJson(cmd.id, json)
    }

    private fun handleGetApplied(cmd: Command): CommandResult {
        val pkg = cmd.payload.stringField("packageName")
            ?: return CommandResult(cmd.id, false, "missing_package_name")
        return try {
            val json = managed.getApplied(pkg)
            ackWithJson(cmd.id, json)
        } catch (t: Throwable) {
            CommandResult(cmd.id, false, "dpm_error: ${t.message ?: t::class.java.simpleName}")
        }
    }

    private fun handleSetSecurityLogging(cmd: Command, enabled: Boolean): CommandResult {
        val ok = dpm.setSecurityLoggingEnabled(enabled)
        Log.d(TAG, "handleSetSecurityLogging($enabled), ok = $ok")
        if (!ok) return CommandResult(cmd.id, false, "dpm_call_rejected")
        WyrdPrefs.get(context).securityLoggingEnabled = enabled
        return CommandResult(cmd.id, true)
    }

    private fun handleSetNetworkLogging(cmd: Command, enabled: Boolean): CommandResult {
        val ok = dpm.setNetworkLoggingEnabled(enabled)
        Log.d(TAG, "handleSetNetworkLogging($enabled), ok = $ok")
        if (!ok) return CommandResult(cmd.id, false, "dpm_call_rejected")
        return CommandResult(cmd.id, true)
    }

    private fun handleRequestAttestation(cmd: Command): CommandResult {
        val nonce = cmd.payload.stringField("nonce")
            ?: return CommandResult(cmd.id, false, "missing_nonce")
        return try {
            val chain = AttestationManager.produceAttestationChain(nonce)
            val b64s = chain.map { Base64.encodeToString(it.encoded, Base64.NO_WRAP) }
            val json = buildJsonObject {
                put("chain", JsonArray(b64s.map { JsonPrimitive(it) }))
            }
            ackWithJson(cmd.id, json)
        } catch (t: Throwable) {
            Log.w(TAG, "reattestation failed", t)
            CommandResult(cmd.id, false, "attestation_unavailable: ${t.message ?: t::class.java.simpleName}")
        }
    }

    private fun ackWithJson(id: String, json: JsonObject): CommandResult {
        val text = compactJson.encodeToString(JsonObject.serializer(), json)
        if (text.toByteArray(Charsets.UTF_8).size > MAX_ACK_MSG_BYTES) {
            return CommandResult(id, false, "ack_message_too_large")
        }
        return CommandResult(id, true, text)
    }

    companion object {
        private const val TAG = "CmdDispatcher"

        val POLICY_RESTRICTIONS = listOf(
            UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
        )

        private const val MAX_ACK_MSG_BYTES = 60_000

        private fun JsonObject.stringField(name: String): String? =
            this[name]?.jsonPrimitive?.contentOrNullSafe()

        private fun JsonObject.intField(name: String): Int? =
            this[name]?.jsonPrimitive?.intOrNull

        private fun JsonObject.longField(name: String): Long? =
            this[name]?.jsonPrimitive?.longOrNull

        private fun JsonObject.boolField(name: String): Boolean? =
            this[name]?.jsonPrimitive?.booleanOrNull

        private fun JsonPrimitive.contentOrNullSafe(): String? =
            if (isString) content else content.takeIf { it != "null" }
    }
}
