package com.bettertalker.app.data.voice;

import kotlin.jvm.functions.Function1;

/**
 * #23-fix-b: callback do streaming do sherpa-onnx.
 *
 * O JNI (jni/offline-tts.cc, CallCallback) busca por reflexão o método
 * `invoke([F)Ljava/lang/Integer;` — retorno BOXED. Lambdas Kotlin geram
 * `invoke([F)I` (primitivo): o GetMethodID falha e o JNI deixa a exceção
 * pendente → SIGABRT no próximo NewFloatArray (bug conhecido do sherpa).
 * Esta classe Java garante a assinatura exata que o JNI procura.
 */
public final class SherpaTtsCallback implements Function1<float[], Integer> {

    private final Function1<float[], Integer> block;

    public SherpaTtsCallback(Function1<float[], Integer> block) {
        this.block = block;
    }

    @Override
    public Integer invoke(float[] samples) {
        return block.invoke(samples);
    }
}
