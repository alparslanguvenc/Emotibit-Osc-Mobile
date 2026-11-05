package com.rotalab.emotibit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.rotalab.emotibit.BuildConfig
import androidx.core.os.LocaleListCompat
import com.rotalab.emotibit.R
import com.rotalab.emotibit.app.ui.MainScreen
import com.rotalab.emotibit.app.ui.RotaLabTheme

class MainActivity : AppCompatActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        EmotiBitService.start(this)
        setContent {
            RotaLabTheme {
                MainScreen(
                    actions = object : MainActions {
                        override fun setLanguage(tag: String?) {
                            AppCompatDelegate.setApplicationLocales(
                                if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
                            )
                        }
                        override fun currentLanguage(): String? = AppCompatDelegate.getApplicationLocales().toLanguageTags().takeIf { it.isNotEmpty() }
                        override fun quit() {
                            EmotiBitService.stop(this@MainActivity)
                            finishAndRemoveTask()
                        }
                        override fun shareLog() = this@MainActivity.shareLog()
                        override fun isIgnoringBatteryOptimizations(): Boolean =
                            getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
                        override fun requestIgnoreBatteryOptimizations() {
                            // Opens the system list instead of the direct request, which needs a
                            // sensitive permission that app scanners flag.
                            val intents = listOf(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()),
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                            for (i in intents) {
                                try { startActivity(i); return } catch (_: Exception) {}
                            }
                        }
                        override fun versionName(): String = BuildConfig.VERSION_NAME
                    },
                )
            }
        }
    }

    private fun shareLog() {
        val file = HostController.log.currentFile()
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.log_share)))
    }
}

interface MainActions {
    fun setLanguage(tag: String?)
    fun currentLanguage(): String?
    fun quit()
    fun shareLog()
    fun isIgnoringBatteryOptimizations(): Boolean
    fun requestIgnoreBatteryOptimizations()
    fun versionName(): String = ""
}
