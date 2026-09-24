package com.example.spendwise.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssetCheckpointPersistenceInstrumentedTest {
    @Test fun recurringCheckpointCompletionPersistsAndAdvancesWithoutChangingHistory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, SpendWiseDatabase::class.java).build()
        try { withContext(Dispatchers.IO) {
            val repository = AssetRepository(database)
            val assetId = repository.create(NewAssetInput(AssetEntity(name = "Car", type = AssetType.VEHICLE,
                brand = null, model = null, purchaseDateEpochDay = null, purchasePriceMinor = null,
                sellerMerchantId = null, currentMileageKm = 90_000, notes = null, createdAt = 1, updatedAt = 1)))
            val due = LocalDate.of(2026, 10, 1)
            val checkpointId = repository.saveCheckpoint(AssetCheckpointEntity(assetId = assetId, title = "License",
                checkpointType = AssetCheckpointType.RENEWAL, triggerMode = CheckpointTriggerMode.DATE_OR_MILEAGE,
                dueDateEpochDay = due.toEpochDay(), dueMileageKm = 95_000, repeatMonths = 12, repeatKm = 10_000,
                warningDays = 30, warningKm = 1000, notes = null, createdAt = 1, updatedAt = 1))
            repository.completeCheckpoint(checkpointId, due.minusDays(2), 95_500, "Renewed")
            val data = repository.data.first()
            val original = data.checkpoints.single { it.id == checkpointId }
            val event = data.checkpointEvents.single()
            assertEquals(due.toEpochDay(), original.dueDateEpochDay)
            assertEquals(due.toEpochDay(), event.dueDateEpochDay)
            assertEquals(95_000L, event.dueMileageKm)
            assertEquals("Renewed", event.note)
            assertEquals(due.minusDays(2).plusMonths(12).toEpochDay(), AssetAttentionEngine.checkpointOccurrence(original, event).dueDateEpochDay)
            assertEquals(105_500L, AssetAttentionEngine.checkpointOccurrence(original, event).dueMileageKm)
            assertEquals(1, database.assetDao().observeCheckpointEvents().first().size)
            val rescheduledDate = due.plusMonths(14).toEpochDay()
            repository.saveCheckpoint(original.copy(dueDateEpochDay = rescheduledDate, dueMileageKm = 107_000, updatedAt = 3))
            val rescheduled = requireNotNull(database.assetDao().getCheckpoint(checkpointId))
            assertEquals(due.toEpochDay(), rescheduled.dueDateEpochDay)
            assertEquals(rescheduledDate, AssetAttentionEngine.checkpointOccurrence(rescheduled, event).dueDateEpochDay)
            assertEquals(107_000L, AssetAttentionEngine.checkpointOccurrence(rescheduled, event).dueMileageKm)
            assertEquals(due.toEpochDay(), database.assetDao().observeCheckpointEvents().first().single().dueDateEpochDay)

            val oneTimeId = repository.saveCheckpoint(original.copy(id = 0, title = "One time", triggerMode = CheckpointTriggerMode.DATE,
                dueMileageKm = null, repeatMonths = null, repeatKm = null))
            repository.completeCheckpoint(oneTimeId, due, null, null)
            assertFalse(database.assetDao().getCheckpoint(oneTimeId)!!.isActive)
            assertEquals(2, database.assetDao().observeCheckpointEvents().first().size)

            val reminderDao = database.assetReminderDao()
            val ruleId = reminderDao.insertRule(AssetReminderRuleEntity(assetId = assetId,
                sourceType = AssetAttentionSource.CHECKPOINT, sourceId = checkpointId,
                triggerKind = AssetReminderTriggerKind.DAYS_BEFORE, leadValue = 30))
            repository.saveCheckpoint(rescheduled.copy(dueDateEpochDay = rescheduledDate,
                dueMileageKm = 107_000, warningDays = null, updatedAt = 4))
            assertFalse(reminderDao.rulesForSource(AssetAttentionSource.CHECKPOINT, checkpointId)
                .single { it.id == ruleId }.isEnabled)
            assertTrue(reminderDao.insertDelivery(AssetNotificationDeliveryEntity(ruleId = ruleId,
                occurrenceKey = "first", deliveredAt = 1)) > 0)
            assertEquals(-1L, reminderDao.insertDelivery(AssetNotificationDeliveryEntity(ruleId = ruleId,
                occurrenceKey = "first", deliveredAt = 2)))
            assertTrue(reminderDao.insertDelivery(AssetNotificationDeliveryEntity(ruleId = ruleId,
                occurrenceKey = "next", deliveredAt = 3)) > 0)
        } } finally { database.close() }
    }
}
