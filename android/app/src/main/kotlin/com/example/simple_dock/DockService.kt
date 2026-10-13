package com.example.simple_dock

import android.app.*
import android.app.usage.UsageStatsManager
import android.app.usage.UsageEvents
import android.content.*
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import org.json.JSONArray

class DockService : Service() {
    companion object {
        const val ACTION_SETTINGS_CHANGED = "com.example.simple_dock.SETTINGS_CHANGED"
        const val ACTION_COLLAPSE = "com.example.simple_dock.COLLAPSE"
        private const val CHANNEL_ID = "simple_dock_dock"
        private const val NOTIFICATION_ID = 71

        fun installedApps(context: Context): List<Map<String, String>> {
            val manager = context.packageManager
            return manager.getInstalledApplications(0)
                .filter { it.packageName != context.packageName && manager.getLaunchIntentForPackage(it.packageName) != null }
                .map { mapOf("package" to it.packageName, "label" to manager.getApplicationLabel(it).toString()) }
                .sortedBy { it["label"]!!.lowercase() }
        }
    }

    private lateinit var windowManager: WindowManager
    private var dockView: LinearLayout? = null
    private var drawerView: View? = null
    private var collapsedView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { collapse() }
    private var lastForegroundPackage: String? = null
    private var lastForegroundEventTime = 0L
    private var keyboardCollapsed = false
    private val foregroundMonitor = object : Runnable {
        override fun run() {
            monitorKeyboard()
            monitorForegroundApp()
            handler.postDelayed(this, 700)
        }
    }
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SETTINGS_CHANGED -> {
                    keyboardCollapsed = false
                    showDock()
                }
                ACTION_COLLAPSE -> collapse()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Simple Dock", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        registerReceiver(settingsReceiver, IntentFilter(ACTION_SETTINGS_CHANGED), RECEIVER_NOT_EXPORTED)
        ensureInitialRecent()
        lastForegroundEventTime = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Simple Dock")
            .setContentText("ドックを表示中")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build())
        showDock()
        handler.post(foregroundMonitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(settingsReceiver)
        handler.removeCallbacks(foregroundMonitor)
        handler.removeCallbacks(hideRunnable)
        removeViews()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prefs() = getSharedPreferences("dock", MODE_PRIVATE)

    private fun monitorForegroundApp() {
        if (!hasUsageAccess()) return
        val usageStats = getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val installedPackages = installedApps(this).mapNotNull { it["package"] }.toSet()
        val events = usageStats.queryEvents(now - 3000, now)
        val event = UsageEvents.Event()
        var latestForeground: Pair<Long, String>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val foregroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            if (foregroundEvent &&
                event.timeStamp > lastForegroundEventTime &&
                event.packageName != packageName &&
                installedPackages.contains(event.packageName)
            ) {
                if (latestForeground == null || event.timeStamp > latestForeground!!.first) {
                    latestForeground = event.timeStamp to event.packageName
                }
            }
        }
        val (timeStamp, foregroundPackage) = latestForeground ?: return
        lastForegroundEventTime = timeStamp
        if (foregroundPackage == lastForegroundPackage) return
        lastForegroundPackage = foregroundPackage
        updateRecent(foregroundPackage)
        if (prefs().getBoolean("hideOnLaunch", true)) {
            collapse()
        } else {
            showDock()
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun updateRecent(packageName: String) {
        val recent = orderedPackages("recent_order")
        recent.remove(packageName)
        recent.add(0, packageName)
        saveOrderedPackages("recent_order", recent.distinct().take(12))
    }

    private fun monitorKeyboard() {
        if (!prefs().getBoolean("hideOnKeyboard", true)) return
        val view = dockView ?: drawerView ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val imeVisible = view?.rootWindowInsets?.isVisible(WindowInsets.Type.ime())
            if (imeVisible != null) {
                setKeyboardVisibility(imeVisible)
                return
            }
        }
        val visibleFrame = Rect()
        val visibleHeight = if (view != null) {
            view.getWindowVisibleDisplayFrame(visibleFrame)
            resources.displayMetrics.heightPixels - visibleFrame.bottom
        } else {
            0
        }
        val keyboardVisible = visibleHeight > dp(160)
        setKeyboardVisibility(keyboardVisible)
    }

    private fun observeKeyboardVisibility(view: View) {
        view.viewTreeObserver.addOnGlobalLayoutListener {
            if (!prefs().getBoolean("hideOnKeyboard", true)) return@addOnGlobalLayoutListener
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            setKeyboardVisibility(
                resources.displayMetrics.heightPixels - frame.bottom > dp(160),
            )
        }
    }

    private fun setKeyboardVisibility(keyboardVisible: Boolean) {
        if (keyboardVisible && !keyboardCollapsed && (dockView != null || drawerView != null)) {
            keyboardCollapsed = true
            collapse()
        } else if (!keyboardVisible && keyboardCollapsed) {
            keyboardCollapsed = false
            if (drawerView == null && Settings.canDrawOverlays(this)) showDock()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun orderedPackages(key: String): MutableList<String> {
        val stored = prefs().getString(key, null)
        if (stored != null) return runCatching {
            val json = JSONArray(stored)
            MutableList(json.length()) { index -> json.getString(index) }
        }.getOrDefault(mutableListOf())
        return prefs().getStringSet(key.removeSuffix("_order"), emptySet())!!.toMutableList()
    }

    private fun saveOrderedPackages(key: String, values: List<String>) {
        val json = JSONArray()
        values.forEach(json::put)
        prefs().edit().putString(key, json.toString()).apply()
    }

    private fun hiddenPackages(): MutableSet<String> = prefs().getStringSet("hidden", emptySet())!!.toMutableSet()

    private fun setHidden(packageName: String, hidden: Boolean) {
        val values = hiddenPackages()
        if (hidden) values.add(packageName) else values.remove(packageName)
        prefs().edit().putStringSet("hidden", values).apply()
    }

    private fun dockBackgroundColor(): Int = Color.argb(
        (prefs().getFloat("opacity", .92f) * 255).toInt(),
        28,
        30,
        36
    )

    private fun params(width: Int, height: Int, gravity: Int): WindowManager.LayoutParams = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        this.gravity = gravity
        y = dp(8)
    }

    private fun showDock() {
        if (!Settings.canDrawOverlays(this)) return
        handler.removeCallbacks(hideRunnable)
        removeViews()
        val vertical = prefs().getString("orientation", "bottom") != "bottom"
        val size = dp(prefs().getFloat("iconSize", 56f).toInt())
        val root = LinearLayout(this).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(dockBackgroundColor())
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                setKeyboardVisibility(insets.isVisible(WindowInsets.Type.ime()))
                insets
            }
        }
        observeKeyboardVisibility(root)
        val pinned = orderedPackages("pinned_order").filterNot(hiddenPackages()::contains)
        val recent = orderedPackages("recent_order")
            .filterNot(hiddenPackages()::contains)
            .filterNot { pinned.contains(it) }
        val recentCount = prefs().getInt("recentCount", 5).coerceAtLeast(1)
        val packages = (pinned + recent).take(recentCount + pinned.size)
        addHideButton(root, size, vertical)
        packages.forEachIndexed { index, packageName ->
            if (index == pinned.size && pinned.isNotEmpty() && recent.isNotEmpty()) {
                addSectionDivider(root, size, vertical)
            }
            addAppButton(root, packageName, size, vertical, pinned.contains(packageName), index)
        }
        addDrawerButton(root, size, vertical)
        dockView = root
        val gravity = when (prefs().getString("orientation", "bottom")) {
            "left" -> Gravity.START or Gravity.CENTER_VERTICAL
            "right" -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        val width = if (vertical) size + dp(36) else WindowManager.LayoutParams.WRAP_CONTENT
        val height = if (vertical) WindowManager.LayoutParams.WRAP_CONTENT else size + dp(36)
        windowManager.addView(root, params(width, height, gravity))
        if (prefs().getBoolean("hideAfterDelay", false)) handler.postDelayed(hideRunnable, 6000)
    }

    private fun addHideButton(root: LinearLayout, size: Int, vertical: Boolean) {
        val arrowButtonSize = dp(32)
        val button = TextView(this).apply {
            text = when (prefs().getString("orientation", "bottom")) {
                "left" -> "◀"
                "right" -> "▶"
                else -> "▼"
            }
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "ドックを隠す"
            setOnClickListener { collapse() }
        }
        root.addView(button, LinearLayout.LayoutParams(arrowButtonSize, arrowButtonSize).apply {
            if (vertical) setMargins(0, dp(5), 0, dp(5)) else setMargins(dp(3), 0, dp(3), 0)
        })
    }

    private fun addSectionDivider(root: LinearLayout, size: Int, vertical: Boolean) {
        val divider = View(this).apply { setBackgroundColor(Color.argb(90, 255, 255, 255)) }
        root.addView(divider, if (vertical) {
            LinearLayout.LayoutParams(size, dp(1)).apply { setMargins(0, dp(5), 0, dp(5)) }
        } else {
            LinearLayout.LayoutParams(dp(1), size).apply { setMargins(dp(5), 0, dp(5), 0) }
        })
    }

    private fun addAppButton(root: LinearLayout, packageName: String, size: Int, vertical: Boolean, pinned: Boolean, index: Int) {
        val manager = packageManager
        val info = try { manager.getApplicationInfo(packageName, 0) } catch (_: Exception) { return }
        val label = manager.getApplicationLabel(info).toString()
        val iconShape = prefs().getString("iconShape", "round") ?: "round"
        val cornerRadius = when (iconShape) {
            "square" -> dp(4).toFloat()
            "squircle" -> size * .22f
            else -> size * .35f
        }
        val button = ImageButton(this).apply {
            setImageDrawable(manager.getApplicationIcon(info))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(size / 8, size / 8, size / 8, size / 8)
            background = GradientDrawable().apply {
                shape = if (iconShape == "round" || iconShape == "circle") {
                    GradientDrawable.OVAL
                } else {
                    GradientDrawable.RECTANGLE
                }
                this.cornerRadius = cornerRadius
                setColor(Color.TRANSPARENT)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    if (iconShape == "round" || iconShape == "circle") {
                        outline.setOval(0, 0, size, size)
                    } else {
                        outline.setRoundRect(0, 0, size, size, cornerRadius)
                    }
                }
            }
            contentDescription = label
            setOnClickListener { launch(packageName) }
            setOnLongClickListener { showAppMenu(it, packageName, pinned); true }
            if (pinned) installDrag(this, packageName)
            setOnDragListener { _, event ->
                if (event.action == DragEvent.ACTION_DROP) {
                    val dragged = event.clipDescription?.label?.toString()
                    if (dragged != null && dragged != packageName) reorderPinned(dragged, packageName)
                }
                true
            }
        }
        root.addView(button, LinearLayout.LayoutParams(size, size).apply {
            if (vertical) setMargins(0, dp(5), 0, dp(5)) else setMargins(dp(5), 0, dp(5), 0)
        })
    }

    private fun addDrawerButton(root: LinearLayout, size: Int, vertical: Boolean) {
        val button = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_sort_by_size)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.DKGRAY) }
            contentDescription = "アプリドロワー"
            setOnClickListener { showDrawer() }
        }
        root.addView(button, LinearLayout.LayoutParams(size, size).apply {
            if (vertical) setMargins(0, dp(5), 0, dp(5)) else setMargins(dp(5), 0, dp(5), 0)
        })
    }

    private fun ensureInitialRecent() {
        if (orderedPackages("recent_order").isEmpty()) {
            val firstApp = installedApps(this).firstOrNull()?.get("package")
            val initial = mutableListOf<String>()
            if (firstApp != null) initial.add(firstApp)
            saveOrderedPackages("recent_order", initial)
        }
    }

    private fun installDrag(view: View, packageName: String) {
        view.setOnLongClickListener {
            view.startDragAndDrop(
                ClipData.newPlainText(packageName, packageName),
                View.DragShadowBuilder(view),
                null,
                0,
            )
        }
    }

    private fun reorderPinned(dragged: String, target: String) {
        val values = orderedPackages("pinned_order")
        val from = values.indexOf(dragged)
        val to = values.indexOf(target)
        if (from < 0 || to < 0 || from == to) return
        values.removeAt(from)
        values.add(to.coerceAtMost(values.size), dragged)
        saveOrderedPackages("pinned_order", values)
        showDock()
    }

    private fun showAppMenu(anchor: View, packageName: String, pinned: Boolean) {
        val actions = mutableListOf("${if (pinned) "unpin" else "pin"}" to { togglePin(packageName) })
        actions.add("hide" to { setHidden(packageName, true); showDock() })
        showContextMenu(anchor, actions)
    }

    private fun showDrawer() {
        closeDrawer(false)
        showDock()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.argb(245, 28, 30, 36))
            }
        }
        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply {
            columnCount = 4
            useDefaultMargins = false
        }
        val hidden = hiddenPackages()
        val drawerApps = installedApps(this).sortedWith(
            compareBy<Map<String, String>> { hidden.contains(it["package"]) }
                .thenBy { it["label"]?.lowercase() ?: "" },
        )
        var hideHeaderAdded = false
        drawerApps.forEach { app ->
            val appPackage = app["package"] ?: return@forEach
            if (!hideHeaderAdded && hidden.contains(appPackage)) {
                hideHeaderAdded = true
                val hideHeader = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6), dp(4), dp(6), dp(4))
                    addView(View(this@DockService).apply {
                        setBackgroundColor(Color.argb(90, 255, 255, 255))
                    }, LinearLayout.LayoutParams(0, dp(1), 1f))
                    addView(TextView(this@DockService).apply {
                        text = "hide app"
                        textSize = 12f
                        setTextColor(Color.argb(180, 255, 255, 255))
                        setPadding(dp(10), 0, dp(10), 0)
                    })
                    addView(View(this@DockService).apply {
                        setBackgroundColor(Color.argb(90, 255, 255, 255))
                    }, LinearLayout.LayoutParams(0, dp(1), 1f))
                }
                grid.addView(hideHeader, GridLayout.LayoutParams().apply {
                    width = -1
                    height = dp(34)
                    columnSpec = GridLayout.spec(0, 4)
                })
            }
            val info = packageManager.getApplicationInfo(appPackage, 0)
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(5), dp(8), dp(5), dp(8))
                addView(ImageView(this@DockService).apply {
                    setImageDrawable(packageManager.getApplicationIcon(info))
                    layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                })
                addView(TextView(this@DockService).apply {
                    text = app["label"]
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(-1, dp(30))
                })
                setOnClickListener { launch(appPackage) }
                setOnLongClickListener { showDrawerMenu(this, appPackage); true }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(114)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            })
        }
        scroll.addView(grid)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                closeDrawer()
                true
            } else false
        }
        drawerView = panel
        val orientation = prefs().getString("orientation", "bottom") ?: "bottom"
        val dockThickness = dp(prefs().getFloat("iconSize", 56f).toInt()) + dp(36)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val drawerWidth = if (orientation == "bottom") {
            dp(460).coerceAtMost(screenWidth - dp(24)).coerceAtLeast(dp(1))
        } else {
            dp(460).coerceAtMost(screenWidth - dockThickness - dp(52)).coerceAtLeast(dp(1))
        }
        val drawerHeight = if (orientation == "bottom") {
            dp(720).coerceAtMost(screenHeight - dockThickness - dp(52)).coerceAtLeast(dp(1))
        } else {
            dp(720).coerceAtMost(screenHeight - dp(24)).coerceAtLeast(dp(1))
        }
        val drawerGravity = when (orientation) {
            "left" -> Gravity.START or Gravity.CENTER_VERTICAL
            "right" -> Gravity.END or Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        val drawerParams = WindowManager.LayoutParams(
            drawerWidth,
            drawerHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = drawerGravity
            x = if (orientation == "bottom") 0 else dockThickness + dp(52)
            y = if (orientation == "bottom") dockThickness + dp(52) else 0
        }
        windowManager.addView(panel, drawerParams)
    }

    private fun closeDrawer(restoreDock: Boolean = true) {
        val wasOpen = drawerView != null
        drawerView?.let { runCatching { windowManager.removeView(it) } }
        drawerView = null
        if (restoreDock && wasOpen && Settings.canDrawOverlays(this)) showDock()
    }

    private fun showDrawerMenu(anchor: View, packageName: String) {
        val pinned = orderedPackages("pinned_order").contains(packageName)
        val hidden = hiddenPackages().contains(packageName)
        showContextMenu(anchor, listOf(
            "${if (pinned) "unpin" else "pin"}" to { togglePin(packageName); showDrawer() },
            "${if (hidden) "show" else "hide"}" to {
                setHidden(packageName, !hidden)
                showDrawer()
            },
        ))
    }

    private fun showContextMenu(anchor: View, actions: List<Pair<String, () -> Unit>>) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.rgb(48, 50, 58))
            }
        }
        val popup = PopupWindow(content, dp(180), WindowManager.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(8).toFloat()
        }
        actions.forEach { (label, action) ->
            content.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { popup.dismiss(); action() }
            }, LinearLayout.LayoutParams(-1, dp(44)))
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(dp(180), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupWidth = dp(180)
        val horizontalOffset = (anchor.width - popupWidth) / 2
        val verticalOffset = -content.measuredHeight - anchor.height
        popup.showAsDropDown(anchor, horizontalOffset, verticalOffset)
    }

    private fun launch(packageName: String) {
        closeDrawer(false)
        updateRecent(packageName)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val options = ActivityOptions.makeBasic().apply {
                    runCatching {
                        javaClass.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                            .invoke(this, 1)
                    }
                }
                startActivity(it, options.toBundle())
            } else {
                startActivity(it)
            }
        }
        if (prefs().getBoolean("hideOnLaunch", true)) collapse() else showDock()
    }

    private fun togglePin(packageName: String) {
        val updated = orderedPackages("pinned_order")
        val added = !updated.contains(packageName)
        if (added) updated.add(packageName) else updated.remove(packageName)
        saveOrderedPackages("pinned_order", updated)
        showDock()
        Toast.makeText(this, if (added) "ドックに追加しました" else "ドックから外しました", Toast.LENGTH_SHORT).show()
    }

    private fun installSwipe(view: View, vertical: Boolean) {
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; false }
                MotionEvent.ACTION_UP -> {
                    val delta = if (vertical) event.rawX - downX else event.rawY - downY
                    val outward = when (prefs().getString("orientation", "bottom")) {
                        "left" -> delta < -80
                        "right" -> delta > 80
                        else -> delta > 80
                    }
                    if (outward) { collapse(); true } else false
                }
                else -> false
            }
        }
    }

    private fun collapse() {
        handler.removeCallbacks(hideRunnable)
        removeViews()
        val orientation = prefs().getString("orientation", "bottom") ?: "bottom"
        val vertical = orientation != "bottom"
        val iconSize = dp(prefs().getFloat("iconSize", 56f).toInt())
        val arrowButtonSize = dp(30)
        val arrow = TextView(this).apply {
            text = when (prefs().getString("orientation", "bottom")) {
                "left" -> "▶"
                "right" -> "◀"
                else -> "▲"
            }
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                if (orientation == "bottom") {
                    val radius = dp(14).toFloat()
                    cornerRadii = floatArrayOf(
                        radius, radius,
                        radius, radius,
                        0f, 0f,
                        0f, 0f,
                    )
                } else {
                    cornerRadius = dp(14).toFloat()
                }
                setColor(dockBackgroundColor())
            }
            setOnClickListener { showDock() }
        }
        collapsedView = arrow
        val width = if (vertical) arrowButtonSize else iconSize + dp(16)
        val height = if (vertical) iconSize + dp(16) else arrowButtonSize
        val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)
        val collapsedGravity = when (orientation) {
            "left" -> Gravity.START or Gravity.TOP
            "right" -> Gravity.END or Gravity.TOP
            else -> Gravity.BOTTOM or Gravity.START
        }
        val collapsedParams = params(width, height, collapsedGravity).apply {
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            x = if (orientation == "bottom") {
                (maxX * prefs().getFloat("showPosition", 50f) / 100f).toInt()
                    .coerceIn(0, maxX)
            } else {
                0
            }
            y = if (orientation == "bottom") {
                0
            } else {
                (maxY * prefs().getFloat("showPosition", 50f) / 100f).toInt()
                    .coerceIn(0, maxY)
            }
        }
        windowManager.addView(arrow, collapsedParams)
    }

    private fun removeViews() {
        removeDockViews()
        drawerView?.let { runCatching { windowManager.removeView(it) } }
        collapsedView?.let { runCatching { windowManager.removeView(it) } }
        dockView = null
        drawerView = null
        collapsedView = null
    }

    private fun removeDockViews() {
        dockView?.let { runCatching { windowManager.removeView(it) } }
        collapsedView?.let { runCatching { windowManager.removeView(it) } }
        dockView = null
        collapsedView = null
    }
}
