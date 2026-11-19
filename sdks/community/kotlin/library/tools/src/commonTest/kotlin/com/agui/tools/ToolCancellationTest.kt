package com.agui.tools

import com.agui.core.types.FunctionCall
import com.agui.core.types.Tool
import com.agui.core.types.ToolCall
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToolCancellationTest {

    private val started = CompletableDeferred<Unit>()

    private val blockingTool = object : AbstractToolExecutor(
        Tool(name = "blocking", description = "Never returns", parameters = JsonObject(emptyMap()))
    ) {
        override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult {
            started.complete(Unit)
            awaitCancellation()
        }
    }

    @Test
    fun registryRethrowsCancellationWithoutCountingFailure() = runTest {
        val registry = DefaultToolRegistry()
        registry.registerTool(blockingTool)
        var thrown: Throwable? = null

        val job = launch {
            try {
                registry.executeTool(ToolExecutionContext(ToolCall("call-1", function = FunctionCall("blocking", "{}"))))
            } catch (e: Throwable) {
                thrown = e
                throw e
            }
        }
        started.await()
        job.cancelAndJoin()

        assertIs<CancellationException>(thrown)
        assertEquals(0, assertNotNull(registry.getToolStats("blocking")).failureCount)
    }

    @Test
    fun cancelledRunSendsNoErrorToolResponse() = runTest {
        val registry = DefaultToolRegistry()
        registry.registerTool(blockingTool)
        val responses = mutableListOf<ToolMessage>()
        val manager = ToolExecutionManager(registry, object : ToolResponseHandler {
            override suspend fun sendToolResponse(toolMessage: ToolMessage, threadId: String?, runId: String?) {
                responses += toolMessage
            }
        })

        val job = launch {
            manager.processEventStream(
                events = flow {
                    emit(ToolCallStartEvent(toolCallId = "call-2", toolCallName = "blocking"))
                    emit(ToolCallArgsEvent(toolCallId = "call-2", delta = "{}"))
                    emit(ToolCallEndEvent(toolCallId = "call-2"))
                    awaitCancellation()
                },
                threadId = "thread-1",
                runId = "run-1"
            ).collect()
        }
        started.await()
        job.cancelAndJoin()

        assertTrue(responses.isEmpty(), "unexpected tool responses: $responses")
        assertEquals(0, manager.getActiveExecutionCount())
    }
}
