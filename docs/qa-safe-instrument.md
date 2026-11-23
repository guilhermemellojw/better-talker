# QA seguro no A34 — testes instrumentados sem perda de dados

Documento operacional criado no QA-OPS-P1 depois que
`connectedPublicAppDebugAndroidTest` desinstalou `com.bettertalker.app.debug`
e apagou os dados do pacote de QA (23/11/2026).

## 1. Por que o incidente aconteceu

- A task `connected*AndroidTest` do AGP executa instalação, testes e **uninstall**
  do APK do app e do APK de teste no mesmo device.
- No AGP 8.13 esse comportamento é controlado pela opção
  `android.injected.androidTest.leaveApksInstalledAfterRun`
  (`BooleanOption.ANDROID_TEST_LEAVE_APKS_INSTALLED_AFTER_RUN`), com **default
  false** — ou seja, uninstall ao final.
- Existe também `android.experimental.testOptions.uninstallIncompatibleApks`,
  que pode desinstalar um app incompatível (assinatura/versão) antes de instalar.
- `adb install -r` **não** desinstala e preserva os dados, desde que:
  - o package seja o mesmo;
  - a assinatura seja a mesma;
  - o `versionCode` não seja downgrade.

## 2. Procedimento seguro (preferido)

1. `scripts/preflight-install.sh <apk> --allow-debug-on-a34` (inspeção).
2. `scripts/safe-instrument.sh --serial <serial> --class <classe> --allow-debug-on-a34`
   (dry-run; mostra tudo que faria).
3. Repetir com `--execute` **somente com autorização**.
4. O script usa `adb install -r` e `adb shell am instrument`, nunca
   `connectedAndroidTest`, nunca `uninstall`, nunca `pm clear`.

Se por algum motivo for inevitável usar o Gradle, a única forma conhecida de
evitar o uninstall é passar
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`; ainda assim
o risco de `uninstallIncompatibleApks` permanece. Prefira `am instrument`.

## 3. Verificação de origem e assinatura

```bash
aapt2 dump badging "$APK" | head -1
apksigner verify --print-certs "$APK" | grep 'SHA-256 digest'
```

- `.debug` deve ter package `com.bettertalker.app.debug` e cert
  `1389b756e137d59de009375ba366e0bc41318fbca5cf1d427adc39246dfd62a8`.
- O script também puxa o `base.apk` instalado e confere o mesmo digest antes
  de qualquer instalação.
- Cert divergente = **abortar**. Instalar com assinatura diferente apaga os dados.

## 4. Snapshot e restauração

Antes de qualquer escrita o script:

1. extrai `databases/better-talker.db` (+ WAL) via `run-as`;
2. roda `pragma integrity_check` (precisa ser `ok`);
3. exige `notes > 0` e `passages > 0`;
4. confirma que `databases/` é gravável (restauração possível);
5. registra SHA-256, counts, IME e transporte em `preflight.txt`.

Depois do teste ele relê o banco e compara counts. Divergência = falha.

## 5. Testes não destrutivos do próprio executor

- `bash -n scripts/safe-instrument.sh`
- `shellcheck scripts/safe-instrument.sh` (quando disponível)
- dry-run no A34: não instala, não roda, não muda IME
- serial inválido → aborta com código 70
- múltiplos transportes → aborta com código 70
- APK com cert errado / downgrade → aborta com código 65
- saída de instrumentação sem `OK (N tests)` → falha (nunca PASS)

## 6. Pendência de recuperação

O arquivo original `nwt_T.epub` do pacote debug continua ausente.
Caminho esperado (registrado no banco):

```
/data/user/0/com.bettertalker.app.debug/files/publicacoes/\
att-4ae5e709-0004-457d-80f1-3902931fb4ae-nwt_T.epub
```

- tamanho: `14846651` bytes
- SHA-256 registrado: `cce5efdc4767dbf1ca92a3f20886425fe26a3e7fdbb91262eecb8ee2bee6b052`

As 31.078 passagens TNM seguem indexadas; o arquivo só é necessário para
re-indexar ou abrir o original. A restauração consiste em copiar um EPUB
verificado para o caminho acima — **sem tocar no banco** — e é reversível.
Não executar sem autorização do coordenador.
