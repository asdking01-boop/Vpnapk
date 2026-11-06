package com.example.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class SubscriptionStatus(
    val labelEn: String,
    val labelAr: String
) {
    ACTIVE("Active", "نشط"),
    EXPIRING_SOON("Expiring Soon", "ينتهي قريباً"),
    EXPIRED("Expired", "منتهي")
}

data class SubscriptionInfo(
    val status: SubscriptionStatus,
    val activationTimestampMs: Long?,
    val expirationTimestampMs: Long?,
    val formattedActivationDateEn: String,
    val formattedActivationDateAr: String,
    val formattedExpirationDateEn: String,
    val formattedExpirationDateAr: String,
    val remainingDays: Int?,
    val countdownDays: Int,
    val countdownHours: Int,
    val countdownMinutes: Int,
    val formattedRemainingDaysEn: String,
    val formattedRemainingDaysAr: String,
    val formattedCountdownEn: String,
    val formattedCountdownAr: String,
    val isExpiringWarningVisible: Boolean,
    val warningMessageEn: String?,
    val warningMessageAr: String?
) {
    val hasActivationDate: Boolean
        get() = activationTimestampMs != null

    val hasExpirationDate: Boolean
        get() = expirationTimestampMs != null

    val isExpired: Boolean
        get() = status == SubscriptionStatus.EXPIRED

    fun statusLabel(isArabic: Boolean): String =
        if (isArabic) status.labelAr else status.labelEn

    fun activationDateText(isArabic: Boolean): String =
        if (isArabic) formattedActivationDateAr else formattedActivationDateEn

    fun expirationDateText(isArabic: Boolean): String =
        if (isArabic) formattedExpirationDateAr else formattedExpirationDateEn

    fun remainingDaysText(isArabic: Boolean): String =
        if (isArabic) formattedRemainingDaysAr else formattedRemainingDaysEn

    fun countdownText(isArabic: Boolean): String =
        if (isArabic) formattedCountdownAr else formattedCountdownEn

    fun warningText(isArabic: Boolean): String? =
        if (isArabic) warningMessageAr else warningMessageEn
}

object SubscriptionInfoHelper {

    const val NOT_AVAILABLE_EN = "Not Available"
    const val NOT_AVAILABLE_AR = "غير متوفر"
    const val WARNING_THRESHOLD_DAYS = 7
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 60L * 60L * 1000L
    private const val MINUTE_MS = 60L * 1000L

    /**
     * Parses a provider-supplied date or timestamp (e.g., Xtream Codes `user_info.exp_date` or `user_info.created_at`)
     * into epoch milliseconds.
     *
     * Never fabricates a date: returns `null` whenever the value is missing, blank, `"null"`, `"0"`,
     * `"unlimited"`, `"never"`, or unparseable.
     */
    fun parseProviderDateMs(raw: String?): Long? {
        val cleaned = raw?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        val lower = cleaned.lowercase(Locale.US)
        if (
            lower == "0" ||
            lower == "null" ||
            lower == "none" ||
            lower == "never" ||
            lower == "unlimited" ||
            lower == "n/a" ||
            lower == "not available"
        ) {
            return null
        }

        val numeric = cleaned.toLongOrNull()
        if (numeric != null) {
            if (numeric <= 0L) return null
            return if (numeric < 100_000_000_000L) {
                numeric * 1000L // Unix timestamp in seconds -> milliseconds
            } else {
                numeric // Already epoch milliseconds
            }
        }

        val patterns = arrayOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "MM/dd/yyyy"
        )
        for (pattern in patterns) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdf.parse(cleaned)?.time
                if (parsed != null && parsed > 0L) {
                    return parsed
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun formatDate(timestampMs: Long?, isArabic: Boolean): String {
        if (timestampMs == null || timestampMs <= 0L) {
            return if (isArabic) NOT_AVAILABLE_AR else NOT_AVAILABLE_EN
        }
        val locale = if (isArabic) Locale("ar") else Locale.US
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", locale).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.format(Date(timestampMs))
        } catch (_: Exception) {
            if (isArabic) NOT_AVAILABLE_AR else NOT_AVAILABLE_EN
        }
    }

    fun isArabicLanguage(languageSetting: String?): Boolean {
        val cleaned = languageSetting?.trim().orEmpty()
        return cleaned == "العربية" ||
            cleaned.equals("Arabic", ignoreCase = true) ||
            cleaned.startsWith("ar", ignoreCase = true)
    }

    /**
     * Calculates complete subscription status, activation date, expiration date, remaining days,
     * live countdown (days, hours, minutes), and 7-day expiration warning.
     *
     * Never fabricates dates: if the provider does not supply `exp_date` or `created_at` (or if the
     * account is the local demo placeholder without provider dates), displays "Not Available" / "غير متوفر".
     */
    fun calculate(
        account: Account?,
        nowMs: Long = System.currentTimeMillis()
    ): SubscriptionInfo {
        if (account == null) {
            return SubscriptionInfo(
                status = SubscriptionStatus.ACTIVE,
                activationTimestampMs = null,
                expirationTimestampMs = null,
                formattedActivationDateEn = NOT_AVAILABLE_EN,
                formattedActivationDateAr = NOT_AVAILABLE_AR,
                formattedExpirationDateEn = NOT_AVAILABLE_EN,
                formattedExpirationDateAr = NOT_AVAILABLE_AR,
                remainingDays = null,
                countdownDays = 0,
                countdownHours = 0,
                countdownMinutes = 0,
                formattedRemainingDaysEn = NOT_AVAILABLE_EN,
                formattedRemainingDaysAr = NOT_AVAILABLE_AR,
                formattedCountdownEn = NOT_AVAILABLE_EN,
                formattedCountdownAr = NOT_AVAILABLE_AR,
                isExpiringWarningVisible = false,
                warningMessageEn = null,
                warningMessageAr = null
            )
        }

        val activationMs = if (account.isDemo) null else parseProviderDateMs(account.activationDate)
        val expirationMs = if (account.isDemo) null else parseProviderDateMs(account.expDate)

        val formattedActivationEn = formatDate(activationMs, isArabic = false)
        val formattedActivationAr = formatDate(activationMs, isArabic = true)
        val formattedExpirationEn = formatDate(expirationMs, isArabic = false)
        val formattedExpirationAr = formatDate(expirationMs, isArabic = true)

        val rawStatus = account.status.trim()
        val isStatusExplicitlyExpired = rawStatus.equals("Expired", ignoreCase = true) ||
            rawStatus.equals("Disabled", ignoreCase = true) ||
            rawStatus.equals("Banned", ignoreCase = true)

        if (expirationMs == null) {
            val resolvedStatus = if (isStatusExplicitlyExpired) {
                SubscriptionStatus.EXPIRED
            } else {
                SubscriptionStatus.ACTIVE
            }
            val expiredMsgEn = if (resolvedStatus == SubscriptionStatus.EXPIRED) {
                "Your subscription has expired. Please renew your subscription with your provider."
            } else null
            val expiredMsgAr = if (resolvedStatus == SubscriptionStatus.EXPIRED) {
                "لقد انتهت صلاحية اشتراكك. يرجى تجديد الاشتراك مع المزود الخاص بك."
            } else null

            return SubscriptionInfo(
                status = resolvedStatus,
                activationTimestampMs = activationMs,
                expirationTimestampMs = null,
                formattedActivationDateEn = formattedActivationEn,
                formattedActivationDateAr = formattedActivationAr,
                formattedExpirationDateEn = NOT_AVAILABLE_EN,
                formattedExpirationDateAr = NOT_AVAILABLE_AR,
                remainingDays = null,
                countdownDays = 0,
                countdownHours = 0,
                countdownMinutes = 0,
                formattedRemainingDaysEn = NOT_AVAILABLE_EN,
                formattedRemainingDaysAr = NOT_AVAILABLE_AR,
                formattedCountdownEn = NOT_AVAILABLE_EN,
                formattedCountdownAr = NOT_AVAILABLE_AR,
                isExpiringWarningVisible = false,
                warningMessageEn = expiredMsgEn,
                warningMessageAr = expiredMsgAr
            )
        }

        val diffMs = expirationMs - nowMs
        if (diffMs <= 0L || isStatusExplicitlyExpired) {
            return SubscriptionInfo(
                status = SubscriptionStatus.EXPIRED,
                activationTimestampMs = activationMs,
                expirationTimestampMs = expirationMs,
                formattedActivationDateEn = formattedActivationEn,
                formattedActivationDateAr = formattedActivationAr,
                formattedExpirationDateEn = formattedExpirationEn,
                formattedExpirationDateAr = formattedExpirationAr,
                remainingDays = 0,
                countdownDays = 0,
                countdownHours = 0,
                countdownMinutes = 0,
                formattedRemainingDaysEn = "0 Days",
                formattedRemainingDaysAr = "0 يوم",
                formattedCountdownEn = "0d 00h 00m",
                formattedCountdownAr = "0 يوم • 00 ساعة • 00 دقيقة",
                isExpiringWarningVisible = false,
                warningMessageEn = "Your subscription has expired. Please renew your subscription with your provider.",
                warningMessageAr = "لقد انتهت صلاحية اشتراكك. يرجى تجديد الاشتراك مع المزود الخاص بك."
            )
        }

        val cDays = (diffMs / DAY_MS).toInt()
        val cHours = ((diffMs % DAY_MS) / HOUR_MS).toInt()
        val cMinutes = ((diffMs % HOUR_MS) / MINUTE_MS).toInt()

        // Remaining days: if less than 24 hours remain, 0 full days (or 1 day if exact ceiling is desired,
        // here cDays matches the countdown days unit directly, while warning triggers when diffMs <= 7 days)
        val isExpiringSoon = diffMs <= WARNING_THRESHOLD_DAYS * DAY_MS
        val status = if (isExpiringSoon) {
            SubscriptionStatus.EXPIRING_SOON
        } else {
            SubscriptionStatus.ACTIVE
        }

        val remainingDaysLabelEn = when (cDays) {
            0 -> "Less than 1 Day (${cHours}h ${cMinutes}m)"
            1 -> "1 Day"
            else -> "$cDays Days"
        }
        val remainingDaysLabelAr = when (cDays) {
            0 -> "أقل من يوم (${cHours} ساعة و ${cMinutes} دقيقة)"
            1 -> "يوم واحد (1)"
            2 -> "يومان (2)"
            in 3..10 -> "$cDays أيام"
            else -> "$cDays يوماً"
        }

        val countdownEn = String.format(Locale.US, "%dd %02dh %02dm", cDays, cHours, cMinutes)
        val countdownAr = String.format(Locale.US, "%d يوم • %02d ساعة • %02d دقيقة", cDays, cHours, cMinutes)

        val warningEn = if (isExpiringSoon) {
            if (cDays == 0) {
                "Warning: Your subscription expires in less than 24 hours (${cHours}h ${cMinutes}m remaining). Renew soon to avoid interruption."
            } else if (cDays == 1) {
                "Warning: Your subscription has 1 day remaining. Please renew soon to avoid interruption."
            } else {
                "Warning: Your subscription has $cDays days remaining. Please renew soon to avoid interruption."
            }
        } else null

        val warningAr = if (isExpiringSoon) {
            if (cDays == 0) {
                "تنبيه: ينتهي اشتراكك خلال أقل من 24 ساعة (${cHours} ساعة و ${cMinutes} دقيقة متبقية). يرجى التجديد قريباً."
            } else if (cDays == 1) {
                "تنبيه: متبقي يوم واحد فقط على انتهاء اشتراكك. يرجى التجديد قريباً لتجنب انقطاع الخدمة."
            } else {
                "تنبيه: متبقي $cDays أيام فقط على انتهاء اشتراكك. يرجى التجديد قريباً لتجنب انقطاع الخدمة."
            }
        } else null

        return SubscriptionInfo(
            status = status,
            activationTimestampMs = activationMs,
            expirationTimestampMs = expirationMs,
            formattedActivationDateEn = formattedActivationEn,
            formattedActivationDateAr = formattedActivationAr,
            formattedExpirationDateEn = formattedExpirationEn,
            formattedExpirationDateAr = formattedExpirationAr,
            remainingDays = cDays,
            countdownDays = cDays,
            countdownHours = cHours,
            countdownMinutes = cMinutes,
            formattedRemainingDaysEn = remainingDaysLabelEn,
            formattedRemainingDaysAr = remainingDaysLabelAr,
            formattedCountdownEn = countdownEn,
            formattedCountdownAr = countdownAr,
            isExpiringWarningVisible = isExpiringSoon,
            warningMessageEn = warningEn,
            warningMessageAr = warningAr
        )
    }
}
