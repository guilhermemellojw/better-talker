package com.bettertalker.app.data.llm

import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekExperimentTest {

    @Test
    fun requestBodyUsaTemperatureDaFlag() {
        val body = DeepSeekProvider.requestBody(
            system = "system",
            user = "user",
            temperature = 0.7f,
        )
        assertTrue(body.contains("\"temperature\":0.7"))
    }

    @Test
    fun requestBodyDefaultMantemTemperaturaAtual() {
        val body = DeepSeekProvider.requestBody(system = "system", user = "user")
        assertTrue(body.contains("\"temperature\":0.2"))
    }
}
