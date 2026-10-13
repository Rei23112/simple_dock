package com.example.simple_dock

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
	private val channelName = "simple_dock/android"

	override fun onResume() {
		super.onResume()
		val prefs = getSharedPreferences("dock", MODE_PRIVATE)
		if (prefs.getBoolean("overlayEnabled", false) && Settings.canDrawOverlays(this)) {
			val intent = Intent(this, DockService::class.java)
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
		}
	}

	override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
		super.configureFlutterEngine(flutterEngine)
		MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
			when (call.method) {
				"hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))
				"hasUsageAccess" -> result.success(hasUsageAccess())
				"openUsageAccessSettings" -> {
					startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
					result.success(null)
				}
				"hasBatteryOptimizationExemption" -> result.success(hasBatteryOptimizationExemption())
				"openBatterySettings" -> {
					val intent = Intent(
						Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
						Uri.parse("package:$packageName"),
					)
					startActivity(intent)
					result.success(null)
				}
				"refreshDock" -> {
					sendBroadcast(Intent(DockService.ACTION_SETTINGS_CHANGED))
					result.success(null)
				}
				"collapseDock" -> {
					sendBroadcast(Intent(DockService.ACTION_COLLAPSE))
					result.success(null)
				}
				"openOverlaySettings" -> {
					startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
					result.success(null)
				}
				"startDock" -> {
					val intent = Intent(this, DockService::class.java)
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
					result.success(null)
				}
				"stopDock" -> {
					stopService(Intent(this, DockService::class.java))
					result.success(null)
				}
				"getApps" -> result.success(DockService.installedApps(this))
				"getAppsWithVisibility" -> result.success(DockService.installedApps(this).map { app ->
					mapOf("package" to app["package"], "label" to app["label"], "hidden" to getSharedPreferences("dock", MODE_PRIVATE).getStringSet("hidden", emptySet())!!.contains(app["package"]))
				})
				"setAppHidden" -> {
					val arguments = call.arguments as Map<*, *>
					val hidden = getSharedPreferences("dock", MODE_PRIVATE).getStringSet("hidden", emptySet())!!.toMutableSet()
					val appPackage = arguments["package"] as String
					if (arguments["hidden"] as Boolean) hidden.add(appPackage) else hidden.remove(appPackage)
					getSharedPreferences("dock", MODE_PRIVATE).edit().putStringSet("hidden", hidden).apply()
					sendBroadcast(Intent(DockService.ACTION_SETTINGS_CHANGED))
					result.success(null)
				}
				"pinApp" -> {
					getSharedPreferences("dock", MODE_PRIVATE).edit().putStringSet(
						"pinned", getSharedPreferences("dock", MODE_PRIVATE).getStringSet("pinned", emptySet())!!.plus(call.arguments as String)
					).apply()
					result.success(null)
				}
				"updateSettings" -> {
					val settings = getSharedPreferences("dock", MODE_PRIVATE).edit()
					(call.arguments as? Map<*, *>)?.forEach { (key, value) ->
						when (value) {
							is Boolean -> settings.putBoolean(key.toString(), value)
							is Int -> settings.putInt(key.toString(), value)
							is Double -> settings.putFloat(key.toString(), value.toFloat())
							is String -> settings.putString(key.toString(), value)
						}
					}
					settings.apply()
					val prefs = getSharedPreferences("dock", MODE_PRIVATE)
					if (prefs.getBoolean("overlayEnabled", false) && Settings.canDrawOverlays(this)) {
						val intent = Intent(this, DockService::class.java)
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
					}
					sendBroadcast(Intent(DockService.ACTION_SETTINGS_CHANGED))
					result.success(null)
				}
				else -> result.notImplemented()
			}
		}
	}

	private fun hasUsageAccess(): Boolean {
		val appOps = getSystemService(android.app.AppOpsManager::class.java)
		return appOps?.checkOpNoThrow(
			android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
			android.os.Process.myUid(),
			packageName,
		) == android.app.AppOpsManager.MODE_ALLOWED
	}

	private fun hasBatteryOptimizationExemption(): Boolean {
		val powerManager = getSystemService(PowerManager::class.java)
		return powerManager?.isIgnoringBatteryOptimizations(packageName) == true
	}
}
