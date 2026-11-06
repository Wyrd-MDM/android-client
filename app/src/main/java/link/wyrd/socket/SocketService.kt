package link.wyrd.socket

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import androidx.core.content.ContextCompat
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.provider.Settings
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat
import link.wyrd.R
import link.wyrd.admin.DpmActions
import link.wyrd.command.CommandDispatcher
import link.wyrd.command.CommandLogStore
import link.wyrd.inventory.SimChangeDetector
import link.wyrd.inventory.TelemetrySnapshotter
import link.wyrd.net.Command
import link.wyrd.prefs.WyrdPrefs
import link.wyrd.security.CommandVerifier
import link.wyrd.security.DeviceSigner
import link.wyrd.net.WyrdApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class SocketService : Service() {

    @Volatile private var running = false
    private var workerThread: Thread? = null
    @Volatile private var activeLink: Link? = null
    @Volatile private var activeOutgoing: LinkedBlockingQueue<Frame.ClientFrame>? = null
    @Volatile private var lastInboundMs: Long = 0L
    @Volatile private var sessionNetwork: Network? = null
    private var lastHelloTs = 0L
    private val wake = Semaphore(0)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (network == sessionNetwork) return
            Log.i(TAG, "default network is now $network; reconnecting")
            reconnectNow()
        }

        override fun onLost(network: Network) {
            if (network != sessionNetwork) return
            Log.i(TAG, "session network $network lost; reconnecting")
            reconnectNow()
        }
    }

    private fun reconnectNow() {
        activeLink?.close()
        wake.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val watchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            val dm = getSystemService(android.hardware.display.DisplayManager::class.java) ?: return
            val on = dm.getDisplay(displayId)?.state == android.view.Display.STATE_ON
            if (on) startUnlockWatch()
        }
    }

    private fun startUnlockWatch() {
        watchHandler.removeCallbacksAndMessages(null)
        var left = UNLOCK_WATCH_TRIES
        lateinit var look: Runnable
        look = Runnable {
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (km != null && !km.isKeyguardLocked) {
                link.wyrd.command.AutoWipe.noteUnlock(applicationContext)
                return@Runnable
            }
            if (--left > 0) watchHandler.postDelayed(look, UNLOCK_WATCH_STEP_MS)
        }
        watchHandler.postDelayed(look, UNLOCK_WATCH_STEP_MS)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        runCatching { link.wyrd.admin.DpmActions(this).restoreSuspendedRestrictions() }
            .onFailure { Log.w(TAG, "could not restore what an install lifted", it) }
        KeepAliveScheduler.schedule(applicationContext)
        runCatching {
            getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.registerDisplayListener(displayListener, watchHandler)
        }.onFailure { Log.w(TAG, "could not watch the display", it) }
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        }.onFailure { Log.w(TAG, "could not watch the network", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_KEEPALIVE) {
            handleKeepalive()
        }
        if (intent?.action == ACTION_RELOAD) {
            Log.i(TAG, "reload requested — dropping socket to reconnect in full mode")
            activeLink?.close()
        }
        if (intent?.action == ACTION_FLUSH_UNLOCKS) {
            flushUnlockEvents()
        }
        if (!running) {
            running = true
            workerThread = Thread({ reconnectLoop() }, "SocketService-loop").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching {
            getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.unregisterDisplayListener(displayListener)
        }
        watchHandler.removeCallbacksAndMessages(null)
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback) }
        KeepAliveScheduler.cancel(applicationContext)
        running = false
        activeLink?.close()
        workerThread?.interrupt()
        super.onDestroy()
    }

    private fun handleKeepalive() {
        val ch = activeOutgoing
        if (ch == null) {
            Log.i(TAG, "keepalive: no active session — reconnect loop will retry")
            return
        }
        ch.offer(Frame.ClientFrame.Ping)
        flushUnlockEvents()

        val idleTimeout = idleTimeoutMs(applicationContext)
        val idleMs = System.currentTimeMillis() - lastInboundMs
        if (idleMs > idleTimeout) {
            Log.w(TAG, "keepalive: idle ${idleMs}ms > ${idleTimeout}ms — forcing reconnect")
            activeLink?.close()
        }
    }

    private fun reconnectLoop() {
        var backoffMs = MIN_BACKOFF_MS

        while (running) {
            val prefs = WyrdPrefs.get(applicationContext)
            if (!prefs.enrolled || prefs.serverUrl.isNullOrBlank() || prefs.enrollToken.isNullOrBlank()) {
                if (!sleepFor(10_000)) break
                continue
            }
            val url = channelUrl(prefs.serverUrl)
            if (url == null) {
                Log.w(TAG, "cannot build the channel address from server_url=${prefs.serverUrl}")
                if (!sleepFor(60_000)) break
                continue
            }

            Log.i(TAG, "connecting to $url")
            wake.drainPermits()
            try {
                runSession(url, prefs.deviceId, prefs.enrollToken!!) {
                    backoffMs = MIN_BACKOFF_MS
                }
                Log.i(TAG, "session ended cleanly")
                backoffMs = MIN_BACKOFF_MS
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                Log.w(TAG, "session error", t)
            }

            if (!running) break
            val jitter = Random.nextLong(0, backoffMs / 2 + 1)
            Log.i(TAG, "reconnecting in ${backoffMs + jitter}ms")
            if (!sleepFor(backoffMs + jitter)) break
            backoffMs = if (networkChanged) MIN_BACKOFF_MS else (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            networkChanged = false
        }
    }

    private fun sleepFor(ms: Long): Boolean {
        return try {
            if (wake.tryAcquire(ms, TimeUnit.MILLISECONDS)) {
                wake.drainPermits()
                networkChanged = true
            }
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private var networkChanged = false

    private fun runSession(url: HttpUrl, deviceId: String, token: String, onHandshake: () -> Unit = {}) {
        val network = getSystemService(ConnectivityManager::class.java)?.activeNetwork
            ?: error("no network")
        val link = openChannel(network, url)
        activeLink = link
        sessionNetwork = network
        if (getSystemService(ConnectivityManager::class.java)?.activeNetwork != network) {
            link.close()
            error("network changed while connecting")
        }
        val sessionAlive = AtomicBoolean(true)
        val outgoing = LinkedBlockingQueue<Frame.ClientFrame>(64)
        val helpers = mutableListOf<Thread>()
        var adbObserver: ContentObserver? = null
        var packageWatch: BroadcastReceiver? = null
        val apps = AppsReport(applicationContext)

        try {
            val helloSigner = DeviceSigner(applicationContext)
            val ts = maxOf(System.currentTimeMillis() / 1000, lastHelloTs + 1)
            lastHelloTs = ts
            val bfu = !isUserUnlocked()
            val serverKey = WyrdPrefs.get(applicationContext).serverPublicKey?.takeIf { it.isNotBlank() }
                ?.let { java.util.Base64.getDecoder().decode(it) }
                ?: error("no server key to check the server against")
            val eph = Channel.Ephemeral()
            val prefs = WyrdPrefs.get(applicationContext)
            val helloFrame = Frame.ClientFrame.Hello(
                deviceId, token, ts, bfu, eph.public, eph.kem, prefs.profileExpirySeq, prefs.serverUrl.orEmpty(),
            ).let {
                it.copy(signature = helloSigner.signHello(it.body().toByteArray()))
            }
            val envelope = Channel.seal(WyrdApi.sealingKey(applicationContext), helloFrame.encode(), Channel.Purpose.HELLO)
            if (!link.send(envelope.body)) error("connection refused the HELLO")
            when (val hello = link.answer(HELLO_TIMEOUT_MS, envelope)) {
                is Frame.ServerFrame.HelloOk -> {
                    link.sealed = eph.finish(hello, helloFrame.body().toByteArray(), serverKey)
                    Log.i(TAG, "HELLO_OK")
                    onHandshake()
                }
                is Frame.ServerFrame.HelloErr -> error("HELLO_ERR: ${hello.message}")
                else -> error("unexpected first frame: ${hello::class.simpleName}")
            }

            lastInboundMs = System.currentTimeMillis()
            activeOutgoing = outgoing
            liveOutgoing = outgoing
            setConnected(true)

            settlePendingInstall(outgoing)

            helpers += Thread({ writerLoop(link, outgoing, sessionAlive) }, "SocketService-writer")
                .also { it.start() }
            helpers += Thread({ telemetryLoop(outgoing, sessionAlive, apps) }, "SocketService-telemetry")
                .also { it.start() }

            helpers += Thread({ orgOwnedReporterLoop(outgoing, sessionAlive) }, "SocketService-orgowned")
                .also { it.start() }

            flushUnlockEvents()

            checkSimChange(outgoing)

            reportAdbState(outgoing)
            adbObserver = watchAdbState(outgoing)

            apps.send(outgoing)
            packageWatch = watchPackages(apps, outgoing)

            while (sessionAlive.get()) {
                val frame = link.next(READ_TIMEOUT_MS)
                lastInboundMs = System.currentTimeMillis()
                handleInbound(frame, outgoing)
            }
        } finally {
            setConnected(false)
            sessionAlive.set(false)
            activeOutgoing = null
            liveOutgoing = null
            activeLink = null
            sessionNetwork = null
            adbObserver?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
            packageWatch?.let { runCatching { unregisterReceiver(it) } }
            link.close()
            helpers.forEach { it.interrupt() }
            helpers.forEach { runCatching { it.join(2_000) } }
        }
    }

    private fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        runCatching { sendBroadcast(Intent(ACTION_CONN_STATE).setPackage(packageName)) }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, linkNotification())
        }
    }

    private fun writerLoop(
        link: Link,
        outgoing: LinkedBlockingQueue<Frame.ClientFrame>,
        sessionAlive: AtomicBoolean,
    ) {
        val signer = DeviceSigner(applicationContext)
        try {
            while (sessionAlive.get()) {
                val frame = signIfNeeded(outgoing.take(), signer)
                if (!link.send(frame.encode())) error("connection refused a message")
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            if (sessionAlive.get()) {
                Log.w(TAG, "writer error", t)
                sessionAlive.set(false)
                link.close()
            }
        }
    }

    private fun signIfNeeded(frame: Frame.ClientFrame, signer: DeviceSigner): Frame.ClientFrame {
        return when (frame) {
            is Frame.ClientFrame.Ack -> {
                if (frame.signature != null) frame
                else {
                    val sig = signer.signAck(frame.body().toByteArray())
                    if (sig == null) frame else frame.copy(signature = sig)
                }
            }

            is Frame.ClientFrame.Telemetry -> {
                if (frame.signature != null) frame
                else {
                    val sig = signer.signTelemetry(frame.body().toByteArray())
                    if (sig == null) frame else frame.copy(signature = sig)
                }
            }

            is Frame.ClientFrame.Hello, Frame.ClientFrame.Ping, Frame.ClientFrame.Pong -> frame
        }
    }

    private fun telemetryLoop(
        outgoing: LinkedBlockingQueue<Frame.ClientFrame>,
        sessionAlive: AtomicBoolean,
        apps: AppsReport,
    ) {
        val snap = TelemetrySnapshotter(applicationContext)
        runCatching { outgoing.offer(Frame.ClientFrame.Telemetry(snap.snapshot())) }
            .onFailure { Log.w(TAG, "telemetry initial push failed", it) }
        try {
            while (sessionAlive.get()) {
                Thread.sleep(TELEMETRY_EVERY_MS)
                if (!sessionAlive.get()) break
                runCatching { outgoing.offer(Frame.ClientFrame.Telemetry(snap.snapshot())) }
                    .onFailure { Log.w(TAG, "telemetry push failed", it) }
                runCatching { checkSimChange(outgoing) }
                    .onFailure { Log.w(TAG, "sim change check failed", it) }
                apps.send(outgoing)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun orgOwnedReporterLoop(
        outgoing: LinkedBlockingQueue<Frame.ClientFrame>,
        sessionAlive: AtomicBoolean
    ) {
        val dpm = DpmActions(applicationContext)
        var last = dpm.isOrgOwnedProfile
        reportOrgOwned(outgoing, last)
        if (last) return
        try {
            while (sessionAlive.get()) {
                Thread.sleep(ORG_OWNED_POLL_MS)
                if (!sessionAlive.get()) break
                val now = dpm.isOrgOwnedProfile
                if (now != last) {
                    last = now
                    reportOrgOwned(outgoing, now)
                    if (now) {
                        Log.i(TAG, "profile marked organization-owned")
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun reportOrgOwned(
        outgoing: LinkedBlockingQueue<Frame.ClientFrame>,
        orgOwned: Boolean
    ) {
        val data = buildJsonObject {
            put("ts", System.currentTimeMillis() / 1000)
            put("orgOwned", orgOwned)
        }
        runCatching { outgoing.offer(Frame.ClientFrame.Telemetry(data)) }
            .onFailure { Log.w(TAG, "org-owned report failed", it) }
    }

    private fun adbUsbOn(): Boolean =
        Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) != 0

    private fun adbWifiOn(): Boolean =
        Settings.Global.getInt(contentResolver, "adb_wifi_enabled", 0) != 0

    private fun reportAdbState(outgoing: LinkedBlockingQueue<Frame.ClientFrame>) {
        val usb = adbUsbOn()
        val wifi = adbWifiOn()
        val data = buildJsonObject {
            put("ts", System.currentTimeMillis() / 1000)
            put("adb", buildJsonObject {
                put("enabled", usb || wifi)
                put("usb", usb)
                put("wifi", wifi)
            })
        }
        runCatching { outgoing.offer(Frame.ClientFrame.Telemetry(data)) }
            .onFailure { Log.w(TAG, "adb state report failed", it) }
    }

    private fun watchPackages(apps: AppsReport, outgoing: LinkedBlockingQueue<Frame.ClientFrame>): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                Thread({ apps.send(outgoing) }, "SocketService-apps").start()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    private fun watchAdbState(outgoing: LinkedBlockingQueue<Frame.ClientFrame>): ContentObserver {
        var last = adbUsbOn() to adbWifiOn()
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                val now = adbUsbOn() to adbWifiOn()
                if (now == last) return
                last = now
                Log.i(TAG, "adb state changed: usb=${now.first} wifi=${now.second}")
                reportAdbState(outgoing)
            }
        }
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observer
        )
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor("adb_wifi_enabled"), false, observer
        )
        return observer
    }

    private fun settlePendingInstall(outgoing: LinkedBlockingQueue<Frame.ClientFrame>) {
        val prefs = WyrdPrefs.get(applicationContext)
        val note = prefs.pendingInstall ?: return
        prefs.pendingInstall = null
        val parts = note.split("|")
        if (parts.size != 3) return
        val (commandId, pkg) = parts[0] to parts[1]
        val expected = parts[2].toLongOrNull() ?: return
        val installed = runCatching {
            applicationContext.packageManager.getPackageInfo(pkg, 0).longVersionCode
        }.getOrNull()
        val ok = installed != null && installed >= expected
        val message = if (ok) "" else "install_did_not_take: have ${installed ?: "nothing"}, wanted $expected"
        Log.i(TAG, "settling install $commandId for $pkg: ok=$ok installed=$installed wanted=$expected")
        CommandLogStore.get(applicationContext).markDone(commandId, ok, message.ifEmpty { null })
        outgoing.offer(Frame.ClientFrame.Ack(commandId, ok, message))
    }

    private fun flushUnlockEvents() {
        val ch = activeOutgoing ?: return
        val pending = UnlockEventQueue.drainAll(applicationContext)
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis() / 1000
        var sent = 0
        for (e in pending) {
            val data = buildJsonObject {
                put("ts", now)
                put("unlock", buildJsonObject {
                    put("success", e.success)
                    put("at", e.at)
                })
            }
            if (ch.offer(Frame.ClientFrame.Telemetry(data))) {
                sent++
            } else {
                UnlockEventQueue.append(applicationContext, e.success, e.at)
            }
        }
        if (sent > 0) Log.i(TAG, "flushed $sent unlock event(s)")
    }

    private fun checkSimChange(outgoing: LinkedBlockingQueue<Frame.ClientFrame>) {
        if (!isUserUnlocked()) return
        val current = SimChangeDetector(applicationContext).currentHash() ?: return
        val prefs = WyrdPrefs.get(applicationContext)
        val baseline = prefs.simIccidHash
        if (baseline == null) {
            prefs.simIccidHash = current
            Log.i(TAG, "SIM baseline established")
            return
        }
        if (baseline == current) return

        val now = System.currentTimeMillis() / 1000
        val data = buildJsonObject {
            put("ts", now)
            put("sim_change", buildJsonObject {
                put("at", now)
            })
        }
        if (outgoing.offer(Frame.ClientFrame.Telemetry(data))) {
            prefs.simIccidHash = current
            Log.i(TAG, "SIM change detected — sim_change event queued")
        }
    }

    private fun handleInbound(frame: Frame.ServerFrame, outgoing: LinkedBlockingQueue<Frame.ClientFrame>) {
        when (frame) {
            is Frame.ServerFrame.Command -> processCommand(frame, outgoing)
            is Frame.ServerFrame.Ping -> outgoing.offer(Frame.ClientFrame.Pong)
            is Frame.ServerFrame.Pong -> {  }
            is Frame.ServerFrame.HelloOk,
            is Frame.ServerFrame.HelloErr -> Log.w(TAG, "unexpected handshake frame after HELLO: $frame")
        }
    }

    private fun processCommand(
        frame: Frame.ServerFrame.Command,
        outgoing: LinkedBlockingQueue<Frame.ClientFrame>
    ) {
        Log.i(TAG, "COMMAND ${frame.id}/${frame.type}")

        val verdict = CommandVerifier(applicationContext).verify(frame.signedBytes, frame.signature)
        when (verdict) {
            CommandVerifier.Outcome.Ok -> {}
            CommandVerifier.Outcome.BadSignature -> {
                Log.w(TAG, "rejecting ${frame.id}: bad signature")
                outgoing.offer(Frame.ClientFrame.Ack(frame.id, false, "bad_signature"))
                return
            }
            CommandVerifier.Outcome.Unsigned -> {
                Log.w(TAG, "rejecting ${frame.id}: unsigned")
                outgoing.offer(Frame.ClientFrame.Ack(frame.id, false, "unsigned"))
                return
            }
            CommandVerifier.Outcome.NoKey -> {
                Log.w(TAG, "rejecting ${frame.id}: no server public key stored")
                outgoing.offer(Frame.ClientFrame.Ack(frame.id, false, "no_server_key"))
                return
            }
        }

        if (!isUserUnlocked()) {
            if (frame.type !in BFU_ALLOWED) {
                Log.i(TAG, "BFU: deferring ${frame.id}/${frame.type}")
                outgoing.offer(Frame.ClientFrame.Ack(frame.id, false, "bfu_deferred"))
                return
            }
            Log.i(TAG, "BFU: executing ${frame.id}/${frame.type} (no replay log)")
            dispatchOnThread(frame, log = null)
            return
        }

        val log = CommandLogStore.get(applicationContext)
        val existing = log.recordOrGet(frame.id)
        if (existing != null) {
            when (existing.phase) {
                CommandLogStore.Phase.done -> {
                    val ok = existing.ok ?: false
                    val msg = existing.message ?: ""
                    Log.i(TAG, "replay hit (done) for ${frame.id} — re-ACK ok=$ok")
                    outgoing.offer(Frame.ClientFrame.Ack(frame.id, ok, msg))
                }
                CommandLogStore.Phase.started -> {
                    if (frame.id in runningCommands) {
                        Log.i(TAG, "replay hit (running) for ${frame.id} — answered when done")
                    } else {
                        Log.w(TAG, "replay hit (started) for ${frame.id} — aborted_restart")
                        log.markDone(frame.id, ok = false, message = "aborted_restart")
                        outgoing.offer(Frame.ClientFrame.Ack(frame.id, false, "aborted_restart"))
                    }
                }
            }
            return
        }

        dispatchOnThread(frame, log)
    }

    private fun dispatchOnThread(frame: Frame.ServerFrame.Command, log: CommandLogStore?) {
        runningCommands += frame.id
        Thread({
            try {
                val dispatcher = CommandDispatcher(applicationContext)
                val cmd = Command(id = frame.id, type = frame.type, payload = frame.payload)
                val result = dispatcher.handle(cmd)
                log?.markDone(frame.id, result.ok, result.message)
                activeOutgoing?.offer(
                    Frame.ClientFrame.Ack(
                        id = result.id,
                        ok = result.ok,
                        message = result.message ?: ""
                    )
                )
            } finally {
                runningCommands -= frame.id
            }
        }, "SocketService-cmd-${frame.id}").start()
    }

    private fun openChannel(network: Network, url: HttpUrl): Link {
        val client = WyrdApi.sharedClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val link = Link()
        link.socket = client.newWebSocket(Request.Builder().url(url).build(), link)
        try {
            link.awaitOpen(CONNECT_TIMEOUT_MS + HELLO_TIMEOUT_MS)
        } catch (t: Throwable) {
            link.close()
            throw t
        }
        return link
    }

    private class Link : WebSocketListener() {
        lateinit var socket: WebSocket
        private val arrived = LinkedBlockingQueue<Any>()
        @Volatile var sealed: Channel.Sealed? = null

        private object Opened
        private class Ended(val why: String, val cause: Throwable? = null)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            arrived.offer(Opened)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            arrived.offer(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            arrived.offer(Ended("server closed the channel: $code $reason"))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.let { ": HTTP ${it.code}" }.orEmpty()
            arrived.offer(Ended("channel failed$status", t))
        }

        fun awaitOpen(timeoutMs: Long) {
            val first = take(timeoutMs, "connect")
            if (first !== Opened) throw IOException("channel spoke before it opened")
        }

        fun answer(timeoutMs: Long, envelope: Channel.Envelope): Frame.ServerFrame =
            Frame.decodeServer(envelope.open(take(timeoutMs, "read") as String))

        fun next(timeoutMs: Long): Frame.ServerFrame {
            val wire = take(timeoutMs, "read") as String
            return Frame.decodeServer(sealed?.fromServer?.open(wire) ?: wire)
        }

        fun send(text: String): Boolean = socket.send(sealed?.toServer?.seal(text) ?: text)

        fun close() = socket.cancel()

        private fun take(timeoutMs: Long, what: String): Any {
            val item = arrived.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: throw IOException("channel $what timed out after ${timeoutMs}ms")
            if (item is Ended) throw IOException(item.why, item.cause)
            return item
        }
    }

    private fun isUserUnlocked(): Boolean {
        val um = applicationContext.getSystemService(UserManager::class.java)
        return um?.isUserUnlocked ?: true
    }

    private fun channelUrl(serverUrl: String?): HttpUrl? {
        if (serverUrl.isNullOrBlank()) return null
        val base = serverUrl.trim().trimEnd('/')
        val withScheme = if ("://" in base) base else "https://$base"
        return "$withScheme$CHANNEL_PATH".toHttpUrlOrNull()
    }

    private fun linkNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                getString(if (connected) R.string.notif_linked else R.string.notif_not_linked)
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun startForegroundWithNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_link), NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(
            NOTIFICATION_ID,
            linkNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    companion object {
        private const val UNLOCK_WATCH_STEP_MS = 3_000L

        private const val UNLOCK_WATCH_TRIES = 60

        const val ACTION_KEEPALIVE = "link.wyrd.action.KEEPALIVE"
        const val ACTION_RELOAD = "link.wyrd.action.RELOAD"
        const val ACTION_FLUSH_UNLOCKS = "link.wyrd.action.FLUSH_UNLOCKS"

        const val ACTION_CONN_STATE = "link.wyrd.action.CONN_STATE"

        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        private var liveOutgoing: LinkedBlockingQueue<Frame.ClientFrame>? = null

        const val LAST_WORD_GRACE_MS = 2_500L

        fun reportAutoWipe(days: Int, lastUnlockAt: Long): Boolean {
            val ch = liveOutgoing ?: return false
            val data = buildJsonObject {
                put("ts", System.currentTimeMillis() / 1000)
                put("auto_wipe", buildJsonObject {
                    put("days", days)
                    put("last_unlock_at", lastUnlockAt)
                })
            }
            return ch.offer(Frame.ClientFrame.Telemetry(data))
        }

        fun reportGeofenceWipe(
            cause: String,
            zone: String?,
            minutes: Long?,
            lastFixAt: Long?,
            missing: List<String> = emptyList(),
        ): Boolean {
            val ch = liveOutgoing ?: return false
            val data = buildJsonObject {
                put("ts", System.currentTimeMillis() / 1000)
                put("geofence_wipe", buildJsonObject {
                    put("cause", cause)
                    if (zone != null) put("zone", zone)
                    if (minutes != null) put("minutes", minutes)
                    if (lastFixAt != null) put("last_fix_at", lastFixAt)
                    if (missing.isNotEmpty()) put("missing", JsonArray(missing.map { JsonPrimitive(it) }))
                })
            }
            return ch.offer(Frame.ClientFrame.Telemetry(data))
        }

        fun reportLocationShare(on: Boolean): Boolean {
            val ch = liveOutgoing ?: return false
            val data = buildJsonObject {
                put("ts", System.currentTimeMillis() / 1000)
                put("locationShare", on)
            }
            return ch.offer(Frame.ClientFrame.Telemetry(data))
        }

        private val BFU_ALLOWED = setOf("REPORT_LOCATION", "WIPE_PROFILE", "SET_PROFILE_EXPIRY")

        private val runningCommands: MutableSet<String> = ConcurrentHashMap.newKeySet()

        private const val TAG = "SocketService"
        private const val CHANNEL_ID = "wyrd_mdm"
        private const val NOTIFICATION_ID = 1

        private fun idleTimeoutMs(context: Context): Long =
            2 * KeepAliveScheduler.intervalMs(context) + 15_000L

        private const val READ_TIMEOUT_MS: Long =
            2 * KeepAliveScheduler.MAX_INTERVAL_MS + 15_000L + 60_000L
        private const val HELLO_TIMEOUT_MS: Long = 15_000L
        private const val CONNECT_TIMEOUT_MS: Long = 15_000L

        private const val CHANNEL_PATH = "/device-api/ws"
        private const val TELEMETRY_EVERY_MS: Long = 10 * 60_000L
        private const val ORG_OWNED_POLL_MS: Long = 5_000L
        private const val MIN_BACKOFF_MS: Long = 5_000L
        private const val MAX_BACKOFF_MS: Long = 30_000L
    }
}
