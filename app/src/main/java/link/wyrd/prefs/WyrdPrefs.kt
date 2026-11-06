package link.wyrd.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import androidx.core.content.edit

class WyrdPrefs private constructor(
    private val sp: SharedPreferences,
    private val deStore: SharedPreferences?,
) {

    var serverUrl: String?
        get() = sp.getString(KEY_SERVER_URL, null)
        set(value) = sp.edit { putString(KEY_SERVER_URL, value) }

    var enrollToken: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(value) = sp.edit {putString(KEY_TOKEN, value)}

    var enrolled: Boolean
        get() = sp.getBoolean(KEY_ENROLLED, false)
        set(value) = sp.edit { putBoolean(KEY_ENROLLED, value)}

    var serverPublicKey: String?
        get() = sp.getString(KEY_SERVER_PUBKEY, null)
        set(value) = sp.edit { putString(KEY_SERVER_PUBKEY, value)}

    var serverSealingKey: String?
        get() = sp.getString(KEY_SERVER_SEALING_KEY, null)
        set(value) = sp.edit { putString(KEY_SERVER_SEALING_KEY, value) }

    var securityLoggingEnabled: Boolean
        get() = sp.getBoolean(KEY_SECURITY_LOGGING_ENABLED, false)
        set(value) = sp.edit { putBoolean(KEY_SECURITY_LOGGING_ENABLED, value) }

    var workProfileProvisioned: Boolean
        get() = sp.getBoolean(KEY_WORK_PROFILE_PROVISIONED, false)
        set(value) = sp.edit { putBoolean(KEY_WORK_PROFILE_PROVISIONED, value) }

    var autoWipeDays: Int
        get() = sp.getInt(KEY_AUTO_WIPE_DAYS, 0)
        set(value) {
            sp.edit { putInt(KEY_AUTO_WIPE_DAYS, value) }
            deStore?.edit { putInt(KEY_AUTO_WIPE_DAYS, value) }
        }

    var autoWipeArmedAt: Long
        get() = sp.getLong(KEY_AUTO_WIPE_ARMED_AT, 0L)
        set(value) {
            sp.edit { putLong(KEY_AUTO_WIPE_ARMED_AT, value) }
            deStore?.edit { putLong(KEY_AUTO_WIPE_ARMED_AT, value) }
        }

    var lastUnlockAt: Long
        get() = sp.getLong(KEY_LAST_UNLOCK_AT, 0L)
        set(value) {
            sp.edit { putLong(KEY_LAST_UNLOCK_AT, value) }
            deStore?.edit { putLong(KEY_LAST_UNLOCK_AT, value) }
        }

    var profileExpiresAt: Long
        get() = sp.getLong(KEY_PROFILE_EXPIRES_AT, 0L)
        set(value) {
            sp.edit { putLong(KEY_PROFILE_EXPIRES_AT, value) }
            deStore?.edit { putLong(KEY_PROFILE_EXPIRES_AT, value) }
        }

    fun nextLogsSeq(): Long {
        val next = sp.getLong(KEY_LOGS_SEQ, 0L) + 1
        sp.edit(commit = true) { putLong(KEY_LOGS_SEQ, next) }
        return next
    }

    var profileExpirySeq: Long
        get() = sp.getLong(KEY_PROFILE_EXPIRY_SEQ, 0L)
        set(value) {
            sp.edit { putLong(KEY_PROFILE_EXPIRY_SEQ, value) }
            deStore?.edit { putLong(KEY_PROFILE_EXPIRY_SEQ, value) }
        }

    var shareLocation: Boolean
        get() = sp.getBoolean(KEY_SHARE_LOCATION, false)
        set(value) = sp.edit { putBoolean(KEY_SHARE_LOCATION, value) }

    var geofence: String?
        get() = sp.getString(KEY_GEOFENCE, null)
        set(value) = sp.edit {
            if (value == null) remove(KEY_GEOFENCE) else putString(KEY_GEOFENCE, value)
        }

    var geofenceSince: Long
        get() = sp.getLong(KEY_GEOFENCE_SINCE, 0L)
        set(value) = sp.edit { putLong(KEY_GEOFENCE_SINCE, value) }

    var geofenceUnknownMinutes: Long
        get() = sp.getLong(KEY_GEOFENCE_UNKNOWN_MINUTES, 0L)
        set(value) = sp.edit { putLong(KEY_GEOFENCE_UNKNOWN_MINUTES, value) }

    var geofenceTimerSince: Long
        get() = sp.getLong(KEY_GEOFENCE_TIMER_SINCE, 0L)
        set(value) = sp.edit { putLong(KEY_GEOFENCE_TIMER_SINCE, value) }

    var geofenceSeenElapsed: Long
        get() = sp.getLong(KEY_GEOFENCE_SEEN_ELAPSED, 0L)
        set(value) = sp.edit { putLong(KEY_GEOFENCE_SEEN_ELAPSED, value) }

    var geofenceSeenBoot: Int
        get() = sp.getInt(KEY_GEOFENCE_SEEN_BOOT, 0)
        set(value) = sp.edit { putInt(KEY_GEOFENCE_SEEN_BOOT, value) }

    var geofenceConditionMissingAt: Long
        get() = sp.getLong(KEY_GEOFENCE_CONDITION_MISSING_AT, 0L)
        set(value) = sp.edit { putLong(KEY_GEOFENCE_CONDITION_MISSING_AT, value) }

    var askedBatteryExemption: Boolean
        get() = sp.getBoolean(KEY_ASKED_BATTERY_EXEMPTION, false)
        set(value) = sp.edit { putBoolean(KEY_ASKED_BATTERY_EXEMPTION, value) }

    var lastLocationSentTs: Long
        get() = sp.getLong(KEY_LAST_LOCATION_SENT_TS, 0L)
        set(value) = sp.edit { putLong(KEY_LAST_LOCATION_SENT_TS, value) }

    var pendingInstall: String?
        get() = sp.getString(KEY_PENDING_INSTALL, null)
        set(value) {
            sp.edit { if (value == null) remove(KEY_PENDING_INSTALL) else putString(KEY_PENDING_INSTALL, value) }
            deStore?.edit { if (value == null) remove(KEY_PENDING_INSTALL) else putString(KEY_PENDING_INSTALL, value) }
        }

    var suspendedRestrictions: String?
        get() = sp.getString(KEY_SUSPENDED_RESTRICTIONS, null)
        set(value) {
            sp.edit { if (value.isNullOrEmpty()) remove(KEY_SUSPENDED_RESTRICTIONS) else putString(KEY_SUSPENDED_RESTRICTIONS, value) }
            deStore?.edit { if (value.isNullOrEmpty()) remove(KEY_SUSPENDED_RESTRICTIONS) else putString(KEY_SUSPENDED_RESTRICTIONS, value) }
        }

    var wipeDeviceWithProfile: Boolean
        get() = sp.getBoolean(KEY_WIPE_DEVICE_WITH_PROFILE, false)
        set(value) {
            sp.edit { putBoolean(KEY_WIPE_DEVICE_WITH_PROFILE, value) }
            deStore?.edit { putBoolean(KEY_WIPE_DEVICE_WITH_PROFILE, value) }
        }

    val deviceId: String
        get() {
            val existing = sp.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val id = UUID.randomUUID().toString()
            sp.edit {putString(KEY_DEVICE_ID, id) }
            return id
        }

    val attestationAlias: String
        get() = "wyrd_attest_v1"

    var simIccidHash: String?
        get() = sp.getString(KEY_SIM_ICCID_HASH, null)
        set(value) = sp.edit { putString(KEY_SIM_ICCID_HASH, value) }

    val simHashSalt: String
        get() {
            val existing = sp.getString(KEY_SIM_HASH_SALT, null)
            if (existing != null) return existing
            val salt = UUID.randomUUID().toString()
            sp.edit { putString(KEY_SIM_HASH_SALT, salt) }
            return salt
        }

    fun syncToDe() {
        val de = deStore ?: return
        de.edit {
            putString(KEY_DEVICE_ID, deviceId)
            for (key in BFU_STRING_KEYS) putString(key, sp.getString(key, null))
            for (key in BFU_BOOL_KEYS) putBoolean(key, sp.getBoolean(key, false))
        }
    }

    companion object {
        private const val PREFS_FILE = "wyrd_secure_prefs"
        private const val DE_PREFS_FILE = "wyrd_de_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_ENROLLED = "enrolled"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_PUBKEY = "server_pubkey_b64"
        private const val KEY_SERVER_SEALING_KEY = "server_sealing_key_b64"
        private const val KEY_LOGS_SEQ = "logs_seq"
        private const val KEY_SECURITY_LOGGING_ENABLED = "security_logging_enabled"
        private const val KEY_WORK_PROFILE_PROVISIONED = "work_profile_provisioned"
        private const val KEY_WIPE_DEVICE_WITH_PROFILE = "wipe_device_with_profile"
        private const val KEY_SHARE_LOCATION = "share_location"
        private const val KEY_GEOFENCE = "geofence"
        private const val KEY_GEOFENCE_SINCE = "geofence_since"
        private const val KEY_GEOFENCE_UNKNOWN_MINUTES = "geofence_unknown_minutes"
        private const val KEY_GEOFENCE_TIMER_SINCE = "geofence_timer_since"
        private const val KEY_GEOFENCE_SEEN_ELAPSED = "geofence_seen_elapsed"
        private const val KEY_GEOFENCE_SEEN_BOOT = "geofence_seen_boot"
        private const val KEY_GEOFENCE_CONDITION_MISSING_AT = "geofence_condition_missing_at"
        private const val KEY_SUSPENDED_RESTRICTIONS = "suspended_restrictions"
        private const val KEY_PENDING_INSTALL = "pending_install"
        private const val KEY_LAST_LOCATION_SENT_TS = "last_location_sent_ts"
        private const val KEY_ASKED_BATTERY_EXEMPTION = "asked_battery_exemption"
        private const val KEY_AUTO_WIPE_DAYS = "auto_wipe_days"
        private const val KEY_LAST_UNLOCK_AT = "last_unlock_at"
        private const val KEY_AUTO_WIPE_ARMED_AT = "auto_wipe_armed_at"
        private const val KEY_PROFILE_EXPIRES_AT = "profile_expires_at"
        private const val KEY_PROFILE_EXPIRY_SEQ = "profile_expiry_seq"
        private const val KEY_SIM_ICCID_HASH = "sim_iccid_hash"
        private const val KEY_SIM_HASH_SALT = "sim_hash_salt"

        private val BFU_STRING_KEYS = listOf(
            KEY_SERVER_URL,
            KEY_TOKEN,
            KEY_SERVER_PUBKEY,
            KEY_SERVER_SEALING_KEY,
        )
        private val BFU_BOOL_KEYS = listOf(
            KEY_ENROLLED,
        )

        @Volatile
        private var instance: WyrdPrefs? = null

        fun get(context: Context): WyrdPrefs {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        fun reload(context: Context): WyrdPrefs {
            return synchronized(this) {
                build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(appCtx: Context): WyrdPrefs {
            val um = appCtx.getSystemService(UserManager::class.java)
            val unlocked = um?.isUserUnlocked ?: true
            val deStore = appCtx.createDeviceProtectedStorageContext()
                .getSharedPreferences(DE_PREFS_FILE, Context.MODE_PRIVATE)
            return if (unlocked) {
                WyrdPrefs(buildEncrypted(appCtx), deStore).also { it.syncToDe() }
            } else {
                WyrdPrefs(deStore, null)
            }
        }

        private fun buildEncrypted(appCtx: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                appCtx,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
