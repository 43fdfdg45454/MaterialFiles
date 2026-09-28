/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.zhanghai.android.files.R
import me.zhanghai.android.files.nfs.NfsDiagnosticsActivity
import me.zhanghai.android.files.provider.nfs.client.NfsReadCache
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.ui.PreferenceFragmentCompat

class SettingsPreferenceFragment : PreferenceFragmentCompat() {
    private lateinit var localePreference: LocalePreference
    private lateinit var clearNfsReadCachePreference: Preference

    override fun onCreatePreferencesFix(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings)

        localePreference = preferenceScreen.findPreference(getString(R.string.pref_key_locale))!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            localePreference.setApplicationLocalesPre33 = { locales ->
                val activity = requireActivity() as SettingsActivity
                activity.setApplicationLocalesPre33(locales)
            }
        }

        preferenceScreen.findPreference<Preference>(
            getString(R.string.pref_key_nfs_read_cache_size_gb)
        )!!.setOnPreferenceChangeListener { _, _ ->
            // Stored after this returns; applied (and the usage shown) right after.
            lifecycleScope.launch {
                // After the new value is stored.
                yield()
                withContext(Dispatchers.IO) { NfsReadCache.trim() }
                updateNfsReadCacheUsage()
            }
            true
        }
        clearNfsReadCachePreference = preferenceScreen.findPreference(
            getString(R.string.pref_key_nfs_clear_read_cache)
        )!!
        preferenceScreen.findPreference<Preference>(getString(R.string.pref_key_nfs_diagnostics))!!
            .setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), NfsDiagnosticsActivity::class.java))
                true
            }
        clearNfsReadCachePreference.setOnPreferenceClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { NfsReadCache.clear() }
                updateNfsReadCacheUsage()
            }
            true
        }
    }

    private fun updateNfsReadCacheUsage() {
        lifecycleScope.launch {
            val size = withContext(Dispatchers.IO) { NfsReadCache.totalSize() }
            val context = context ?: return@launch
            clearNfsReadCachePreference.summary = getString(
                R.string.settings_nfs_clear_read_cache_summary,
                Formatter.formatFileSize(context, size)
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val viewLifecycleOwner = viewLifecycleOwner
        // The following may end up passing the same lambda instance to the observer because it has
        // no capture, and result in an IllegalArgumentException "Cannot add the same observer with
        // different lifecycles" if activity is finished and instantly started again. To work around
        // this, always use an instance method reference.
        // https://stackoverflow.com/a/27524543
        //Settings.THEME_COLOR.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        //Settings.NIGHT_MODE.observe(viewLifecycleOwner) { NightModeHelper.sync() }
        //Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner) { CustomThemeHelper.sync() }
        Settings.THEME_COLOR.observe(viewLifecycleOwner, this::onThemeColorChanged)
        Settings.MATERIAL_DESIGN_3.observe(viewLifecycleOwner, this::onMaterialDesign3Changed)
        Settings.NIGHT_MODE.observe(viewLifecycleOwner, this::onNightModeChanged)
        Settings.BLACK_NIGHT_MODE.observe(viewLifecycleOwner, this::onBlackNightModeChanged)
    }

    private fun onThemeColorChanged(themeColor: ThemeColor) {
        CustomThemeHelper.sync()
    }

    private fun onMaterialDesign3Changed(isMaterialDesign3: Boolean) {
        CustomThemeHelper.sync()
    }

    private fun onNightModeChanged(nightMode: NightMode) {
        NightModeHelper.sync()
    }

    private fun onBlackNightModeChanged(blackNightMode: Boolean) {
        CustomThemeHelper.sync()
    }

    override fun onResume() {
        super.onResume()

        updateNfsReadCacheUsage()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Refresh locale preference summary because we aren't notified for an external change
            // between system default and the locale that's the current system default.
            localePreference.notifyChanged()
        }
    }
}
