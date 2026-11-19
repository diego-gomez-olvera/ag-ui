package com.agui.client

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import com.agui.client.testing.ScriptedSseServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PayloadLoggingTest {

    private val logs = mutableListOf<String>()

    private val run = listOf(
        """{"type":"RUN_STARTED","threadId":"t1","runId":"r1"}""",
        """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
        """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"$SECRET"}""",
        """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
        """{"type":"RUN_FINISHED","threadId":"t1","runId":"r1"}"""
    )

    @BeforeTest
    fun captureLogs() {
        Logger.setMinSeverity(Severity.Verbose)
        Logger.setLogWriters(object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                synchronized(logs) { logs += message }
            }
        })
    }

    @AfterTest
    fun restoreLogs() {
        Logger.setLogWriters(platformLogWriter())
    }

    @Test
    fun payloadsAreNotLoggedByDefault() = runTest {
        ScriptedSseServer(listOf(run)).use { server ->
            AgUiAgent(server.url).sendMessage("Hi", threadId = "t1").toList()
        }

        assertFalse(synchronized(logs) { logs.any { SECRET in it } }, "payload logged: $logs")
    }

    @Test
    fun payloadsAreLoggedInDebugMode() = runTest {
        ScriptedSseServer(listOf(run)).use { server ->
            AgUiAgent(server.url) {
                debug = true
            }.sendMessage("Hi", threadId = "t1").toList()
        }

        assertTrue(synchronized(logs) { logs.any { SECRET in it } })
    }

    private companion object {
        const val SECRET = "Jane Doe, 1 Main Street"
    }
}
