package com.agui.client

import com.agui.client.testing.ScriptedSseServer
import com.agui.core.types.AssistantMessage
import com.agui.core.types.RunFinishedEvent
import com.agui.core.types.TextMessageContentEvent
import com.agui.core.types.Tool
import com.agui.core.types.ToolCallResultEvent
import com.agui.core.types.ToolMessage
import com.agui.core.types.UserMessage
import com.agui.tools.ToolExecutionContext
import com.agui.tools.ToolExecutionResult
import com.agui.tools.ToolExecutor
import com.agui.tools.toolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClientToolFollowUpTest {

    private val toolCallRun = listOf(
        """{"type":"RUN_STARTED","threadId":"t1","runId":"r1"}""",
        """{"type":"TOOL_CALL_START","toolCallId":"call_1","toolCallName":"get_weather","parentMessageId":"a1"}""",
        """{"type":"TOOL_CALL_ARGS","toolCallId":"call_1","delta":"{}"}""",
        """{"type":"TOOL_CALL_END","toolCallId":"call_1"}""",
        """{"type":"RUN_FINISHED","threadId":"t1","runId":"r1"}"""
    )

    private val answerRun = listOf(
        """{"type":"RUN_STARTED","threadId":"t1","runId":"r2"}""",
        """{"type":"TEXT_MESSAGE_START","messageId":"m1","role":"assistant"}""",
        """{"type":"TEXT_MESSAGE_CONTENT","messageId":"m1","delta":"It is sunny and 21 C"}""",
        """{"type":"TEXT_MESSAGE_END","messageId":"m1"}""",
        """{"type":"RUN_FINISHED","threadId":"t1","runId":"r2"}"""
    )

    private class StayTool : ToolExecutor {
        var calls = 0
        override val tool = Tool(name = "get_weather", description = "Current weather", parameters = JsonObject(emptyMap()))
        override suspend fun execute(context: ToolExecutionContext): ToolExecutionResult {
            calls++
            return ToolExecutionResult.success(message = "sunny, 21 C")
        }
    }

    @Test
    fun followUpRunCarriesConversationAndItsEventsReachTheCaller() = runTest {
        val tool = StayTool()
        ScriptedSseServer(listOf(toolCallRun, answerRun)).use { server ->
            val agent = AgUiAgent(server.url) {
                toolRegistry = toolRegistry(tool)
            }

            val events = agent.sendMessage("What is the weather?", threadId = "t1").toList()

            assertEquals(1, tool.calls)
            val result = events.filterIsInstance<ToolCallResultEvent>().single()
            assertEquals("call_1", result.toolCallId)
            assertEquals("sunny, 21 C", result.content)
            assertEquals(
                "It is sunny and 21 C",
                events.filterIsInstance<TextMessageContentEvent>().joinToString("") { it.delta }
            )
            assertEquals(2, events.count { it is RunFinishedEvent })

            assertEquals(2, server.requests.size)
            val followUp = server.requests[1]
            val messages = followUp.getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(listOf("user", "assistant", "tool"), messages.map { it.getValue("role").jsonPrimitive.content })
            val toolCall = messages[1].getValue("toolCalls").jsonArray.single().jsonObject
            assertEquals("call_1", toolCall["id"]?.jsonPrimitive?.content)
            assertEquals("call_1", messages[2]["toolCallId"]?.jsonPrimitive?.content)
            assertEquals("sunny, 21 C", messages[2]["content"]?.jsonPrimitive?.content)
            assertEquals("get_weather", followUp.getValue("tools").jsonArray.single().jsonObject["name"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun statefulAgentKeepsToolRoundTripInHistory() = runTest {
        val tool = StayTool()
        ScriptedSseServer(listOf(toolCallRun, answerRun)).use { server ->
            val agent = StatefulAgUiAgent(server.url) {
                toolRegistry = toolRegistry(tool)
            }

            agent.chat("What is the weather?", threadId = "t1").toList()

            val history = agent.getHistory("t1")
            assertEquals(4, history.size)
            assertIs<UserMessage>(history[0])
            assertEquals("call_1", assertIs<AssistantMessage>(history[1]).toolCalls?.single()?.id)
            assertEquals("sunny, 21 C", assertIs<ToolMessage>(history[2]).content)
            assertEquals("It is sunny and 21 C", assertIs<AssistantMessage>(history[3]).content)
        }
    }

    @Test
    fun followUpRunsStopAtTheConfiguredLimit() = runTest {
        val tool = StayTool()
        ScriptedSseServer(listOf(toolCallRun, toolCallRun, toolCallRun)).use { server ->
            val agent = AgUiAgent(server.url) {
                toolRegistry = toolRegistry(tool)
                maxToolFollowUpRuns = 1
            }

            agent.sendMessage("What is the weather?", threadId = "t1").toList()

            assertEquals(2, server.requests.size)
            assertEquals(2, tool.calls)
        }
    }
}
