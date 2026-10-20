package com.myexpenses.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class IngestionSettings(
    val onboardingComplete: Boolean = false,
    val gmailConnected: Boolean = false,
    val gmailAccount: String? = null,
    val gmailQuery: String = DEFAULT_GMAIL_QUERY,
    val smsLastScan: Long? = null,
    val gmailLastSync: Long? = null,
    val lastStatus: String? = null,
) {
    companion object {
        const val DEFAULT_GMAIL_QUERY =
            "newer_than:1y {subject:debited subject:credited subject:transaction " +
                "subject:purchase subject:payment subject:refund}"
    }
}

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<IngestionSettings> = mutableSettings.asStateFlow()

    fun completeOnboarding() = update { copy(onboardingComplete = true) }

    fun setGmailConnection(connected: Boolean, account: String? = null) = update {
        copy(
            gmailConnected = connected,
            gmailAccount = if (connected) account ?: gmailAccount else null,
            lastStatus = if (connected) "Gmail connected" else "Gmail disconnected",
        )
    }

    fun setGmailQuery(query: String) = update {
        copy(gmailQuery = query.trim().ifEmpty { IngestionSettings.DEFAULT_GMAIL_QUERY })
    }

    fun recordSmsScan(time: Long = System.currentTimeMillis(), status: String) = update {
        copy(smsLastScan = time, lastStatus = status)
    }

    fun recordGmailSync(time: Long = System.currentTimeMillis(), status: String) = update {
        copy(gmailLastSync = time, lastStatus = status)
    }

    fun recordStatus(status: String) = update { copy(lastStatus = status) }

    private fun update(transform: IngestionSettings.() -> IngestionSettings) {
        val value = mutableSettings.value.transform()
        preferences.edit()
            .putBoolean(ONBOARDING, value.onboardingComplete)
            .putBoolean(GMAIL_CONNECTED, value.gmailConnected)
            .putString(GMAIL_ACCOUNT, value.gmailAccount)
            .putString(GMAIL_QUERY, value.gmailQuery)
            .putLong(SMS_LAST_SCAN, value.smsLastScan ?: 0L)
            .putLong(GMAIL_LAST_SYNC, value.gmailLastSync ?: 0L)
            .putString(LAST_STATUS, value.lastStatus)
            .apply()
        mutableSettings.value = value
    }

    private fun read() = IngestionSettings(
        onboardingComplete = preferences.getBoolean(ONBOARDING, false),
        gmailConnected = preferences.getBoolean(GMAIL_CONNECTED, false),
        gmailAccount = preferences.getString(GMAIL_ACCOUNT, null),
        gmailQuery = preferences.getString(
            GMAIL_QUERY,
            IngestionSettings.DEFAULT_GMAIL_QUERY,
        ) ?: IngestionSettings.DEFAULT_GMAIL_QUERY,
        smsLastScan = preferences.getLong(SMS_LAST_SCAN, 0L).takeIf { it > 0L },
        gmailLastSync = preferences.getLong(GMAIL_LAST_SYNC, 0L).takeIf { it > 0L },
        lastStatus = preferences.getString(LAST_STATUS, null),
    )

    private companion object {
        const val FILE_NAME = "app_preferences"
        const val ONBOARDING = "onboarding_complete"
        const val GMAIL_CONNECTED = "gmail_connected"
        const val GMAIL_ACCOUNT = "gmail_account"
        const val GMAIL_QUERY = "gmail_query"
        const val SMS_LAST_SCAN = "sms_last_scan"
        const val GMAIL_LAST_SYNC = "gmail_last_sync"
        const val LAST_STATUS = "last_status"
    }
}
