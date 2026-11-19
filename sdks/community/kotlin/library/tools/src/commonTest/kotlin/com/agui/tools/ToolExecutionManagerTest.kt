package com.agui.tools

import com.agui.core.types.Tool
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolMessage
import com.agui.core.types.RunFinishedEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.assertNull

class ToolExecutionManagerTest {

    @Test
    fun executesRegisteredToolAndEmitsLifecycleEvents() = runTest {
        val registry = DefaultToolRegistry()
        val handler = RecordingResponseHandler()
        val manager = ToolExecutionManager(registry, handler)

        registry.registerTool(
            object : AbstractToolExecutor(
                Tool(
                    name = "echo",
                    description = "Returns the provided text",
                    parameters = JsonObject(emptyMap())
                )
            ) {
                override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult {
                    return ToolExecutionResult.success(message = "echo:${context.toolCall.function.arguments}")
                }
            }
        )

        val streamEvents = listOf(
            ToolCallStartEvent(toolCallId = "call-1", toolCallName = "echo"),
            ToolCallArgsEvent(toolCallId = "call-1", delta = """{"text":"hi"}"""),
            ToolCallEndEvent(toolCallId = "call-1"),
            RunFinishedEvent(threadId = "thread-1", runId = "run-1")
        )

        val collected = manager.processEventStream(
            events = flowOf(*streamEvents.toTypedArray()),
            threadId = "thread-1",
            runId = "run-1"
        ).toList()

        assertEquals(streamEvents, collected)
        assertEquals(1, handler.messages.size)
        val response = handler.messages.single()
        assertEquals("call-1", response.toolCallId)
        assertEquals("""echo:{"text":"hi"}""", response.content)
    }

    @Test
    fun unknownToolIsSkippedNotErrored() = runTest {
        // Unknown tools are assumed to be server-handled (e.g. A2UI's render_a2ui
        // injected by CopilotRuntime's a2ui middleware). Emitting a client-side
        // error response would race the server's synthetic TOOL_CALL_RESULT and
        // leave two conflicting tool results in history, breaking subsequent
        // turns. Silently skip instead.
        val registry = DefaultToolRegistry() // intentionally empty
        val handler = RecordingResponseHandler()
        val manager = ToolExecutionManager(registry, handler)

        manager.processEventStream(
            events = flowOf(
                ToolCallStartEvent(toolCallId = "missing-1", toolCallName = "unknown"),
                ToolCallArgsEvent(toolCallId = "missing-1", delta = "{}"),
                ToolCallEndEvent(toolCallId = "missing-1"),
                RunFinishedEvent(threadId = "thread-2", runId = "run-2")
            ),
            threadId = "thread-2",
            runId = "run-2"
        ).toList()

        assertEquals(0, handler.messages.size)
    }

    @Test
    fun failedToolSetsErrorOnToolMessage() = runTest {
        val handler = runSingleTool { ToolExecutionResult.failure("City not found") }

        val response = handler.messages.single()
        assertEquals("City not found", response.content)
        assertEquals("City not found", response.error)
    }

    @Test
    fun throwingToolSetsErrorOnToolMessage() = runTest {
        val handler = runSingleTool { throw ToolExecutionException("Storage unavailable") }

        assertEquals("Storage unavailable", handler.messages.single().error)
    }

    @Test
    fun stringResultIsSentUnquoted() = runTest {
        val handler = runSingleTool { ToolExecutionResult.success(JsonPrimitive("sunny, 21 C")) }

        val response = handler.messages.single()
        assertEquals("sunny, 21 C", response.content)
        assertNull(response.error)
    }

    @Test
    fun objectResultIsSentAsJson() = runTest {
        val handler = runSingleTool {
            ToolExecutionResult.success(buildJsonObject { put("temperature", "21 C") })
        }

        assertEquals("""{"temperature":"21 C"}""", handler.messages.single().content)
    }

    private suspend fun runSingleTool(execute: suspend () -> ToolExecutionResult): RecordingResponseHandler {
        val registry = DefaultToolRegistry()
        registry.registerTool(object : ToolExecutor {
            override val tool = Tool(name = "forecast", description = "Forecast", parameters = JsonObject(emptyMap()))
            override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult = execute()
        })
        val handler = RecordingResponseHandler()
        ToolExecutionManager(registry, handler).processEventStream(
            events = flowOf(
                ToolCallStartEvent(toolCallId = "call-t", toolCallName = "forecast"),
                ToolCallEndEvent(toolCallId = "call-t"),
                RunFinishedEvent(threadId = "thread-6", runId = "run-6")
            ),
            threadId = "thread-6",
            runId = "run-6"
        ).toList()
        return handler
    }

    private class RecordingResponseHandler : ToolResponseHandler {
        val messages = mutableListOf<ToolMessage>()
        override suspend fun sendToolResponse(toolMessage: ToolMessage, threadId: String?, runId: String?) {
            messages += toolMessage.copy(content = toolMessage.content)
        }
    }
}
