package com.agui.client.tools

import com.agui.core.types.ToolMessage
import com.agui.tools.ToolResponseHandler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Collects the tool results of one run so the agent can send them in its follow-up run.
 * Tool calls run concurrently, so access is guarded by a mutex.
 */
internal class ToolResultCollector : ToolResponseHandler {
    private val mutex = Mutex()
    private val results = mutableListOf<ToolMessage>()

    override suspend fun sendToolResponse(toolMessage: ToolMessage, threadId: String?, runId: String?) {
        mutex.withLock { results += toolMessage }
    }

    suspend fun messages(): List<ToolMessage> = mutex.withLock { results.toList() }
}
