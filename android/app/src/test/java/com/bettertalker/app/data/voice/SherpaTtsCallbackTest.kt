package com.bettertalker.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #23-fix-b: o JNI do sherpa procura `invoke([F)Ljava/lang/Integer;`
 * (boxed) — este teste trava a assinatura que evita o SIGABRT.
 */
class SherpaTtsCallbackTest {

    @Test
    fun assinaturaExataQueOJniProcura() {
        val m = SherpaTtsCallback::class.java
            .getMethod("invoke", FloatArray::class.java)
        assertEquals("invoke", m.name)
        assertEquals(FloatArray::class.java, m.parameterTypes[0])
        // BOXED: java.lang.Integer (e não o primitivo int).
        assertEquals(java.lang.Integer::class.java, m.returnType)
    }

    @Test
    fun delegaParaOBlocoERetornaOValor() {
        var recebido: FloatArray? = null
        val cb = SherpaTtsCallback { samples ->
            recebido = samples
            7
        }
        val retorno = cb.invoke(floatArrayOf(0.1f, 0.2f))
        assertNotNull(recebido)
        assertEquals(2, recebido!!.size)
        assertEquals(7, retorno.toInt())
    }
}
