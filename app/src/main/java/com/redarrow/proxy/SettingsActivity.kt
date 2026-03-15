package com.redarrow.proxy

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.redarrow.proxy.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("red_arrow_settings", Context.MODE_PRIVATE)

        // Back button
        binding.btnBack.setOnClickListener { finish() }

        // Version
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            binding.tvVersion.text = pInfo.versionName
        } catch (_: Exception) {
            binding.tvVersion.text = "unknown"
        }

        // --- Theme ---
        val savedTheme = prefs.getString("theme", "system") ?: "system"
        when (savedTheme) {
            "light" -> binding.btnThemeLight.isChecked = true
            "dark" -> binding.btnThemeDark.isChecked = true
            else -> binding.btnThemeSystem.isChecked = true
        }

        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeLight -> "light"
                R.id.btnThemeDark -> "dark"
                else -> "system"
            }
            prefs.edit().putString("theme", mode).apply()
            when (mode) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        // --- Language ---
        val savedLang = prefs.getString("language", "system") ?: "system"
        when (savedLang) {
            "zh" -> binding.btnLangZh.isChecked = true
            "en" -> binding.btnLangEn.isChecked = true
            else -> binding.btnLangSystem.isChecked = true
        }

        binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val lang = when (checkedId) {
                R.id.btnLangZh -> "zh"
                R.id.btnLangEn -> "en"
                else -> "system"
            }
            prefs.edit().putString("language", lang).apply()
            val locales = if (lang == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(lang)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
