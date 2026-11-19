package com.agui.client

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneratedIdsTest {

    private class IdAgent : AgUiAgent("http://mock-url") {
        fun ids(): List<String> = listOf(generateThreadId(), generateRunId(), generateId("msg"))
    }

    @Test
    fun idsGeneratedInTheSameMillisecondDoNotCollide() {
        val agent = IdAgent()

        val ids = (1..1_000).flatMap { agent.ids() }

        assertEquals(ids.size, ids.toSet().size)
    }
}
