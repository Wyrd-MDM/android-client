package link.wyrd.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log

class DpmActions(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    private val admin: ComponentName = WyrdDeviceAdminReceiver.component(context)

    val isProfileOwner: Boolean get() = dpm.isProfileOwnerApp(context.packageName)

    val isOrgOwnedProfile: Boolean
        get() = isProfileOwner &&
            runCatching { dpm.isOrganizationOwnedDeviceWithManagedProfile }
                .getOrDefault(false)

    fun lockNow() {
        if (isOrgOwnedProfile) {
            val locked = runCatching {
                dpm.getParentProfileInstance(admin).lockNow()
                true
            }.getOrElse {
                Log.w(TAG, "parent lock refused; locking the profile instead", it)
                false
            }
            if (locked) return
        }
        dpm.lockNow()
    }

    fun wipeDevice() {
        check(isOrgOwnedProfile) { "resetting the phone needs an organization-owned profile" }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            dpm.wipeDevice(0)
        } else {
            @Suppress("DEPRECATION")
            dpm.wipeData(0)
        }
    }

    fun wipeProfile() {
        @Suppress("DEPRECATION")
        dpm.wipeData(0)
    }

    fun setCameraDisabled(disabled: Boolean) =
        dpm.setCameraDisabled(admin, disabled)

    fun setScreenCaptureDisabled(disabled: Boolean) =
        dpm.setScreenCaptureDisabled(admin, disabled)

    fun setUserRestriction(restriction: String, enabled: Boolean, onParent: Boolean = false) {
        val target = if (onParent) dpm.getParentProfileInstance(admin) else dpm
        if (enabled) {
            target.addUserRestriction(admin, restriction)
        } else {
            target.clearUserRestriction(admin, restriction)
        }
    }

    fun setAutoTimeEnabled(enabled: Boolean) = dpm.setAutoTimeEnabled(admin, enabled)

    fun hasUserRestriction(restriction: String, onParent: Boolean = false): Boolean? = runCatching {
        val target = if (onParent) dpm.getParentProfileInstance(admin) else dpm
        target.getUserRestrictions(admin).getBoolean(restriction)
    }.getOrNull()

    fun suspendInstallRestrictions(): AutoCloseable {
        val um = context.getSystemService(UserManager::class.java)
        val blocking = listOf(
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        )
        val held = blocking.filter { um?.hasUserRestriction(it) == true }
        link.wyrd.prefs.WyrdPrefs.get(context).suspendedRestrictions = held.joinToString("|")
        for (r in held) {
            try {
                dpm.clearUserRestriction(admin, r)
            } catch (t: Throwable) {
                Log.w(TAG, "could not lift $r for an install", t)
            }
        }
        if (held.isNotEmpty()) Log.i(TAG, "install: lifted $held")
        return AutoCloseable {
            for (r in held) {
                try {
                    dpm.addUserRestriction(admin, r)
                } catch (t: Throwable) {
                    Log.w(TAG, "could not restore $r after an install", t)
                }
            }
            if (held.isNotEmpty()) Log.i(TAG, "install: restored $held")
            link.wyrd.prefs.WyrdPrefs.get(context).suspendedRestrictions = null
        }
    }

    fun restoreSuspendedRestrictions() {
        val prefs = link.wyrd.prefs.WyrdPrefs.get(context)
        val owed = prefs.suspendedRestrictions?.split("|")?.filter { it.isNotBlank() } ?: return
        if (owed.isEmpty()) {
            prefs.suspendedRestrictions = null
            return
        }
        for (r in owed) {
            runCatching { dpm.addUserRestriction(admin, r) }
                .onFailure { Log.w(TAG, "could not put $r back", it) }
        }
        Log.i(TAG, "install: restored after restart $owed")
        prefs.suspendedRestrictions = null
    }

    private val canDriveLogging: Boolean get() = isOrgOwnedProfile

    fun setSecurityLoggingEnabled(enabled: Boolean): Boolean {
        if (!canDriveLogging) return false
        return try {
            dpm.setSecurityLoggingEnabled(admin, enabled)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    fun setNetworkLoggingEnabled(enabled: Boolean): Boolean {
        if (!canDriveLogging) return false
        return try {
            dpm.setNetworkLoggingEnabled(admin, enabled)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    fun retrieveSecurityLogs(): List<android.app.admin.SecurityLog.SecurityEvent> {
        if (!canDriveLogging) return emptyList()
        return try {
            dpm.retrieveSecurityLogs(admin) ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun retrievePreRebootSecurityLogs(): List<android.app.admin.SecurityLog.SecurityEvent> {
        if (!canDriveLogging) return emptyList()
        return try {
            dpm.retrievePreRebootSecurityLogs(admin) ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun retrieveNetworkLogs(batchToken: Long): List<android.app.admin.NetworkEvent> {
        if (!canDriveLogging) return emptyList()
        return try {
            dpm.retrieveNetworkLogs(admin, batchToken) ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun lockSelfRuntimePermissions() {
        if (!isProfileOwner) return
        Log.i(TAG, "Locking runtime permissions")
        val pkg = context.packageName
        val perms = listOf(
            android.Manifest.permission.INTERNET,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_PHONE_STATE,
        )
        for (perm in perms) {
            val ok = runCatching {
                dpm.setPermissionGrantState(
                    admin,
                    pkg,
                    perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.getOrDefault(false)
            Log.i(TAG, "lockSelfRuntimePermissions: $perm -> $ok")
        }
    }

    fun grantSelfBackgroundLocation(): Boolean {
        if (!isProfileOwner) return false
        val ok = runCatching {
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }.getOrDefault(false)
        Log.i(TAG, "grantSelfBackgroundLocation -> $ok")
        return ok
    }

    companion object {
        private const val TAG = "DpmActions"
    }

}
