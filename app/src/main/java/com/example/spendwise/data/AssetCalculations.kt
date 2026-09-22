package com.example.spendwise.data

import java.time.LocalDate
import java.time.Period
import java.time.temporal.ChronoUnit

enum class WarrantyState { ACTIVE, EXPIRING_SOON, EXPIRED }
data class WarrantyStatus(val state: WarrantyState, val daysUntilExpiry: Long)

object WarrantyCalculator {
    fun expiryFromDuration(start: LocalDate, years: Int = 0, months: Int = 0): LocalDate =
        start.plusYears(years.toLong()).plusMonths(months.toLong())

    fun status(endDate: LocalDate, today: LocalDate, expiringSoonDays: Int = 30): WarrantyStatus {
        val remaining = ChronoUnit.DAYS.between(today, endDate)
        val state = when {
            remaining < 0 -> WarrantyState.EXPIRED
            remaining <= expiringSoonDays -> WarrantyState.EXPIRING_SOON
            else -> WarrantyState.ACTIVE
        }
        return WarrantyStatus(state, remaining)
    }

    fun remainingLabel(endDate: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(today, endDate)
        if (days < 0) return "Warranty expired ${-days} days ago"
        if (days <= 30) return "Warranty expires in $days days"
        val p = Period.between(today, endDate)
        return "${p.years} years ${p.months} months remaining".trim()
    }
}

enum class MaintenanceDueStatus { OK, DUE_SOON, DUE, OVERDUE }
enum class MaintenanceTriggerReason { TIME, MILEAGE, BOTH, UNKNOWN }
data class MaintenanceDueResult(
    val nextDueDate: LocalDate?,
    val nextDueMileageKm: Long?,
    val remainingDays: Long?,
    val remainingKm: Long?,
    val status: MaintenanceDueStatus,
    val triggerReason: MaintenanceTriggerReason
)

object MaintenanceDueCalculator {
    fun calculate(
        rule: AssetMaintenanceRuleEntity,
        today: LocalDate,
        currentMileageKm: Long?,
        latestEvent: AssetMaintenanceEventEntity?
    ): MaintenanceDueResult {
        val baseDate = latestEvent?.performedDateEpochDay?.let(LocalDate::ofEpochDay)
            ?: rule.baselineDateEpochDay?.let(LocalDate::ofEpochDay)
        val baseMileage = latestEvent?.mileageKm ?: rule.baselineMileageKm
        val nextDate = if (rule.triggerType != MaintenanceTriggerType.MILEAGE) {
            baseDate?.let { date -> rule.intervalMonths?.let { date.plusMonths(it.toLong()) } }
        } else null
        val nextMileage = if (rule.triggerType != MaintenanceTriggerType.TIME) {
            baseMileage?.let { mileage -> rule.intervalKm?.let { mileage + it } }
        } else null
        val remainingDays = nextDate?.let { ChronoUnit.DAYS.between(today, it) }
        val remainingKm = if (nextMileage != null && currentMileageKm != null) nextMileage - currentMileageKm else null
        val timeStatus = componentStatus(remainingDays, rule.warningDays.toLong())
        val mileageStatus = componentStatus(remainingKm, rule.warningKm)
        val relevant = when (rule.triggerType) {
            MaintenanceTriggerType.TIME -> listOfNotNull(timeStatus)
            MaintenanceTriggerType.MILEAGE -> listOfNotNull(mileageStatus)
            MaintenanceTriggerType.TIME_OR_MILEAGE -> listOfNotNull(timeStatus, mileageStatus)
        }
        val status = relevant.maxByOrNull(MaintenanceDueStatus::ordinal) ?: MaintenanceDueStatus.OK
        val timeOrdinal = timeStatus?.ordinal ?: -1
        val mileageOrdinal = mileageStatus?.ordinal ?: -1
        val reason = when {
            timeOrdinal < 0 && mileageOrdinal < 0 -> MaintenanceTriggerReason.UNKNOWN
            timeOrdinal == mileageOrdinal && timeOrdinal >= MaintenanceDueStatus.DUE_SOON.ordinal -> MaintenanceTriggerReason.BOTH
            timeOrdinal >= mileageOrdinal -> MaintenanceTriggerReason.TIME
            else -> MaintenanceTriggerReason.MILEAGE
        }
        return MaintenanceDueResult(nextDate, nextMileage, remainingDays, remainingKm, status, reason)
    }

    private fun componentStatus(remaining: Long?, warning: Long): MaintenanceDueStatus? = when {
        remaining == null -> null
        remaining < 0 -> MaintenanceDueStatus.OVERDUE
        remaining == 0L -> MaintenanceDueStatus.DUE
        remaining <= warning -> MaintenanceDueStatus.DUE_SOON
        else -> MaintenanceDueStatus.OK
    }
}

data class AssetMaintenanceWritePlan(val existingEventId: Long?, val createEvent: Boolean, val createTransaction: Boolean)

fun planAssetMaintenanceWrite(existing: AssetMaintenanceEventEntity?, requestedExpense: Boolean): AssetMaintenanceWritePlan =
    if (existing != null) AssetMaintenanceWritePlan(existing.id, false, false)
    else AssetMaintenanceWritePlan(null, true, requestedExpense)

fun isSafeAssetDocumentPath(relativePath: String): Boolean =
    relativePath.replace('\\', '/').startsWith("assets/documents/") && ".." !in relativePath
