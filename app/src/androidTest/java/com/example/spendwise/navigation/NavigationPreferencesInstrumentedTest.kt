package com.example.spendwise.navigation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationPreferencesInstrumentedTest {
    @Test fun orderAndPinsSurviveFileReload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "navigation-${UUID.randomUUID()}.preferences_pb")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = NavigationPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = firstScope, produceFile = { file })
            )
            val order = listOf("HOME", "ASSETS", "COMMITMENTS", "TRANSACTIONS", "ANALYTICS", "MORE", "CATEGORIES")
            first.saveOrder(order)
            first.setPinned("ANALYTICS", false)
            assertEquals(4, first.configuration.first().pinnedDestinations.size)
            firstScope.cancel()

            val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val second = NavigationPreferencesRepository(
                    PreferenceDataStoreFactory.create(scope = secondScope, produceFile = { file })
                )
                assertEquals(order, second.configuration.first().orderedIds)
                assertEquals(4, second.configuration.first().pinnedDestinations.size)
                second.reset()
                assertEquals(NavigationConfiguration.DEFAULT, second.configuration.first())
            } finally {
                secondScope.cancel()
            }
        } finally {
            firstScope.cancel()
            file.delete()
        }
    }
}
