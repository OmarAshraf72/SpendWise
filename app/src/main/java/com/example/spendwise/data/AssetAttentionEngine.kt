package com.example.spendwise.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class AssetAttentionStatus { UPCOMING, DUE_SOON, DUE, OVERDUE }

data class AssetAttentionItem(
    val assetId: Long,
    val sourceType: AssetAttentionSource,
    val sourceId: Long,
    val title: String,
    val status: AssetAttentionStatus,
    val dueDateEpochDay: Long?,
    val dueMileageKm: Long?,
    val remainingDays: Long?,
    val remainingKm: Long?,
    val priority: Int,
    val message: String,
    val occurrenceKey: String
)

data class CheckpointOccurrence(val dueDateEpochDay: Long?, val dueMileageKm: Long?) {
    val key: String get() = "${dueDateEpochDay ?: "-"}:${dueMileageKm ?: "-"}"
}

object AssetAttentionEngine {
    fun validateCheckpoint(checkpoint: AssetCheckpointEntity) {
        require(checkpoint.title.isNotBlank())
        require(checkpoint.repeatMonths == null || checkpoint.repeatMonths > 0)
        require(checkpoint.repeatKm == null || checkpoint.repeatKm > 0)
        require(checkpoint.warningDays == null || checkpoint.warningDays >= 0)
        require(checkpoint.warningKm == null || checkpoint.warningKm >= 0)
        require(checkpoint.dueMileageKm == null || checkpoint.dueMileageKm >= 0)
        when (checkpoint.triggerMode) {
            CheckpointTriggerMode.DATE -> require(checkpoint.dueDateEpochDay != null && checkpoint.dueMileageKm == null && checkpoint.repeatKm == null)
            CheckpointTriggerMode.MILEAGE -> require(checkpoint.dueMileageKm != null && checkpoint.dueDateEpochDay == null && checkpoint.repeatMonths == null)
            CheckpointTriggerMode.DATE_OR_MILEAGE -> require(checkpoint.dueDateEpochDay != null && checkpoint.dueMileageKm != null)
        }
        require(checkpoint.repeatMonths == null || checkpoint.dueDateEpochDay != null)
        require(checkpoint.repeatKm == null || checkpoint.dueMileageKm != null)
    }

    fun checkpointOccurrence(checkpoint: AssetCheckpointEntity, latestEvent: AssetCheckpointEventEntity?): CheckpointOccurrence {
        val derived = if (latestEvent == null) CheckpointOccurrence(checkpoint.dueDateEpochDay, checkpoint.dueMileageKm) else CheckpointOccurrence(
            checkpoint.repeatMonths?.let { LocalDate.ofEpochDay(latestEvent.completedDateEpochDay).plusMonths(it.toLong()).toEpochDay() },
            checkpoint.repeatKm?.let { interval -> latestEvent.completedMileageKm?.plus(interval) }
        )
        return CheckpointOccurrence(checkpoint.rescheduledDueDateEpochDay ?: derived.dueDateEpochDay,
            checkpoint.rescheduledDueMileageKm ?: derived.dueMileageKm)
    }

    fun evaluate(data: AssetData, today: LocalDate, onlyAssetId: Long? = null): List<AssetAttentionItem> {
        val output = mutableListOf<AssetAttentionItem>()
        data.assets.asSequence().filter { !it.isArchived && (onlyAssetId == null || it.id == onlyAssetId) }.forEach { asset ->
            data.warranties.asSequence().filter { it.assetId == asset.id }.forEach { warranty ->
                val due = warranty.endDateEpochDay
                output += item(asset.id, AssetAttentionSource.WARRANTY, warranty.id, warranty.name,
                    due, null, today, asset.currentMileageKm, 30, null)
            }
            data.rules.asSequence().filter { it.assetId == asset.id && it.isActive }.forEach { rule ->
                val latest = data.events.filter { it.maintenanceRuleId == rule.id }
                    .maxWithOrNull(compareBy<AssetMaintenanceEventEntity> { it.performedDateEpochDay }.thenBy { it.createdAt })
                val due = MaintenanceDueCalculator.calculate(rule, today, asset.currentMileageKm, latest)
                if (due.nextDueDate != null || due.nextDueMileageKm != null) {
                    output += item(asset.id, AssetAttentionSource.MAINTENANCE, rule.id, rule.title,
                        due.nextDueDate?.toEpochDay(), due.nextDueMileageKm, today, asset.currentMileageKm,
                        rule.warningDays, rule.warningKm)
                }
            }
            data.checkpoints.asSequence().filter { it.assetId == asset.id && it.isActive }.forEach { checkpoint ->
                val latest = data.checkpointEvents.filter { it.checkpointId == checkpoint.id }
                    .maxWithOrNull(compareBy<AssetCheckpointEventEntity> { it.completedDateEpochDay }.thenBy { it.createdAt })
                val due = checkpointOccurrence(checkpoint, latest)
                if (due.dueDateEpochDay != null || due.dueMileageKm != null) {
                    output += item(asset.id, AssetAttentionSource.CHECKPOINT, checkpoint.id, checkpoint.title,
                        due.dueDateEpochDay, due.dueMileageKm, today, asset.currentMileageKm,
                        checkpoint.warningDays, checkpoint.warningKm)
                }
            }
        }
        return output.sortedWith(compareByDescending<AssetAttentionItem> { it.priority }
            .thenBy { item -> listOfNotNull(item.remainingDays, item.remainingKm).minOfOrNull { abs(it) } ?: Long.MAX_VALUE }
            .thenBy { it.assetId }.thenBy { it.sourceId })
    }

    private fun item(
        assetId: Long, source: AssetAttentionSource, sourceId: Long, title: String,
        dueDate: Long?, dueKm: Long?, today: LocalDate, currentKm: Long?, warningDays: Int?, warningKm: Long?
    ): AssetAttentionItem {
        val days = dueDate?.let { it - today.toEpochDay() }
        val km = if (dueKm != null && currentKm != null) dueKm - currentKm else null
        fun status(remaining: Long?, warning: Long?): AssetAttentionStatus? = when {
            remaining == null -> null
            remaining < 0 -> AssetAttentionStatus.OVERDUE
            remaining == 0L -> AssetAttentionStatus.DUE
            warning != null && remaining <= warning -> AssetAttentionStatus.DUE_SOON
            else -> AssetAttentionStatus.UPCOMING
        }
        val dateStatus = status(days, warningDays?.toLong())
        val kmStatus = status(km, warningKm)
        val selected = listOfNotNull(dateStatus, kmStatus).maxByOrNull(::statusPriority) ?: AssetAttentionStatus.UPCOMING
        val chosenRemaining = when {
            kmStatus != null && statusPriority(kmStatus) > statusPriority(dateStatus ?: AssetAttentionStatus.UPCOMING) -> km to "km"
            days != null -> days to "days"
            else -> km to "km"
        }
        val remaining = chosenRemaining.first
        val message = when {
            remaining == null -> "Due point set; update mileage to check progress"
            source == AssetAttentionSource.WARRANTY && remaining < 0 -> "$title expired ${abs(remaining)} days ago"
            source == AssetAttentionSource.WARRANTY && remaining == 0L -> "$title expires today"
            source == AssetAttentionSource.WARRANTY -> "$title expires in $remaining days"
            remaining < 0 -> "$title overdue by ${abs(remaining)} ${chosenRemaining.second}"
            remaining == 0L -> "$title due now"
            else -> "$title due in $remaining ${chosenRemaining.second}"
        }
        return AssetAttentionItem(assetId, source, sourceId, title, selected, dueDate, dueKm, days, km,
            statusPriority(selected), message, "${dueDate ?: "-"}:${dueKm ?: "-"}")
    }

    private fun statusPriority(status: AssetAttentionStatus) = when (status) {
        AssetAttentionStatus.OVERDUE -> 4
        AssetAttentionStatus.DUE -> 3
        AssetAttentionStatus.DUE_SOON -> 2
        AssetAttentionStatus.UPCOMING -> 1
    }
}
