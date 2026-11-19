package com.agui.tools

import com.agui.core.types.FunctionCall
import com.agui.core.types.Tool
import com.agui.core.types.ToolCall
import com.agui.core.types.ToolCallArgsEvent
import com.agui.core.types.ToolCallEndEvent
import com.agui.core.types.ToolCallStartEvent
import com.agui.core.types.ToolMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ToolConcurrencyTest {

    private fun yieldingTool(name: String) = object : AbstractToolExecutor(
        Tool(name = name, description = "Yields once", parameters = JsonObject(emptyMap()))
    ) {
        override suspend fun executeInternal(context: ToolExecutionContext): ToolExecutionResult {
            yield()
            return ToolExecutionResult.success(message = "ok")
        }
    }

    @Test
    fun concurrentExecutionsAreAllCounted() = runTest {
        val registry = DefaultToolRegistry()
        registry.registerTool(yieldingTool("echo"))

        withContext(Dispatchers.Default) {
            (1..CALLS).map { index ->
                async {
                    registry.executeTool(ToolExecutionContext(ToolCall("call-$index", function = FunctionCall("echo", "{}"))))
                }
            }.awaitAll()
        }

        val stats = assertNotNull(registry.getToolStats("echo"))
        assertEquals(CALLS.toLong(), stats.executionCount)
        assertEquals(CALLS.toLong(), stats.successCount)
    }

    @Test
    fun concurrentRegistrationsAreAllKept() = runTest {
        val registry = DefaultToolRegistry()

        withContext(Dispatchers.Default) {
            (1..CALLS).map { index -> async { registry.registerTool(yieldingTool("tool-$index")) } }.awaitAll()
        }

        assertEquals(CALLS, registry.getAllTools().size)
        assertEquals(CALLS, registry.getAllStats().size)
    }

    @Test
    fun managerTracksConcurrentToolCalls() = runTest {
        val registry = DefaultToolRegistry()
        registry.registerTool(yieldingTool("echo"))
        val lock = Mutex()
        val responses = mutableListOf<ToolMessage>()
        val manager = ToolExecutionManager(registry, object : ToolResponseHandler {
            override suspend fun sendToolResponse(toolMessage: ToolMessage, threadId: String?, runId: String?) {
                lock.withLock { responses += toolMessage }
            }
        })

        withContext(Dispatchers.Default) {
            manager.processEventStream(
                events = flow {
                    repeat(CALLS) { index ->
                        emit(ToolCallStartEvent(toolCallId = "call-$index", toolCallName = "echo"))
                        emit(ToolCallArgsEvent(toolCallId = "call-$index", delta = "{}"))
                        emit(ToolCallEndEvent(toolCallId = "call-$index"))
                    }
                },
                threadId = "thread-1",
                runId = "run-1"
            ).toList()
        }

        assertEquals(CALLS, responses.size)
        assertEquals(0, manager.getActiveExecutionCount())
    }

    private companion object {
        const val CALLS = 200
    }
}
