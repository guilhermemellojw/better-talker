# JBR 21 SIGSEGV no VAIO — usar JDK 17 localmente

- **Status:** aberto
- **Data:** 2026-10-19
- **Severidade:** bloqueia build/testes locais de forma intermitente
- **Ambiente:** Linux Mint 22.3, VAIO AMD Ryzen 7 5825U, 6,7 GB RAM,
  Android Studio JBR 21.0.10 (OpenJDK 21.0.10)

## Sintoma

O daemon do Gradle desaparece no meio do build e o `./gradlew` termina com:

```
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
```

O `hs_err_pid*.log` mostra SIGSEGV no coletor G1:

```
#  SIGSEGV (0xb) at pc=0x0000000000000006
# Problematic frame:
# V  [libjvm.so+0x9a17c7]  G1CMBitMapClosure::do_addr(HeapWordImpl**)+0x1a7
```

Em alguns casos o daemon do Kotlin cai antes, com
`Connection to the Kotlin daemon has been unexpectedly lost` /
`EOFException`, e o Gradle tenta recompilar sem daemon.

## Quando ocorre

Com mais de um build/teste do mesmo projeto em paralelo (ou com o daemon
do Android Studio/Kotlin já ativo), em máquinas com pouca RAM. Já foi
observado em pelo menos dois agentes/sessões diferentes.

## Workaround validado

1. Usar **JDK 17** para o Gradle:

   ```bash
   export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
   export ANDROID_HOME="$HOME/Android/Sdk"
   ```

2. Evitar builds concorrentes no mesmo working tree.
3. Em caso de caches incrementais corrompidos:

   ```bash
   rm -rf app/build/kotlin
   ```

4. Se o daemon do Kotlin continuar caindo, compilar em processo:

   ```bash
   ./gradlew :app:testDebugUnitTest --no-daemon --max-workers=1 \
     -Pkotlin.compiler.execution.strategy=in-process \
     -Dorg.gradle.jvmargs="-Xmx1536m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC"
   ```

## O que não resolve

- Aumentar somente o heap do Gradle (o crash é no G1, não OOM).
- Repetir o build com o mesmo JBR 21 sem liberar memória/daemons.

## Critério de encerramento

- Build e suíte completa estáveis com JBR 21 em duas execuções
  consecutivas no VAIO, sem SIGSEGV e sem fallback de compilação.
