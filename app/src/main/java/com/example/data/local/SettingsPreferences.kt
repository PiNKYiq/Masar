package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "masar_app_settings")

data class PendingPhotoCapture(
  val stopId: String,
  val targetName: String,
  val outputUriString: String,
  val outputFilePath: String
)

class SettingsPreferences(private val context: Context?) {

  val isDarkModeFlow: Flow<Boolean> = context?.settingsDataStore?.data
    ?.map { prefs -> prefs[KEY_IS_DARK_MODE] ?: false }
    ?: flowOf(false)

  val isCallerAddressBarEnabledFlow: Flow<Boolean> = context?.settingsDataStore?.data
    ?.map { prefs -> prefs[KEY_CALLER_ADDRESS_BAR_ENABLED] ?: isCallerAddressBarEnabledSync() }
    ?: flowOf(false)

  suspend fun setDarkMode(enabled: Boolean) {
    context?.settingsDataStore?.edit { prefs ->
      prefs[KEY_IS_DARK_MODE] = enabled
    }
  }

  private fun callerPrefs() = context?.getSharedPreferences(PREFS_CALLER_BAR, Context.MODE_PRIVATE)

  fun isCallerAddressBarEnabledSync(): Boolean {
    return callerPrefs()?.getBoolean(KEY_SYNC_CALLER_BAR_ENABLED, false) ?: false
  }

  fun setCallerAddressBarEnabledSync(enabled: Boolean) {
    callerPrefs()?.edit()?.putBoolean(KEY_SYNC_CALLER_BAR_ENABLED, enabled)?.commit()
  }

  suspend fun setCallerAddressBarEnabled(enabled: Boolean) {
    callerPrefs()?.edit()?.putBoolean(KEY_SYNC_CALLER_BAR_ENABLED, enabled)?.commit()
    context?.settingsDataStore?.edit { prefs ->
      prefs[KEY_CALLER_ADDRESS_BAR_ENABLED] = enabled
    }
  }

  val isBackgroundKeepAliveEnabledFlow: Flow<Boolean> = context?.settingsDataStore?.data
    ?.map { prefs -> prefs[KEY_BACKGROUND_KEEP_ALIVE_ENABLED] ?: isBackgroundKeepAliveEnabledSync() }
    ?: flowOf(true)

  fun isBackgroundKeepAliveEnabledSync(): Boolean {
    return callerPrefs()?.getBoolean(KEY_SYNC_BACKGROUND_KEEP_ALIVE_ENABLED, true) ?: true
  }

  suspend fun setBackgroundKeepAliveEnabled(enabled: Boolean) {
    callerPrefs()?.edit()?.putBoolean(KEY_SYNC_BACKGROUND_KEEP_ALIVE_ENABLED, enabled)?.commit()
    context?.settingsDataStore?.edit { prefs ->
      prefs[KEY_BACKGROUND_KEEP_ALIVE_ENABLED] = enabled
    }
  }

  fun setBackgroundKeepAliveEnabledSync(enabled: Boolean) {
    callerPrefs()?.edit()?.putBoolean(KEY_SYNC_BACKGROUND_KEEP_ALIVE_ENABLED, enabled)?.commit()
  }

  fun isFloatingQuickAddEnabledSync(): Boolean {
    return callerPrefs()?.getBoolean(KEY_SYNC_QUICK_ADD_ENABLED, false) ?: false
  }

  fun setFloatingQuickAddEnabledSync(enabled: Boolean) {
    callerPrefs()?.edit()?.putBoolean(KEY_SYNC_QUICK_ADD_ENABLED, enabled)?.commit()
  }

  fun getUpdateSourceUrlSync(): String {
    return callerPrefs()?.getString(KEY_UPDATE_SOURCE_URL, "").orEmpty()
  }

  fun setUpdateSourceUrlSync(url: String) {
    callerPrefs()?.edit()?.putString(KEY_UPDATE_SOURCE_URL, url.trim())?.commit()
  }

  fun getLastUpdateCheckTimeSync(): String {
    return callerPrefs()?.getString(KEY_LAST_UPDATE_CHECK_TIME, "").orEmpty()
  }

  fun setLastUpdateCheckTimeSync(time: String) {
    callerPrefs()?.edit()?.putString(KEY_LAST_UPDATE_CHECK_TIME, time)?.commit()
  }

  fun saveActiveCallerBar(
    phone: String,
    normalizedPhone: String,
    address: String,
    mapUrl: String,
    isSuspicious: Boolean
  ) {
    callerPrefs()?.edit()?.apply {
      putString(KEY_ACTIVE_BAR_PHONE, phone)
      putString(KEY_ACTIVE_BAR_NORM_PHONE, normalizedPhone)
      putString(KEY_ACTIVE_BAR_ADDRESS, address)
      putString(KEY_ACTIVE_BAR_MAP_URL, mapUrl)
      putBoolean(KEY_ACTIVE_BAR_SUSPICIOUS, isSuspicious)
      commit()
    }
  }

  fun getActiveCallerBar(): ActiveCallerBarState? {
    val prefs = callerPrefs() ?: return null
    val address = prefs.getString(KEY_ACTIVE_BAR_ADDRESS, null)?.takeIf { it.isNotBlank() } ?: return null
    return ActiveCallerBarState(
      phone = prefs.getString(KEY_ACTIVE_BAR_PHONE, null).orEmpty(),
      normalizedPhone = prefs.getString(KEY_ACTIVE_BAR_NORM_PHONE, null).orEmpty(),
      address = address,
      mapUrl = prefs.getString(KEY_ACTIVE_BAR_MAP_URL, null).orEmpty(),
      isSuspicious = prefs.getBoolean(KEY_ACTIVE_BAR_SUSPICIOUS, false)
    )
  }

  fun clearActiveCallerBar() {
    callerPrefs()?.edit()?.apply {
      remove(KEY_ACTIVE_BAR_PHONE)
      remove(KEY_ACTIVE_BAR_NORM_PHONE)
      remove(KEY_ACTIVE_BAR_ADDRESS)
      remove(KEY_ACTIVE_BAR_MAP_URL)
      remove(KEY_ACTIVE_BAR_SUSPICIOUS)
      commit()
    }
  }

  private fun pendingPrefs() = context?.getSharedPreferences(PREFS_PENDING_CAPTURE, Context.MODE_PRIVATE)

  fun savePendingPhotoCapture(
    stopId: String,
    targetName: String,
    outputUriString: String,
    outputFilePath: String
  ) {
    pendingPrefs()?.edit()?.apply {
      putString(KEY_PENDING_STOP_ID, stopId)
      putString(KEY_PENDING_TARGET, targetName)
      putString(KEY_PENDING_URI, outputUriString)
      putString(KEY_PENDING_PATH, outputFilePath)
      commit()
    }
  }

  fun getPendingPhotoCapture(): PendingPhotoCapture? {
    val prefs = pendingPrefs() ?: return null
    val stopId = prefs.getString(KEY_PENDING_STOP_ID, null)?.takeIf { it.isNotBlank() } ?: return null
    val targetName = prefs.getString(KEY_PENDING_TARGET, null)?.takeIf { it.isNotBlank() } ?: return null
    val uriString = prefs.getString(KEY_PENDING_URI, null)?.takeIf { it.isNotBlank() } ?: return null
    val filePath = prefs.getString(KEY_PENDING_PATH, null).orEmpty()
    return PendingPhotoCapture(
      stopId = stopId,
      targetName = targetName,
      outputUriString = uriString,
      outputFilePath = filePath
    )
  }

  fun clearPendingPhotoCapture() {
    pendingPrefs()?.edit()?.apply {
      remove(KEY_PENDING_STOP_ID)
      remove(KEY_PENDING_TARGET)
      remove(KEY_PENDING_URI)
      remove(KEY_PENDING_PATH)
      commit()
    }
  }

  fun savePendingGalleryPick(stopId: String, targetName: String) {
    pendingPrefs()?.edit()?.apply {
      putString(KEY_GALLERY_STOP_ID, stopId)
      putString(KEY_GALLERY_TARGET, targetName)
      commit()
    }
  }

  fun getPendingGalleryPick(): Pair<String, String>? {
    val prefs = pendingPrefs() ?: return null
    val stopId = prefs.getString(KEY_GALLERY_STOP_ID, null)?.takeIf { it.isNotBlank() } ?: return null
    val targetName = prefs.getString(KEY_GALLERY_TARGET, null)?.takeIf { it.isNotBlank() } ?: return null
    return stopId to targetName
  }

  fun clearPendingGalleryPick() {
    pendingPrefs()?.edit()?.apply {
      remove(KEY_GALLERY_STOP_ID)
      remove(KEY_GALLERY_TARGET)
      commit()
    }
  }

  companion object {
    private val KEY_IS_DARK_MODE = booleanPreferencesKey("is_dark_mode")
    private val KEY_CALLER_ADDRESS_BAR_ENABLED = booleanPreferencesKey("caller_address_bar_enabled")
    private val KEY_BACKGROUND_KEEP_ALIVE_ENABLED = booleanPreferencesKey("background_keep_alive_enabled")
    private const val PREFS_CALLER_BAR = "masar_caller_address_bar"
    private const val KEY_SYNC_CALLER_BAR_ENABLED = "caller_address_bar_enabled"
    private const val KEY_SYNC_BACKGROUND_KEEP_ALIVE_ENABLED = "background_keep_alive_enabled"
    private const val KEY_SYNC_QUICK_ADD_ENABLED = "floating_quick_add_enabled"
    private const val KEY_ACTIVE_BAR_PHONE = "active_bar_phone"
    private const val KEY_ACTIVE_BAR_NORM_PHONE = "active_bar_norm_phone"
    private const val KEY_ACTIVE_BAR_ADDRESS = "active_bar_address"
    private const val KEY_ACTIVE_BAR_MAP_URL = "active_bar_map_url"
    private const val KEY_ACTIVE_BAR_SUSPICIOUS = "active_bar_suspicious"
    private const val PREFS_PENDING_CAPTURE = "masar_pending_photo_capture"
    private const val KEY_PENDING_STOP_ID = "pending_stop_id"
    private const val KEY_PENDING_TARGET = "pending_target"
    private const val KEY_PENDING_URI = "pending_uri"
    private const val KEY_PENDING_PATH = "pending_path"
    private const val KEY_GALLERY_STOP_ID = "pending_gallery_stop_id"
    private const val KEY_GALLERY_TARGET = "pending_gallery_target"
    private const val KEY_UPDATE_SOURCE_URL = "app_update_source_url"
    private const val KEY_LAST_UPDATE_CHECK_TIME = "app_last_update_check_time"
  }
}

data class ActiveCallerBarState(
  val phone: String,
  val normalizedPhone: String,
  val address: String,
  val mapUrl: String,
  val isSuspicious: Boolean
)
