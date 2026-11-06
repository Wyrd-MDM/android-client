package link.wyrd

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.work.WorkInfo
import androidx.work.WorkManager
import link.wyrd.admin.BatteryExemption
import link.wyrd.admin.WyrdDeviceAdminReceiver
import link.wyrd.databinding.ActivityMainBinding
import link.wyrd.prefs.WyrdPrefs
import link.wyrd.socket.SocketService
import link.wyrd.ui.WyrdWebView
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "MainActivity"
        private const val ENROLL_WORK_NAME = "wyrd-enroll"
        private const val LINKING_ANIM_MS = 2_400L

        const val EXTRA_ENROLLMENT_PAYLOAD = "enrollment_payload"
    }

    private lateinit var binding: ActivityMainBinding

    private var linkState: WyrdWebView.State = WyrdWebView.State.READY
    private var linkProgress: Float = 0f

    private val linkingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = LINKING_ANIM_MS
        addUpdateListener {
            linkProgress = it.animatedValue as Float
            applyState()
        }
    }

    private val provisionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.i(TAG, "Provisioning result: code=${result.resultCode}")
        if (result.resultCode == RESULT_OK) {
            WyrdPrefs.get(this).workProfileProvisioned = true
            refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.primaryAction.setOnClickListener { onPrimaryAction() }

        binding.step1Button.setOnClickListener {
            runCatching {
                startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD))
            }.onFailure { Log.w(TAG, "failed to launch SET_NEW_PASSWORD", it) }
        }
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ENROLL_WORK_NAME)
            .observe(this) { infos ->
                val latest = infos?.maxByOrNull { it.generation } ?: return@observe
                onEnrollWorkUpdate(latest.state)
            }

        handleEnrollmentIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEnrollmentIntent(intent)
    }

    private fun handleEnrollmentIntent(intent: Intent) {
        val payload = intent.getStringExtra(EXTRA_ENROLLMENT_PAYLOAD)?.takeIf { it.isNotBlank() }
            ?: return
        intent.removeExtra(EXTRA_ENROLLMENT_PAYLOAD)
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isProfileOwnerApp(packageName)) {
            Log.w(TAG, "Ignoring enrollment_payload: this device is already managed")
            return
        }
        Log.i(TAG, "Enrollment payload received via intent extra (${payload.length} chars)")
        handleEnrollmentPayload(payload)
    }

    private val connStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = applyState()
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, connStateReceiver, IntentFilter(SocketService.ACTION_CONN_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshStatus()
        if (WyrdPrefs.get(this).enrolled) {
            ensureSocketServiceRunning()
        }
        BatteryExemption.askOnce(this)
    }

    override fun onPause() {
        runCatching { unregisterReceiver(connStateReceiver) }
        super.onPause()
    }

    private fun ensureSocketServiceRunning() {
        ContextCompat.startForegroundService(
            this, Intent(this, SocketService::class.java)
        )
    }

    private fun applyWindowInsets() {
        val baseActionMarginBottom = binding.primaryAction.marginBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.upperScroll.updatePadding(top = bars.top)
            binding.primaryAction.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = baseActionMarginBottom + bars.bottom
            }
            insets
        }
    }

    private fun appVersionName(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        }.getOrDefault("")

    private fun onPrimaryAction() {
        if (linkState == WyrdWebView.State.LINKED) startActivity(Intent(this, StatusActivity::class.java))
    }

    private fun handleEnrollmentPayload(raw: String) {
        try {
            val json = JSONObject(raw)
            val extras = json.optJSONObject(
                "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE"
            )
            fun field(name: String): String =
                (extras?.optString(name, "")?.takeIf { it.isNotBlank() }
                    ?: json.optString(name, "")).trim()
            val url = field("server_url")
            val token = field("token")
            val serverKey = field(WyrdDeviceAdminReceiver.KEY_SERVER_KEY)
            val sealingKey = field(WyrdDeviceAdminReceiver.KEY_SERVER_SEALING_KEY)
            if (url.isEmpty() || token.isEmpty() || serverKey.isEmpty() || sealingKey.isEmpty()) {
                Log.w(TAG, "enrollment payload lacks server_url, token or server keys")
                toast(getString(R.string.enroll_payload_invalid))
                linkState = WyrdWebView.State.READY
                applyState()
                return
            }
            WyrdPrefs.get(this).apply {
                serverUrl = url
                enrollToken = token
                serverPublicKey = serverKey
                serverSealingKey = sealingKey
            }
            linkState = WyrdWebView.State.READY
            applyState()
            startWorkProfileProvisioning()
        } catch (t: Throwable) {
            Log.w(TAG, "enrollment payload unreadable", t)
            toast(getString(R.string.enroll_payload_invalid))
            linkState = WyrdWebView.State.READY
            applyState()
        }
    }

    private fun enterLinking() {
        linkState = WyrdWebView.State.LINKING
        linkProgress = 0f
        linkingAnimator.cancel()
        linkingAnimator.start()
        applyState()
    }

    private fun onEnrollWorkUpdate(state: WorkInfo.State) {
        when (state) {
            WorkInfo.State.SUCCEEDED -> {
                linkingAnimator.cancel()
                linkProgress = 1f
                linkState = WyrdWebView.State.LINKED
                applyState()
            }
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                linkingAnimator.cancel()
                linkProgress = 0f
                if (linkState == WyrdWebView.State.LINKING) {
                    linkState = WyrdWebView.State.READY
                    applyState()
                }
            }
            WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                if (linkState == WyrdWebView.State.READY) enterLinking()
            }
            else -> Unit
        }
    }

    private fun refreshStatus() {
        val prefs = WyrdPrefs.get(this)
        if (prefs.workProfileProvisioned && !runsInsideWorkProfile() && workProfileGone()) {
            onWorkProfileGone(prefs)
            return
        }
        val workProfileEnrolled = !prefs.enrolled && isWorkProfileEnrolled()
        val anyEnrolled = prefs.enrolled || workProfileEnrolled
        if (linkState != WyrdWebView.State.LINKING) {
            linkState = if (anyEnrolled) WyrdWebView.State.LINKED else WyrdWebView.State.READY
            linkProgress = if (anyEnrolled) 1f else 0f
        }
        applyState()
        refreshChecklist(anyEnrolled)
    }

    private fun refreshChecklist(anyEnrolled: Boolean) {
        val pinSet = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        val show = anyEnrolled && !pinSet
        binding.step1Card.visibility = if (show) View.VISIBLE else View.GONE
        binding.checklistContainer.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun isWorkProfileEnrolled(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = WyrdDeviceAdminReceiver.component(this)
        try {
            if (dpm.getBindDeviceAdminTargetUsers(admin).isNotEmpty()) return true
        } catch (_: SecurityException) {
        }
        return WyrdPrefs.get(this).workProfileProvisioned
    }

    private fun runsInsideWorkProfile(): Boolean =
        getSystemService(DevicePolicyManager::class.java)?.isProfileOwnerApp(packageName) == true

    private fun workProfileGone(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java) ?: return false
        return runCatching {
            dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        }.getOrDefault(false)
    }

    private fun onWorkProfileGone(prefs: WyrdPrefs) {
        Log.i(TAG, "work profile gone — offering to uninstall this copy")
        prefs.workProfileProvisioned = false
        linkState = WyrdWebView.State.READY
        linkProgress = 0f
        applyState()
        toast(getString(R.string.work_profile_gone))
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        } catch (t: Throwable) {
            Log.w(TAG, "uninstall prompt failed", t)
        }
    }

    private fun startWorkProfileProvisioning() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            val msg = getString(
                if (dpm.isProfileOwnerApp(packageName)) R.string.already_managed
                else R.string.work_profile_unsupported
            )
            Log.w(TAG, "Provisioning not allowed: $msg")
            toast(msg)
            return
        }

        val prefs = WyrdPrefs.get(this)
        val url = prefs.serverUrl?.trim().orEmpty()
        val token = prefs.enrollToken?.trim().orEmpty()
        if (url.isEmpty() || token.isEmpty()) {
            Log.w(TAG, "missing server address or token")
            toast(getString(R.string.enroll_payload_invalid))
            return
        }
        val extras = PersistableBundle().apply {
            putString(WyrdDeviceAdminReceiver.KEY_SERVER_URL, url)
            putString(WyrdDeviceAdminReceiver.KEY_TOKEN, token)
            putString(WyrdDeviceAdminReceiver.KEY_SERVER_KEY, prefs.serverPublicKey)
            putString(WyrdDeviceAdminReceiver.KEY_SERVER_SEALING_KEY, prefs.serverSealingKey)
        }

        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                WyrdDeviceAdminReceiver.component(this@MainActivity)
            )
            putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
                extras
            )
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, false)
        }

        Log.i(TAG, "launching PROVISION_MANAGED_PROFILE")
        provisionLauncher.launch(intent)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun applyState() {
        binding.wyrdWeb.linkState = linkState
        binding.wyrdWeb.progress = linkProgress

        when (linkState) {
            WyrdWebView.State.READY -> {
                binding.statusPrimary.text = getString(R.string.state_ready_primary)
                binding.statusSecondary.text = getString(R.string.state_ready_secondary)
                binding.linkProgress.visibility = View.GONE
                binding.primaryAction.visibility = View.GONE
            }
            WyrdWebView.State.LINKING -> {
                binding.statusPrimary.text = getString(R.string.state_linking_primary)
                binding.statusSecondary.text =
                    getString(R.string.state_linking_secondary_fmt, (linkProgress * 100).toInt())
                binding.linkProgress.visibility = View.VISIBLE
                binding.linkProgress.progress = (linkProgress * 100).toInt()
                renderAction(
                    R.string.btn_linking_label,
                    R.string.btn_linking_sub,
                    R.drawable.ic_action_wait,
                    actionBgRes = R.color.wyrd_hairline,
                    actionTextColorRes = R.color.wyrd_ash,
                    enabled = false,
                )
            }
            WyrdWebView.State.LINKED -> {
                val nodeId = nodeIdShort()
                binding.statusPrimary.text = getString(
                    if (SocketService.connected) R.string.state_connected_primary
                    else R.string.state_disconnected_primary
                )
                binding.statusSecondary.text =
                    getString(R.string.state_linked_secondary_fmt, nodeId, appVersionName())
                binding.linkProgress.visibility = View.GONE
                renderAction(
                    R.string.status_title,
                    R.string.status_subtitle,
                    R.drawable.ic_action_info,
                    actionBgRes = R.color.wyrd_surface_70,
                    actionTextColorRes = R.color.wyrd_fog,
                    enabled = true,
                )
            }
        }

    }

    private fun renderAction(
        labelRes: Int,
        subRes: Int,
        iconRes: Int,
        actionBgRes: Int,
        actionTextColorRes: Int,
        enabled: Boolean,
    ) {
        binding.primaryAction.visibility = View.VISIBLE
        binding.primaryAction.isClickable = enabled
        binding.primaryAction.alpha = if (enabled) 1f else 0.7f
        binding.primaryAction.setCardBackgroundColor(ContextCompat.getColor(this, actionBgRes))
        binding.primaryActionLabel.setText(labelRes)
        binding.primaryActionSub.setText(subRes)
        binding.primaryActionLabel.setTextColor(ContextCompat.getColor(this, actionTextColorRes))
        binding.primaryActionSub.setTextColor(ContextCompat.getColor(this, actionTextColorRes))
        binding.primaryActionIcon.setImageResource(iconRes)
        binding.primaryActionIcon.setColorFilter(ContextCompat.getColor(this, actionTextColorRes))
    }

    private fun nodeIdShort(): String =
        WyrdPrefs.get(this).deviceId.replace("-", "").take(11).uppercase()

}
