# Pre-flight de instalação/teste

Antes de qualquer `adb install` ou teste instrumentado, rode:

```bash
./scripts/preflight-install.sh <apk>
# para validar o modelo do aparelho:
ADB_SERIAL=<serial> ./scripts/preflight-install.sh <apk>
# teste de app limpo no A34 com o pacote .debug:
ADB_SERIAL=<serial> ./scripts/preflight-install.sh <apk> --allow-debug-on-a34
```

O script **não instala nada**. Ele inspeciona o APK e os aparelhos e aborta
combinações perigosas.

## Regras

1. **Pacote release:** `com.bettertalker.app` + certificado de release
   (`ad1b0a302256a66aa083d76b767291eb2e3c8c4b07f07ae741dd99716233bba4`).
2. **Pacote debug:** `com.bettertalker.app.debug` + certificado debug
   (`1389b756e137d59de009375ba366e0bc41318fbca5cf1d427adc39246dfd62a8`).
   Ele **coexiste** com o release.
3. **Nunca** instale um APK com `applicationId` `com.bettertalker.app` e
   assinatura debug. Foi exatamente isso que substituiu o release e apagou
   os dados do A34 em 2026-10-31. O script aborta.
4. **A34 (`SM-A346M`):** release-only. Teste de app limpo com `.debug` só
   com `--allow-debug-on-a34`; o `.debug` não toca nos dados do release.
5. **Tablet (`25040RP0AL`):** debug/matrix. Não instale o release nele.
6. **Antes de instalar release:** exporte backup em
   **Configurações → Dados → Exportar backup** e guarde o ZIP fora do
   aparelho.
7. **Teste instrumentado:** use o pacote `.debug` e o `ADB_SERIAL` do
   aparelho de teste; nunca rode contra o release do A34.
8. Se um build antigo ainda usar o `applicationId` do release com outra
   assinatura, a instalação exige desinstalar — e a desinstalação apaga os
   dados locais. Exporte antes.

## Checklist manual (equivalente)

```bash
AAPT=$(ls -1 ${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/*/aapt2 | tail -1)
APKSIGNER=$(ls -1 ${ANDROID_HOME:-$HOME/Android/Sdk}/build-tools/*/apksigner | tail -1)

"$AAPT" dump badging app-release.apk | grep '^package:'
"$APKSIGNER" verify --print-certs app-release.apk | grep 'Signer #1 certificate SHA-256'
adb devices -l
adb -s "$ADB_SERIAL" shell getprop ro.product.model
```

- `package: com.bettertalker.app` + cert `ad1b0a30…` → release.
- `package: com.bettertalker.app.debug` + cert `1389b756…` → debug.
- Qualquer outra combinação → **pare**.

## Contexto

Em 2026-10-31, um build debug com o `applicationId` do release foi
instalado por cima do release no A34; o Android tratou como atualização,
substituiu o app e apagou o banco local. O banco foi restaurado do snapshot
`a34-live.db` e a causa foi corrigida com `applicationIdSuffix ".debug"`.
Este pre-flight existe para impedir a repetição.
