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

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            Log.i(TAG, "debugReceiver onReceive: action=${intent?.action}")
            if (intent?.action == "com.demo.imsdc.CALL") {
                val target = intent.getStringExtra("target")
                Log.i(TAG, "debugReceiver CALL target=$target")
                if (target != null) {
                    dialDigits = target
                    binding.tvDialDigits.text = target
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
            binding.tvDialDigits.text = autoCall
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

    private var isDialpadVisible = true

    private fun showDialpad(animate: Boolean = true) {
        if (isDialpadVisible && binding.layoutDialpadPanel.visibility == View.VISIBLE) return
        isDialpadVisible = true
        if (animate) {
            val transition = android.transition.AutoTransition().apply {
                duration = 200
            }
            android.transition.TransitionManager.beginDelayedTransition(binding.layoutDialer, transition)
        }
        binding.layoutDialpadPanel.visibility = View.VISIBLE
        binding.fabShowDialpad.visibility = View.GONE
    }

    private fun hideDialpad(animate: Boolean = true) {
        if (!isDialpadVisible && binding.layoutDialpadPanel.visibility == View.GONE) return
        isDialpadVisible = false
        if (animate) {
            val transition = android.transition.AutoTransition().apply {
                duration = 200
            }
            android.transition.TransitionManager.beginDelayedTransition(binding.layoutDialer, transition)
        }
        binding.layoutDialpadPanel.visibility = View.GONE
        binding.fabShowDialpad.visibility = View.VISIBLE
    }

    private fun doLogout() {
        sipManager?.stop()
        dialDigits = ""
        binding.tvDialDigits.text = ""
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
                binding.tvDialDigits.text = dialDigits
            }
        }

        // Nút Backspace xóa 1 ký tự
        binding.btnBackspace.setOnClickListener {
            if (dialDigits.isNotEmpty()) {
                dialDigits = dialDigits.substring(0, dialDigits.length - 1)
                binding.tvDialDigits.text = dialDigits
            }
        }

        // Nhấn giữ Backspace xóa sạch ký tự
        binding.btnBackspace.setOnLongClickListener {
            dialDigits = ""
            binding.tvDialDigits.text = dialDigits
            true
        }

        // Nút Thu gọn Dialpad (Góc trái hàng gọi)
        binding.btnLogout.setOnClickListener {
            hideDialpad(true)
        }
        binding.btnLogout.setOnLongClickListener {
            doLogout()
            true
        }

        // Nút Nổi FAB mở Dialpad
        binding.fabShowDialpad.setOnClickListener {
            showDialpad(true)
        }

        // Tab Phone mở / bật tắt Dialpad
        binding.tabContacts.setOnClickListener {
            showingContacts = true
            if (isDialpadVisible) hideDialpad(true)
            refreshList()
        }
        binding.tabPhone.setOnClickListener {
            if (showingContacts) { showingContacts = false; refreshList(); return@setOnClickListener }
            if (!isDialpadVisible) {
                showDialpad(true)
            } else {
                hideDialpad(true)
            }
        }

        // Khi cuộn hoặc chạm vào vùng lịch sử cuộc gọi -> Tự động thu gọn dialpad
        binding.scrollRecentCalls.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            if (Math.abs(scrollY - oldScrollY) > 8 && isDialpadVisible) {
                hideDialpad(true)
            }
        }
        binding.scrollRecentCalls.setOnClickListener {
            if (isDialpadVisible) hideDialpad(true)
        }
        binding.layoutRecentCalls.setOnClickListener {
            if (isDialpadVisible) hideDialpad(true)
        }

        // Nút 3 chấm Menu: Đăng xuất & Cài đặt
        binding.btnMoreMenu.setOnClickListener { v ->
            val popup = android.widget.PopupMenu(this, v)
            popup.menu.add("Đăng xuất tài khoản")
            popup.menu.add("Thu gọn bàn phím")
            popup.setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    "Đăng xuất tài khoản" -> doLogout()
                    "Thu gọn bàn phím" -> hideDialpad(true)
                }
                true
            }
            popup.show()
        }

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
                return@setOnClickListener
            }

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
                        binding.tvDialDigits.text = ""
                        
                        // Chuyển giao diện sang bàn phím Samsung OneUI
                        binding.layoutSetup.visibility = View.GONE
                        binding.layoutDialer.visibility = View.VISIBLE
                        
                        Toast.makeText(this@MainActivity, "Đăng ký thành công!", Toast.LENGTH_SHORT).show()

                        val autoCall = intent.getStringExtra("call")
                        if (!autoCall.isNullOrEmpty()) {
                            dialDigits = autoCall
                            binding.tvDialDigits.text = autoCall
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
                if (newArr.length() >= 5) break
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

    private fun displayRecentCalls() {
        val container = binding.layoutRecentCalls
        container.removeAllViews()
        
        val items = ArrayList<RecentCallItem>()
        val sp = getSharedPreferences("ims_dc_history", MODE_PRIVATE)
        val historyStr = sp.getString("history", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(historyStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val num = obj.getString("number")
                val incoming = obj.getBoolean("isIncoming")
                val ts = obj.getLong("timestamp")
                items.add(RecentCallItem(num, incoming, ts, isReal = true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi đọc lịch sử: ${e.message}")
        }

        // Thêm mock data giống ảnh màn hình
        val now = System.currentTimeMillis()
        if (items.none { it.number == "1001" }) {
            items.add(RecentCallItem("1001", isIncoming = false, timestamp = now - 3600000 * 2))
        }
        if (items.none { it.number == "1002" }) {
            items.add(RecentCallItem("1002", isIncoming = true, timestamp = now - 3600000 * 3))
        }
        if (items.none { it.number == "1003" }) {
            items.add(RecentCallItem("1003", isIncoming = true, timestamp = now - 3600000 * 8))
        }

        // Sắp xếp theo thời gian mới nhất lên đầu
        items.sortByDescending { it.timestamp }

        val density = resources.displayMetrics.density
        val paddingPx = (12 * density).toInt()

        for (item in items) {
            val number = item.number
            val isIncoming = item.isIncoming
            val timestamp = item.timestamp
            val rawName = item.displayName
            
            // Tên trong danh bạ của app, nếu có
            val savedName = Phonebook.nameOf(this, number)
            val displayName = savedName ?: rawName ?: number

            // Create row container (Horizontal LinearLayout)
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                setBackgroundResource(typedValue.resourceId)
                setPadding(paddingPx, (10 * density).toInt(), paddingPx, (10 * density).toInt())
                
                setOnClickListener {
                    dialDigits = number
                    binding.tvDialDigits.text = dialDigits
                    showDialpad(true)
                }
                setOnLongClickListener { editContactDialog(number, savedName); true }
            }

            // 1. Avatar
            val avatarFrame = android.widget.FrameLayout(this).apply {
                val size = (44 * density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
                setBackgroundResource(com.demo.imsdc.R.drawable.circle_avatar_bg)
                
                val colorHex = when (number) {
                    "1001" -> "#FF4CAF50" // Green
                    "1002" -> "#FFE91E63" // Pink
                    "1003" -> "#FF9C27B0" // Purple
                    else -> "#FF7C83FF" // Slate Blue
                }
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(colorHex))
            }
            
            val initials = displayName.firstOrNull()?.toString()?.uppercase() ?: "?"
            val tvInitials = android.widget.TextView(this).apply {
                text = initials
                setTextColor(android.graphics.Color.WHITE)
                textSize = 18f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                gravity = android.view.Gravity.CENTER
            }
            avatarFrame.addView(tvInitials, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            row.addView(avatarFrame)

            // 2. Middle Details (Name, Subtitle)
            val detailsLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                    setMargins((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                }
                layoutParams = lp
            }

            val tvName = android.widget.TextView(this).apply {
                text = displayName
                setTextColor(android.graphics.Color.parseColor("#FF111111"))
                textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            detailsLayout.addView(tvName)

            val subtitleLayout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, (2 * density).toInt(), 0, 0)
                }
                layoutParams = lp
            }

            val imgDirection = android.widget.ImageView(this).apply {
                val size = (14 * density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
                
                if (isIncoming) {
                    setImageResource(com.demo.imsdc.R.drawable.ic_phone_incoming)
                    imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF22C55E")) // Green
                } else {
                    setImageResource(com.demo.imsdc.R.drawable.ic_phone_outgoing)
                    imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF3B82F6")) // Blue
                }
            }
            subtitleLayout.addView(imgDirection)

            val tvSub = android.widget.TextView(this).apply {
                text = if (savedName != null) " $number" else " VoLTE Vietnam"
                setTextColor(android.graphics.Color.parseColor("#FF8E8E93"))
                textSize = 12f
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((4 * density).toInt(), 0, 0, 0)
                }
                layoutParams = lp
            }
            subtitleLayout.addView(tvSub)
            detailsLayout.addView(subtitleLayout)
            row.addView(detailsLayout)

            // 3. Time / Timestamp
            val timeStr = android.text.format.DateFormat.format("HH:mm", timestamp).toString()
            val tvTime = android.widget.TextView(this).apply {
                text = timeStr
                setTextColor(android.graphics.Color.parseColor("#FF8E8E93"))
                textSize = 12f
            }
            row.addView(tvTime)

            // 4. Info Button (i)
            val infoBtn = android.widget.ImageView(this).apply {
                val size = (24 * density).toInt()
                val lp = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    setMargins((12 * density).toInt(), 0, 0, 0)
                }
                layoutParams = lp
                setImageResource(com.demo.imsdc.R.drawable.ic_info)
                imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF8E8E93"))
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, typedValue, true)
                setBackgroundResource(typedValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener { editContactDialog(number, savedName) }
            }
            row.addView(infoBtn)

            container.addView(row)
        }
    }

    // ───────────────────────── Danh bạ (trong app) ─────────────────────────

    private var showingContacts = false

    private fun refreshList() {
        val active = android.graphics.Color.parseColor("#FF22C55E")
        val idle = android.graphics.Color.parseColor("#FF8E8E93")
        (binding.tabPhone.getChildAt(1) as? android.widget.TextView)?.setTextColor(if (showingContacts) idle else active)
        (binding.tabContacts.getChildAt(1) as? android.widget.TextView)?.setTextColor(if (showingContacts) active else idle)
        if (showingContacts) displayContacts() else displayRecentCalls()
    }

    private fun displayContacts() {
        val container = binding.layoutRecentCalls
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val pad = (12 * density).toInt()

        fun row(title: String, subtitle: String?, initial: String, color: String,
                onClick: () -> Unit, onLong: (() -> Unit)? = null) {
            val r = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val tv = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setPadding(pad, (10 * density).toInt(), pad, (10 * density).toInt())
                setOnClickListener { onClick() }
                if (onLong != null) setOnLongClickListener { onLong(); true }
            }
            val avatar = android.widget.FrameLayout(this).apply {
                val size = (44 * density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
                setBackgroundResource(com.demo.imsdc.R.drawable.circle_avatar_bg)
                backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(color))
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = initial
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 18f
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    gravity = android.view.Gravity.CENTER
                }, android.widget.FrameLayout.LayoutParams(-1, -1))
            }
            r.addView(avatar)
            val details = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(pad, 0, pad, 0) }
            }
            details.addView(android.widget.TextView(this).apply {
                text = title
                setTextColor(android.graphics.Color.parseColor("#FF111111"))
                textSize = 16f
                setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            })
            if (subtitle != null) details.addView(android.widget.TextView(this).apply {
                text = subtitle
                setTextColor(android.graphics.Color.parseColor("#FF8E8E93"))
                textSize = 12f
            })
            r.addView(details)
            container.addView(r)
        }

        row("Thêm liên hệ", null, "+", "#FF22C55E", onClick = { editContactDialog(null, null) })
        val contacts = Phonebook.all(this)
        if (contacts.isEmpty()) {
            container.addView(android.widget.TextView(this).apply {
                text = "Chưa có liên hệ nào. Nhấn giữ một cuộc gọi gần đây để lưu số."
                setTextColor(android.graphics.Color.parseColor("#FF8E8E93"))
                textSize = 13f
                setPadding(pad, pad, pad, pad)
            })
        }
        for ((number, name) in contacts) {
            row(name, number, name.firstOrNull()?.uppercase() ?: "?", "#FF7C83FF",
                onClick = {
                    dialDigits = number
                    binding.tvDialDigits.text = dialDigits
                    showDialpad(true)
                },
                onLong = {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(name)
                        .setItems(arrayOf("Sửa", "Xóa")) { _, which ->
                            if (which == 0) editContactDialog(number, name)
                            else { Phonebook.remove(this, number); refreshList() }
                        }
                        .show()
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
