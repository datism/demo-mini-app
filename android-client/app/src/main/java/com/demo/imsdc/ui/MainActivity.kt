package com.demo.imsdc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.demo.imsdc.databinding.ActivityMainBinding
import com.demo.imsdc.notification.IncomingCallNotificationManager
import com.demo.imsdc.sip.SipManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

private const val TAG = "MainActivity"

/**
 * MainActivity — Màn hình đăng nhập / thiết lập kết nối & bàn phím Samsung OneUI
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var sipManager: SipManager? = null
    private var localIp = ""
    private var dialDigits = ""

    /** Where the current outgoing call was started, so the home screen returns there when it ends. */
    private enum class CallOrigin { DIALPAD, RECENTS, CONTACTS }
    private var callOrigin: CallOrigin? = null

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            Log.i(TAG, "debugReceiver onReceive: action=${intent?.action}")
            if (intent?.action == "com.demo.imsdc.CALL") {
                val target = intent.getStringExtra("target")
                Log.i(TAG, "debugReceiver CALL target=$target")
                if (target != null) {
                    dialDigits = target
                    showDigits()
                    binding.btnDialerCall.performClick()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isTaskRoot && intent.hasCategory(Intent.CATEGORY_LAUNCHER) && intent.action == Intent.ACTION_MAIN) {
            Log.w(TAG, "MainActivity is not task root, finishing to restore existing task stack")
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        SipManager.appContext = applicationContext

        // Khởi tạo Notification Channel cho cuộc gọi đến
        IncomingCallNotificationManager.createNotificationChannel(this)

        // Yêu cầu quyền POST_NOTIFICATIONS trên Android 13+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    102
                )
            }
        }

        // Lấy IP local tự động theo SIP server đã lưu hoặc mặc định
        val defaultServerInit = intent.getStringExtra("sip_server") 
            ?: getSharedPreferences("ims_dc_prefs", MODE_PRIVATE).getString("sip_server", "100.76.76.105") 
            ?: "100.76.76.105"
        localIp = com.demo.imsdc.util.NetworkUtils.getLocalIpAddress(this, defaultServerInit)
        Log.i(TAG, "Local IP: $localIp (probed with $defaultServerInit)")

        if (CallActivity.isCallActive) {
            Log.i(TAG, "Active call in progress in onCreate! Redirecting to CallActivity...")
            val callIntent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(callIntent)
            return
        }

        setupUI()
        setupDialerKeypad()
        resetHome(null)

        val autoUser = intent.getStringExtra("username")
        if (autoUser != null) {
            binding.etUsername.setText(autoUser)
            doRegister(binding.etSipServer.text.toString().trim(), autoUser)
        } else {
            // Tự động đăng ký khi mở ứng dụng với thông tin mặc định / đã lưu
            val defaultServer = binding.etSipServer.text.toString().trim()
            val defaultUser = binding.etUsername.text.toString().trim()
            if (defaultServer.isNotEmpty() && defaultUser.isNotEmpty()) {
                Log.i(TAG, "🚀 Tự động đăng ký khi khởi động ứng dụng: user=$defaultUser, server=$defaultServer")
                doRegister(defaultServer, defaultUser)
            }
        }
        
        val autoCall = intent.getStringExtra("call")
        if (autoCall != null) {
            dialDigits = autoCall
            binding.btnDialerCall.performClick()
        }

        registerDebugReceiver()
    }

    /** Clears the typed number and shows the tab a call came from (null = Gần đây). */
    private fun resetHome(origin: CallOrigin?) {
        dialDigits = ""
        showDigits()
        showTab(when (origin) {
            CallOrigin.DIALPAD -> HomeTab.KEYPAD
            CallOrigin.CONTACTS -> HomeTab.CONTACTS
            else -> HomeTab.RECENTS
        })
    }

    /** Calls straight from the recents / contacts list, without opening the keypad. */
    private fun callFromList(number: String, origin: CallOrigin) {
        callOrigin = origin
        dialDigits = number
        binding.btnDialerCall.performClick()
    }

    override fun onRestart() {
        super.onRestart()
        // Back from a call, or the app reopened from the background.
        if (!CallActivity.isCallActive) {
            resetHome(callOrigin)
            callOrigin = null
        }
    }

    private fun registerDebugReceiver() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(debugReceiver, android.content.IntentFilter("com.demo.imsdc.CALL"), android.content.Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(debugReceiver, android.content.IntentFilter("com.demo.imsdc.CALL"))
            }
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        setIntent(intent)

        if (CallActivity.isCallActive) {
            Log.i(TAG, "Active call in progress in onNewIntent! Redirecting to CallActivity...")
            val callIntent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(callIntent)
            return
        }
        val autoCall = intent.getStringExtra("call")
        Log.i(TAG, "onNewIntent: autoCall=$autoCall")
        if (!autoCall.isNullOrEmpty()) {
            dialDigits = autoCall
            showDigits()
            binding.tvDialDigits.visibility = View.VISIBLE
            binding.btnDialerCall.performClick()
        }
        val autoUser = intent.getStringExtra("username")
        if (autoUser != null) {
            binding.etUsername.setText(autoUser)
            doRegister(binding.etSipServer.text.toString().trim(), autoUser)
        }
    }

    private fun setupUI() {
        val prefs = getSharedPreferences("ims_dc_prefs", Context.MODE_PRIVATE)
        
        val intentSipServer = intent.getStringExtra("sip_server")
        val savedServer = intentSipServer ?: prefs.getString("sip_server", "") ?: ""
        
        val intentUsername = intent.getStringExtra("username")
        val savedUsername = intentUsername ?: prefs.getString("username", "") ?: ""
        
        val intentRemoteUser = intent.getStringExtra("remote_user")
        val savedRemoteUser = intentRemoteUser ?: prefs.getString("remote_user", "") ?: ""
        
        val intentLocalIp = intent.getStringExtra("local_ip")
        if (intentLocalIp != null && intentLocalIp.isNotEmpty()) {
            localIp = intentLocalIp
        }
        
        // Smart Default cho SIP Server P-CSCF
        if (savedServer.isNotEmpty()) {
            binding.etSipServer.setText(savedServer)
        } else {
            binding.etSipServer.setText("100.76.76.105")
        }

        // Smart Default cho Username tùy theo thiết bị
        if (savedUsername.isNotEmpty()) {
            binding.etUsername.setText(savedUsername)
        } else {
            if (localIp == "100.119.80.116") {
                binding.etUsername.setText("1002")
            } else if (localIp == "100.127.196.108") {
                binding.etUsername.setText("1003")
            } else {
                binding.etUsername.setText("1002")
            }
        }

        // Smart Default cho Remote User
        if (savedRemoteUser.isNotEmpty()) {
            binding.etRemoteUser.setText(savedRemoteUser)
        } else {
            if (localIp == "100.119.80.116") {
                binding.etRemoteUser.setText("1003")
            } else if (localIp == "100.127.196.108") {
                binding.etRemoteUser.setText("1002")
            } else {
                binding.etRemoteUser.setText("1003")
            }
        }

        binding.tvLocalIp.text = "Local IP: $localIp"
        binding.etLocalIp.setText(localIp)

        // Nút Register
        binding.btnRegister.setOnClickListener {
            val sipServer = binding.etSipServer.text.toString().trim()
            val username  = binding.etUsername.text.toString().trim()
            val remoteUser = binding.etRemoteUser.text.toString().trim()
            localIp       = binding.etLocalIp.text.toString().trim()

            if (sipServer.isEmpty() || username.isEmpty() || localIp.isEmpty()) {
                Toast.makeText(this, "Vui lòng điền đầy đủ thông tin", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("sip_server", sipServer)
                .putString("username", username)
                .putString("remote_user", remoteUser)
                .apply()

            doRegister(sipServer, username)
        }
    }

    private enum class HomeTab { KEYPAD, RECENTS, CONTACTS }
    private var currentTab = HomeTab.RECENTS

    /** Shows one of the three home tabs (One UI Phone style): full keypad, recents, or contacts. */
    private fun showTab(tab: HomeTab) {
        currentTab = tab
        val keypad = tab == HomeTab.KEYPAD
        binding.layoutDialpadPanel.visibility = if (keypad) View.VISIBLE else View.GONE
        binding.scrollRecentCalls.visibility = if (keypad) View.GONE else View.VISIBLE
        binding.btnAddContact.visibility = if (tab == HomeTab.CONTACTS) View.VISIBLE else View.GONE
        for ((view, t) in listOf(binding.tabKeypad to HomeTab.KEYPAD, binding.tabPhone to HomeTab.RECENTS,
                                 binding.tabContacts to HomeTab.CONTACTS)) {
            val on = t == tab
            view.setBackgroundResource(if (on) com.demo.imsdc.R.drawable.bg_nav_tab_selected else 0)
            (view.getChildAt(1) as? android.widget.TextView)?.setTypeface(null,
                if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        if (!keypad) {
            binding.scrollRecentCalls.scrollTo(0, 0)
            refreshList()
        }
    }

    /** Puts [dialDigits] on screen; backspace and "Thêm vào danh bạ" only show while something is typed. */
    private fun showDigits() {
        binding.tvDialDigits.text = dialDigits
        val vis = if (dialDigits.isEmpty()) View.INVISIBLE else View.VISIBLE
        binding.btnBackspace.visibility = vis
        binding.btnAddToContacts.visibility = vis
    }

    private fun doLogout() {
        sipManager?.stop()
        dialDigits = ""
        showDigits()
        binding.layoutSetup.visibility = View.VISIBLE
        binding.layoutDialer.visibility = View.GONE
        binding.tvStatus.text = "Chưa đăng ký"
        Toast.makeText(this, "Đã đăng xuất", Toast.LENGTH_SHORT).show()
    }

    private fun setupDialerKeypad() {
        // Ánh xạ phím số và ký tự đặc biệt
        val keys = listOf(
            binding.key1 to "1", binding.key2 to "2", binding.key3 to "3",
            binding.key4 to "4", binding.key5 to "5", binding.key6 to "6",
            binding.key7 to "7", binding.key8 to "8", binding.key9 to "9",
            binding.key0 to "0", binding.keyStar to "*", binding.keyPound to "#"
        )

        for ((view, digit) in keys) {
            view.setOnClickListener {
                dialDigits += digit
                showDigits()
            }
        }

        // Nút Backspace xóa 1 ký tự; nhấn giữ xóa sạch
        binding.btnBackspace.setOnClickListener {
            if (dialDigits.isNotEmpty()) {
                dialDigits = dialDigits.dropLast(1)
                showDigits()
            }
        }
        binding.btnBackspace.setOnLongClickListener {
            dialDigits = ""
            showDigits()
            true
        }
        binding.btnAddToContacts.setOnClickListener {
            if (dialDigits.isNotEmpty()) editContactDialog(dialDigits, null)
        }

        // Thanh điều hướng dạng viên thuốc: Bàn phím · Gần đây · Danh bạ
        binding.tabKeypad.setOnClickListener { showTab(HomeTab.KEYPAD) }
        binding.tabPhone.setOnClickListener { showTab(HomeTab.RECENTS) }
        binding.tabContacts.setOnClickListener { showTab(HomeTab.CONTACTS) }
        binding.btnAddContact.setOnClickListener { editContactDialog(null, null) }

        // Nút 3 chấm Menu (ở cả tiêu đề danh sách và bàn phím)
        val showMenu = { v: View ->
            val popup = android.widget.PopupMenu(this, v)
            popup.menu.add("Đăng xuất tài khoản")
            popup.setOnMenuItemClickListener { doLogout(); true }
            popup.show()
        }
        binding.btnMoreMenu.setOnClickListener { showMenu(it) }
        binding.btnMoreMenuKeypad.setOnClickListener { showMenu(it) }

        // Nút Gọi từ bàn phím quay số
        binding.btnDialerCall.setOnClickListener {
            val sipServer  = if (binding.etSipServer.text.toString().trim().isNotEmpty()) binding.etSipServer.text.toString().trim() else "100.76.76.105"
            val username   = if (binding.etUsername.text.toString().trim().isNotEmpty()) binding.etUsername.text.toString().trim() else "1002"
            val remoteUser = if (dialDigits.isNotEmpty()) dialDigits else if (binding.etRemoteUser.text.toString().trim().isNotEmpty()) binding.etRemoteUser.text.toString().trim() else "1003"
            
            // Ưu tiên IP thực tế từ socket TCP SipManager, fallback về probe định tuyến
            val activeIp = SipManager.shared?.getLocalIp() ?: com.demo.imsdc.util.NetworkUtils.getLocalIpAddress(this, sipServer)
            localIp = activeIp
            binding.etLocalIp.setText(activeIp)
            binding.tvLocalIp.text = "Local IP: $activeIp"
            Log.i(TAG, "📞 btnDialerCall clicked: sipServer=$sipServer, username=$username, remoteUser=$remoteUser, localIp=$localIp")

            if (sipServer.isEmpty() || username.isEmpty() || remoteUser.isEmpty() || localIp.isEmpty() || localIp == "0.0.0.0") {
                Toast.makeText(this, "Vui lòng điền đầy đủ thông tin để gọi", Toast.LENGTH_SHORT).show()
                callOrigin = null
                return@setOnClickListener
            }
            if (callOrigin == null) callOrigin = CallOrigin.DIALPAD

            val activePort = SipManager.shared?.getLocalPort() ?: 0
            
            saveRecentCall(remoteUser, isIncoming = false)
 
            CallActivity.launch(
                activity   = this,
                sipServer  = sipServer,
                localUser  = username,
                remoteUser = remoteUser,
                localIp    = localIp,
                localPort  = activePort,
                sipPort    = 5060
            )
        }
    }

    private fun doRegister(sipServer: String, username: String) {
        if (CallActivity.isCallActive || SipManager.shared?.isCallActive == true) {
            Log.w(TAG, "doRegister() blocked: active SIP call in progress")
            return
        }
        binding.btnRegister.isEnabled = false
        binding.tvStatus.text = "⏳ Đang đăng ký..."

        // Khởi tạo SIP manager mới nếu chưa có hoặc server thay đổi
        SipManager.shared?.stop()
        val sm = SipManager(
            sipServerHost = sipServer,
            sipServerPort = 5060,
            localUsername = username,
            localPort = 0,
            password = "password$username"
        )
        SipManager.shared = sm
        sipManager = sm
        var hasRegisteredOnce = false
        sm.also {
            sm.onRegistered = {
                lifecycleScope.launch(Dispatchers.Main) {
                    val resolvedIp = sm.getLocalIp()
                    localIp = resolvedIp
                    binding.tvLocalIp.text = "Local IP: $resolvedIp"
                    binding.etLocalIp.setText(resolvedIp)
                    Log.i(TAG, "SIP Registered successfully with local IP: $resolvedIp")

                    if (!hasRegisteredOnce) {
                        hasRegisteredOnce = true
                        binding.tvStatus.text = "✅ Đã đăng ký: $username"
                        binding.btnRegister.isEnabled = true
                        dialDigits = ""
                        showDigits()
                        
                        // Chuyển giao diện sang bàn phím Samsung OneUI
                        binding.layoutSetup.visibility = View.GONE
                        binding.layoutDialer.visibility = View.VISIBLE
                        
                        Toast.makeText(this@MainActivity, "Đăng ký thành công!", Toast.LENGTH_SHORT).show()

                        val autoCall = intent.getStringExtra("call")
                        if (!autoCall.isNullOrEmpty()) {
                            dialDigits = autoCall
                            showDigits()
                            binding.tvDialDigits.visibility = View.VISIBLE
                            binding.btnDialerCall.performClick()
                        }
                    } else {
                        Log.d(TAG, "Auto-refresh REGISTER OK (silently)")
                    }
                }
            }
            sm.onIncomingInvite = { fromUser, sdpBody, callId, rawMsg, rawSdp ->
                lifecycleScope.launch(Dispatchers.Main) {
                    val activePort = sm.getLocalPort()
                    val activeIp = sm.getLocalIp()
                    localIp = activeIp
                    CallActivity.launchIncoming(
                        context = this@MainActivity,
                        sipServer = sipServer,
                        localUser = username,
                        remoteUser = fromUser,
                        localIp = activeIp,
                        localPort = activePort,
                        sipPort = 5060,
                        incomingInvite = rawMsg,
                        incomingSdp = sdpBody,
                        incomingCallId = callId,
                        incomingRawSdp = rawSdp
                    )
                }
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                sm.start(this@MainActivity)
                val activeIp = sm.getLocalIp()
                localIp = activeIp
                sipManager?.register(activeIp)
                // If not registered within 4s, retry REGISTER once
                delay(4000)
                if (binding.layoutSetup.visibility == View.VISIBLE) {
                    Log.w(TAG, "Registration timeout — retrying SIP REGISTER...")
                    sipManager?.register(sm.getLocalIp())
                    delay(3000)
                    if (binding.layoutSetup.visibility == View.VISIBLE) {
                        withContext(Dispatchers.Main) {
                            binding.tvStatus.text = "⚠️ Chưa nhận phản hồi. Vui lòng bấm REGISTER lại."
                            binding.btnRegister.isEnabled = true
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "❌ Lỗi: ${e.message}"
                    binding.btnRegister.isEnabled = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (CallActivity.isCallActive) {
            Log.i(TAG, "Active call in progress in onResume! Redirecting to CallActivity...")
            val callIntent = Intent(this, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(callIntent)
            return
        }

        registerDebugReceiver()
        refreshList()
        val sm = SipManager.shared
        if (sm != null && sm.isRunning) {
            Log.i(TAG, "Restoring callbacks on active SipManager")
            val sipServer = binding.etSipServer.text.toString().trim()
            val username  = binding.etUsername.text.toString().trim()
            
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    sm.register(localIp)
                    Log.i(TAG, "Refreshed SIP registration on resume")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to refresh registration on resume: ${e.message}")
                }
            }
            
            sm.onIncomingInvite = { fromUser, sdpBody, callId, rawMsg, rawSdp ->
                lifecycleScope.launch(Dispatchers.Main) {
                    saveRecentCall(fromUser, isIncoming = true)
                    val activePort = sm.getLocalPort()
                    CallActivity.launchIncoming(
                        context = this@MainActivity,
                        sipServer = sipServer,
                        localUser = username,
                        remoteUser = fromUser,
                        localIp = localIp,
                        localPort = activePort,
                        sipPort = 5060,
                        incomingInvite = rawMsg,
                        incomingSdp = sdpBody,
                        incomingCallId = callId,
                        incomingRawSdp = rawSdp
                    )
                }
            }
        } else {
            val sipServer = binding.etSipServer.text.toString().trim()
            val username  = binding.etUsername.text.toString().trim()
            if (sipServer.isNotEmpty() && username.isNotEmpty()) {
                Log.i(TAG, "Resuming registration on screen active...")
                doRegister(sipServer, username)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(debugReceiver)
        } catch (_: Exception) {}
        // Clear callbacks to prevent Activity memory leak
        SipManager.shared?.apply {
            onRegistered = null
            onIncomingInvite = null
        }
        // DO NOT stop SipManager.shared here since onDestroy is called on configuration changes
    }

    private fun saveRecentCall(remoteUser: String, isIncoming: Boolean) {
        val sp = getSharedPreferences("ims_dc_history", MODE_PRIVATE)
        val historyStr = sp.getString("history", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(historyStr)
            val item = org.json.JSONObject().apply {
                put("number", remoteUser)
                put("isIncoming", isIncoming)
                put("timestamp", System.currentTimeMillis())
            }
            
            val newArr = org.json.JSONArray()
            newArr.put(item)
            for (i in 0 until arr.length()) {
                if (newArr.length() >= 50) break
                newArr.put(arr.getJSONObject(i))
            }
            
            sp.edit().putString("history", newArr.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi lưu lịch sử: ${e.message}")
        }
    }

    private data class RecentCallItem(
        val number: String,
        val isIncoming: Boolean,
        val timestamp: Long,
        val displayName: String? = null,
        val isReal: Boolean = false
    )

    // ───────────────── Danh sách kiểu One UI: tiêu đề nhóm + thẻ bo góc ─────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Grey section label above a card group: a day in Gần đây, a letter in Danh bạ. */
    private fun sectionHeader(text: String) = android.widget.TextView(this).apply {
        this.text = text
        setTextColor(android.graphics.Color.parseColor("#FF6E6E73"))
        textSize = 14f
        setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        setPadding(dp(20), dp(18), dp(20), dp(8))
    }

    /** Stacks [rows] into one rounded white card, with inset dividers between rows. */
    private fun addCard(container: android.widget.LinearLayout, rows: List<View>) {
        rows.forEachIndexed { i, row ->
            row.setBackgroundResource(when {
                rows.size == 1 -> com.demo.imsdc.R.drawable.bg_list_card
                i == 0 -> com.demo.imsdc.R.drawable.bg_list_card_top
                i == rows.size - 1 -> com.demo.imsdc.R.drawable.bg_list_card_bottom
                else -> com.demo.imsdc.R.drawable.bg_list_card_mid
            })
            // Ripple over the card background
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            row.foreground = androidx.core.content.ContextCompat.getDrawable(this, tv.resourceId)
            container.addView(row)
            if (i < rows.size - 1) {
                container.addView(android.widget.FrameLayout(this).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    addView(View(this@MainActivity).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#FFE5E5EA"))
                    }, android.widget.FrameLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(20), 0, dp(20), 0) })
                }, android.widget.LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }

    /** One list row: optional leading view, a title, optional trailing text. */
    private fun listRow(leading: View?, title: String, trailing: String?,
                        onClick: () -> Unit, onLong: (() -> Unit)? = null): View {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        if (leading != null) row.addView(leading)
        row.addView(android.widget.TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.parseColor("#FF111111"))
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(if (leading != null) dp(14) else 0, 0, dp(8), 0)
            }
        })
        if (trailing != null) row.addView(android.widget.TextView(this).apply {
            text = trailing
            setTextColor(android.graphics.Color.parseColor("#FF6E6E73"))
            textSize = 14f
        })
        return row
    }

    private fun dayLabel(ts: Long): String {
        val day = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val today = java.util.Calendar.getInstance()
        fun same(a: java.util.Calendar, b: java.util.Calendar) =
            a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
                a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (same(day, today)) return "Hôm nay"
        today.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (same(day, today)) return "Hôm qua"
        val s = java.text.SimpleDateFormat("EEEE, d 'tháng' M", java.util.Locale("vi", "VN")).format(java.util.Date(ts))
        return s.replaceFirstChar { it.uppercase() }
    }

    private fun displayRecentCalls() {
        val container = binding.layoutRecentCalls
        container.removeAllViews()
        binding.tvHeaderSub.text = "Gần đây"

        val items = ArrayList<RecentCallItem>()
        val sp = getSharedPreferences("ims_dc_history", MODE_PRIVATE)
        val historyStr = sp.getString("history", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(historyStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                items.add(RecentCallItem(obj.getString("number"), obj.getBoolean("isIncoming"),
                    obj.getLong("timestamp"), isReal = true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc lịch sử: ${e.message}")
        }

        // Thêm mock data giống ảnh màn hình
        val now = System.currentTimeMillis()
        if (items.none { it.number == "1001" }) items.add(RecentCallItem("1001", isIncoming = false, timestamp = now - 3600000 * 2))
        if (items.none { it.number == "1002" }) items.add(RecentCallItem("1002", isIncoming = true, timestamp = now - 3600000 * 3))
        if (items.none { it.number == "1003" }) items.add(RecentCallItem("1003", isIncoming = true, timestamp = now - 3600000 * 8))

        items.sortByDescending { it.timestamp }

        // Back-to-back calls with the same number and direction on the same day become one row "(n)".
        data class Merged(val item: RecentCallItem, var count: Int)
        val merged = ArrayList<Merged>()
        for (item in items) {
            val last = merged.lastOrNull()
            if (last != null && last.item.number == item.number && last.item.isIncoming == item.isIncoming &&
                dayLabel(last.item.timestamp) == dayLabel(item.timestamp)) last.count++
            else merged.add(Merged(item, 1))
        }

        for ((label, group) in merged.groupBy { dayLabel(it.item.timestamp) }) {
            container.addView(sectionHeader(label))
            addCard(container, group.map { m ->
                val number = m.item.number
                val savedName = Phonebook.nameOf(this, number)
                val icon = android.widget.ImageView(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(dp(20), dp(20))
                    setImageResource(if (m.item.isIncoming) com.demo.imsdc.R.drawable.ic_phone_incoming
                                     else com.demo.imsdc.R.drawable.ic_phone_outgoing)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor(if (m.item.isIncoming) "#FF3B82F6" else "#FF22C55E"))
                }
                val title = (savedName ?: number) + if (m.count > 1) " (${m.count})" else ""
                listRow(icon, title, android.text.format.DateFormat.format("HH:mm", m.item.timestamp).toString(),
                    onClick = { callFromList(number, CallOrigin.RECENTS) },
                    onLong = { editContactDialog(number, savedName) })
            })
        }
    }

    // ───────────────────────── Danh bạ (trong app) ─────────────────────────

    private val avatarColors = listOf("#FF7C83FF", "#FF5AB0F0", "#FFB388FF", "#FFFF8A80", "#FF4DB6AC", "#FFFFB74D")

    private fun avatar(initial: String, color: String) = android.widget.TextView(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(44), dp(44))
        setBackgroundResource(com.demo.imsdc.R.drawable.circle_avatar_bg)
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(color))
        text = initial
        gravity = android.view.Gravity.CENTER
        setTextColor(android.graphics.Color.WHITE)
        textSize = 18f
    }

    /** Redraws the list of the current tab (Gần đây or Danh bạ). */
    private fun refreshList() {
        when (currentTab) {
            HomeTab.RECENTS -> displayRecentCalls()
            HomeTab.CONTACTS -> displayContacts()
            HomeTab.KEYPAD -> {}
        }
    }

    private fun displayContacts() {
        val container = binding.layoutRecentCalls
        container.removeAllViews()
        val contacts = Phonebook.all(this).sortedBy { it.second.lowercase() }
        binding.tvHeaderSub.text = "${contacts.size} danh bạ có số điện thoại"

        container.addView(android.widget.Space(this), android.widget.LinearLayout.LayoutParams(-1, dp(4)))
        addCard(container, listOf(listRow(avatar("+", "#FF22C55E"), "Thêm liên hệ", null,
            onClick = { editContactDialog(null, null) })))

        if (contacts.isEmpty()) {
            container.addView(android.widget.TextView(this).apply {
                text = "Chưa có liên hệ nào. Nhấn giữ một cuộc gọi gần đây để lưu số."
                setTextColor(android.graphics.Color.parseColor("#FF6E6E73"))
                textSize = 14f
                setPadding(dp(20), dp(16), dp(20), dp(16))
            })
            return
        }
        val byLetter = contacts.groupBy { it.second.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "#" }
        for ((letter, group) in byLetter) {
            container.addView(sectionHeader(letter))
            addCard(container, group.map { (number, name) ->
                val color = avatarColors[Math.floorMod(name.hashCode(), avatarColors.size)]
                listRow(avatar(name.trim().firstOrNull()?.uppercase() ?: "?", color), name, null,
                    onClick = { callFromList(number, CallOrigin.CONTACTS) },
                    onLong = {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("$name · $number")
                            .setItems(arrayOf("Sửa", "Xóa")) { _, which ->
                                if (which == 0) editContactDialog(number, name)
                                else { Phonebook.remove(this, number); refreshList() }
                            }
                            .show()
                    })
            })
        }
    }

    /** Add (number null) or edit a contact; [number] prefilled from a recent call. */
    private fun editContactDialog(number: String?, name: String?) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, 0)
        }
        val etName = android.widget.EditText(this).apply {
            hint = "Tên"; setText(name ?: ""); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val etNumber = android.widget.EditText(this).apply {
            hint = "Số"; setText(number ?: ""); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        box.addView(etName); box.addView(etNumber)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (name != null) "Sửa liên hệ" else "Lưu vào danh bạ")
            .setView(box)
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Lưu") { _, _ ->
                val newName = etName.text.toString().trim()
                val newNumber = etNumber.text.toString().trim()
                if (newName.isEmpty() || newNumber.isEmpty()) {
                    Toast.makeText(this, "Cần nhập tên và số", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (number != null && Phonebook.normalize(number) != Phonebook.normalize(newNumber)) Phonebook.remove(this, number)
                Phonebook.put(this, newNumber, newName)
                refreshList()
            }
            .show()
        etName.requestFocus()
    }
}
