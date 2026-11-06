package link.wyrd

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.UserManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.card.MaterialCardView
import link.wyrd.admin.BatteryExemption
import link.wyrd.admin.DpmActions
import link.wyrd.admin.WyrdDeviceAdminReceiver
import link.wyrd.command.AutoWipe
import link.wyrd.command.ProfileExpiry
import link.wyrd.databinding.ActivityStatusBinding
import link.wyrd.geo.Geofence
import link.wyrd.geo.LocationShare
import link.wyrd.geo.Requirement
import link.wyrd.prefs.WyrdPrefs
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class StatusActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatusBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()
        buildSections()
    }

    override fun onResume() {
        super.onResume()
        LocationShare.reconcile(this)?.let { showUnmet(it) }
        buildSections()
        BatteryExemption.askOnce(this)
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.upperScroll.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
    }

    private fun buildSections() {
        val root = binding.sectionsContainer
        root.removeAllViews()

        sectionCard(root, getString(R.string.section_data)).apply {
            row(this, R.string.data_location, R.string.data_location_sub)
            row(this, R.string.data_inventory, R.string.data_inventory_sub)
            row(this, R.string.data_status, R.string.data_status_sub)
            row(this, R.string.data_unlocks, R.string.data_unlocks_sub)
            row(this, R.string.data_attestation, R.string.data_attestation_sub)
            row(this, R.string.data_debugging, R.string.data_debugging_sub)
            row(this, R.string.data_sim, R.string.data_sim_sub)
            row(this, R.string.data_logs, R.string.data_logs_sub)
            row(this, R.string.data_removal, R.string.data_removal_sub)
        }

        sectionCard(root, getString(R.string.section_permissions)).apply {
            permRow(
                this, R.string.perm_precise, R.string.perm_precise_sub,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) { askForLocationPermission() }
            permRow(
                this, R.string.perm_background, R.string.perm_background_sub,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) { askForBackgroundLocation() }
            permRow(this, R.string.perm_phone, R.string.perm_phone_sub, Manifest.permission.READ_PHONE_STATE)
            permRow(this, R.string.perm_notifications, R.string.perm_notifications_sub, Manifest.permission.POST_NOTIFICATIONS)
            row(this, R.string.perm_apps, R.string.perm_apps_sub, Status(getString(R.string.chip_held), R.color.wyrd_green))
            addPositionSources(this)
            addBatteryExemption(this)
        }

        sectionCard(root, getString(R.string.section_remote)).apply {
            row(this, R.string.remote_lock, R.string.remote_lock_sub)
            row(this, R.string.remote_wipe, R.string.remote_wipe_sub)
            row(this, R.string.remote_apps, R.string.remote_apps_sub)
            row(this, R.string.remote_policies, R.string.remote_policies_sub)
            row(this, R.string.remote_camera, R.string.remote_camera_sub)
            row(this, R.string.remote_screenshots, R.string.remote_screenshots_sub)
            row(this, R.string.remote_reattest, R.string.remote_reattest_sub)
            row(this, R.string.remote_logs, R.string.remote_logs_sub)
            addUnlockTimer(this)
            addProfileExpiry(this)
        }

        sectionCard(root, getString(R.string.section_restrictions)).apply {
            addRestrictions(this)
        }

        if (isProfileOwner()) {
            sectionCard(root, getString(R.string.section_choice)).apply {
                val prefs = WyrdPrefs.get(this@StatusActivity)
                switchRow(
                    this,
                    R.string.choice_share,
                    if (Geofence.isActive(this@StatusActivity)) R.string.share_locked_by_geofence
                    else R.string.choice_share_sub,
                    prefs.shareLocation,
                ) { on -> onShareSwitch(on) }
                switchRow(
                    this,
                    R.string.choice_wipe_phone,
                    R.string.choice_wipe_phone_sub,
                    prefs.wipeDeviceWithProfile,
                ) { on -> prefs.wipeDeviceWithProfile = on }
            }
        }
    }

    private fun onShareSwitch(on: Boolean) {
        if (!on) {
            if (Geofence.isActive(this)) {
                toast(getString(R.string.share_locked_by_geofence))
                buildSections()
                return
            }
            LocationShare.disable(this)
            buildSections()
            return
        }
        when (val r = LocationShare.enable(this)) {
            LocationShare.Outcome.On -> {}
            is LocationShare.Outcome.Unmet -> showUnmet(r.missing)
            LocationShare.Outcome.LockRefused -> toast(getString(R.string.share_lock_refused))
        }
        buildSections()
    }

    private fun showUnmet(missing: List<Requirement>) {
        val list = missing.joinToString("\n") { "\u2022 " + getString(LocationShare.label(it)) }
        AlertDialog.Builder(this)
            .setTitle(R.string.share_unmet_title)
            .setMessage(getString(R.string.share_unmet_intro) + "\n\n" + list)
            .setPositiveButton(R.string.share_fix) { _, _ -> fix(missing.first()) }
            .setNegativeButton(R.string.share_close, null)
            .show()
    }

    private fun fix(r: Requirement) {
        when (r) {
            Requirement.LOCATION_ON,
            Requirement.WORK_PROFILE_LOCATION,
            Requirement.NETWORK_LOCATION -> runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            Requirement.PRECISE_PERMISSION -> askForLocationPermission()
            Requirement.BACKGROUND_PERMISSION -> askForBackgroundLocation()
            Requirement.UNRESTRICTED_BACKGROUND -> BatteryExemption.request(this)
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] != true) {
            toast(getString(R.string.location_denied))
        } else {
            askForBackgroundLocation()
        }
        buildSections()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) openAppLocationSettings()
        buildSections()
    }

    private fun askForLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    private fun askForBackgroundLocation() {
        if (hasBackgroundLocation()) return
        if (DpmActions(this).grantSelfBackgroundLocation() && hasBackgroundLocation()) return
        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun hasBackgroundLocation(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun openAppLocationSettings() {
        toast(getString(R.string.location_needs_all_the_time))
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null),
                )
            )
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun isProfileOwner(): Boolean =
        getSystemService(DevicePolicyManager::class.java)?.isProfileOwnerApp(packageName) == true

    private fun addUnlockTimer(card: LinearLayout) {
        val days = WyrdPrefs.get(this).autoWipeDays
        if (days <= 0) return
        val deadline = AutoWipe.deadlineAt(this)
        val subtitle = if (deadline == null) {
            resources.getQuantityString(R.plurals.autowipe_pending, days, days)
        } else {
            getString(R.string.autowipe_deadline, moment(deadline))
        }
        row(
            card,
            getString(R.string.autowipe_title),
            subtitle,
            Status(resources.getQuantityString(R.plurals.chip_days, days, days), R.color.wyrd_alarm),
        )
    }

    private fun addProfileExpiry(card: LinearLayout) {
        val at = ProfileExpiry.deadlineAt(this) ?: return
        row(card, getString(R.string.expiry_title), getString(R.string.expiry_deadline, moment(at)))
    }

    private fun moment(epochSeconds: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(TimeUnit.SECONDS.toMillis(epochSeconds)))

    private fun addRestrictions(card: LinearLayout) {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = WyrdDeviceAdminReceiver.component(this)
        val managed = dpm?.isProfileOwnerApp(packageName) == true
        if (!managed) {
            row(card, R.string.restr_not_managed, R.string.restr_not_managed_sub)
            return
        }
        var count = 0
        if (Geofence.isActive(this)) {
            row(card, getString(R.string.geofence_title), getString(R.string.geofence_summary),
                Status(getString(R.string.chip_view), R.color.wyrd_blue))
            card.getChildAt(card.childCount - 1).setOnClickListener {
                startActivity(Intent(this, GeofenceActivity::class.java))
            }
            count++
        }
        try {
            val restrictions = dpm.getUserRestrictions(admin)
            for ((key, name) in RESTRICTION_NAMES) {
                if (restrictions.getBoolean(key)) {
                    row(card, name, R.string.restr_by_policy)
                    count++
                }
            }
            if (dpm.getCameraDisabled(admin)) { row(card, R.string.restr_camera, R.string.restr_by_policy); count++ }
            if (dpm.getScreenCaptureDisabled(admin)) { row(card, R.string.restr_screenshots, R.string.restr_by_policy); count++ }
            if (DpmActions(this).hasUserRestriction(UserManager.DISALLOW_CONFIG_DATE_TIME, onParent = true) == true) {
                row(card, R.string.restr_date_time, R.string.restr_date_time_sub)
                count++
            }
        } catch (t: Throwable) {
            row(card, R.string.restr_unavailable, R.string.restr_unavailable_sub)
            return
        }
        if (count == 0) {
            row(card, R.string.restr_none, R.string.restr_none_sub)
        }
    }

    private fun sectionCard(parent: LinearLayout, header: String): LinearLayout {
        val h = TextView(this).apply {
            text = header
            typeface = Typeface.MONOSPACE
            setTextColor(color(R.color.wyrd_blue))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.28f
            layoutParams = LinearLayout.LayoutParams(WC, WC).apply {
                topMargin = dp(22); bottomMargin = dp(10); marginStart = dp(4)
            }
        }
        parent.addView(h)

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val card = MaterialCardView(this).apply {
            setCardBackgroundColor(color(R.color.wyrd_surface))
            cardElevation = 0f
            strokeColor = color(R.color.wyrd_hairline)
            strokeWidth = dp(1)
            radius = dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            addView(inner)
        }
        parent.addView(card)
        return inner
    }

    private fun row(card: LinearLayout, @StringRes title: Int, @StringRes subtitle: Int, status: Status? = null) =
        row(card, getString(title), getString(subtitle), status)

    private fun row(card: LinearLayout, title: String, subtitle: String, status: Status? = null) {
        if (card.childCount > 0) card.addView(divider())
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WC, 1f)
            addView(TextView(this@StatusActivity).apply {
                text = title
                setTextColor(color(R.color.wyrd_fog))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(this@StatusActivity).apply {
                text = subtitle
                setTextColor(color(R.color.wyrd_ash))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(MP, WC).apply { topMargin = dp(2) }
            })
        }
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(MP, WC)
            addView(texts)
            if (status != null) addView(statusChip(status))
        }
        card.addView(rowView)
    }

    private fun switchRow(
        card: LinearLayout,
        @StringRes title: Int,
        @StringRes subtitle: Int,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        row(card, title, subtitle)
        val rowView = card.getChildAt(card.childCount - 1) as LinearLayout
        rowView.addView(SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
    }

    private fun permRow(
        card: LinearLayout,
        @StringRes title: Int,
        @StringRes subtitle: Int,
        permission: String,
        onFix: (() -> Unit)? = null,
    ) {
        val granted = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        val status =
            if (granted) Status(getString(R.string.chip_granted), R.color.wyrd_green)
            else Status(getString(R.string.chip_denied), R.color.wyrd_alarm)
        row(card, title, subtitle, status)
        if (granted || onFix == null) return
        card.getChildAt(card.childCount - 1).setOnClickListener { onFix() }
    }

    private fun addPositionSources(card: LinearLayout) {
        val lm = getSystemService(LocationManager::class.java)
        val satellite = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }
            .getOrDefault(false)
        val network = runCatching { lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true }
            .getOrDefault(false)
        val label = when {
            satellite && network -> Status(getString(R.string.chip_sources_both), R.color.wyrd_green)
            network -> Status(getString(R.string.chip_sources_network), R.color.wyrd_green)
            satellite -> Status(getString(R.string.chip_sources_satellite), R.color.wyrd_alarm)
            else -> Status(getString(R.string.chip_sources_none), R.color.wyrd_alarm)
        }
        row(card, R.string.perm_sources, R.string.perm_sources_sub, label)
        if (satellite && network) return
        card.getChildAt(card.childCount - 1).setOnClickListener {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
    }

    private fun addBatteryExemption(card: LinearLayout) {
        val exempt = BatteryExemption.granted(this)
        val status =
            if (exempt) Status(getString(R.string.chip_background_unlimited), R.color.wyrd_green)
            else Status(getString(R.string.chip_background_limited), R.color.wyrd_alarm)
        row(card, R.string.perm_background_running, R.string.perm_background_running_sub, status)
        if (exempt) return
        val rowView = card.getChildAt(card.childCount - 1)
        rowView.setOnClickListener { BatteryExemption.request(this) }
    }

    private fun statusChip(status: Status): TextView = TextView(this).apply {
        text = status.label
        typeface = Typeface.MONOSPACE
        setTextColor(color(status.colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = 0.1f
        layoutParams = LinearLayout.LayoutParams(WC, WC).apply { marginStart = dp(8) }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(MP, dp(1)).apply {
            marginStart = dp(12); marginEnd = dp(12)
        }
        setBackgroundColor(color(R.color.wyrd_hairline))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    private data class Status(val label: String, val colorRes: Int)

    private companion object {
        private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT

        private val RESTRICTION_NAMES = linkedMapOf(
            UserManager.DISALLOW_CROSS_PROFILE_COPY_PASTE to R.string.restr_cross_profile_paste,
            UserManager.DISALLOW_INSTALL_APPS to R.string.restr_install_apps,
            UserManager.DISALLOW_UNINSTALL_APPS to R.string.restr_uninstall_apps,
        )
    }
}
