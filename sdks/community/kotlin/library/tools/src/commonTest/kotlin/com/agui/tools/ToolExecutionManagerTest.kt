package com.agui.tools

import com.agui.core.types.Tool
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolMessage
import com.agui.core.types.BaseEvent
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.RunStartedEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
    fun toolResponseCanStartFollowUpRunOnSameManager() = runTest {
        val registry = DefaultToolRegistry()
        registry.registerTool(echoTool())
        val followUpEvents = mutableListOf<BaseEvent>()
        lateinit var manager: ToolExecutionManager
        val handler = object : ToolResponseHandler {
            override suspend fun sendToolResponse(toolMessage: ToolMessage, threadId: String?, runId: String?) {
                // A follow-up run through the same manager, as a client sends after a tool result
                manager.processEventStream(
                    events = flowOf(
                        RunStartedEvent(threadId = "thread-3", runId = "run-3b"),
                        RunFinishedEvent(threadId = "thread-3", runId = "run-3b")
                    ),
                    threadId = threadId,
                    runId = "run-3b"
                ).toList(followUpEvents)
            }
        }
        manager = ToolExecutionManager(registry, handler)

        withTimeout(5.seconds) {
            manager.processEventStream(
                events = flowOf(
                    ToolCallStartEvent(toolCallId = "call-3", toolCallName = "echo"),
                    ToolCallArgsEvent(toolCallId = "call-3", delta = "{}"),
                    ToolCallEndEvent(toolCallId = "call-3"),
                    RunFinishedEvent(threadId = "thread-3", runId = "run-3a")
                ),
                threadId = "thread-3",
                runId = "run-3a"
            ).toList()
        }

        assertEquals(2, followUpEvents.size)
        assertEquals(0, manager.getActiveExecutionCount())
    }

    @Test
    fun runWaitsForItsOwnToolCallsBeforeCompleting() = runTest {
        val registry = DefaultToolRegistry()
        registry.registerTool(echoTool())
        val handler = RecordingResponseHandler()
        val manager = ToolExecutionManager(registry, handler)

        repeat(2) { round ->
            manager.processEventStream(
                events = flowOf(
                    ToolCallStartEvent(toolCallId = "call-$round", toolCallName = "echo"),
                    ToolCallArgsEvent(toolCallId = "call-$round", delta = "{}"),
                    ToolCallEndEvent(toolCallId = "call-$round"),
                    RunFinishedEvent(threadId = "thread-4", runId = "run-$round")
                ),
                threadId = "thread-4",
                runId = "run-$round"
            ).toList()
            assertEquals(round + 1, handler.messages.size)
        }
        assertEquals(listOf("call-0", "call-1"), handler.messages.map { it.toolCallId })
    }

    private fun echoTool() = object : AbstractToolExecutor(
        Tool(name = "echo", description = "Returns the provided text", parameters = JsonObject(emptyMap()))
    ) {
        override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success(message = "echo:${context.toolCall.function.arguments}")
    }

    private class RecordingResponseHandler : ToolResponseHandler {
        val messages = mutableListOf<ToolMessage>()
        override suspend fun sendToolResponse(toolMessage: ToolMessage, threadId: String?, runId: String?) {
            messages += toolMessage.copy(content = toolMessage.content)
        }
    }
}
