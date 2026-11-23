# Testes host-only do executor seguro

`safe-instrument-matrix.sh` exercita `scripts/safe-instrument.sh` contra um ADB
**simulado** (`mock-adb.sh`). Nenhum aparelho físico é acessado.

## Pré-requisitos

- Linux com `bash`, `sqlite3`, `keytool` (JDK) e Android SDK
  (`aapt2`/`apksigner`) via `ANDROID_HOME` ou `~/Android/Sdk`;
- debug keystore em `~/.android/debug.keystore` (ou `DEBUG_KEYSTORE=...`).

Os APKs de fixture (app, app-alvo e androidTest) são **gerados no workdir**
com `aapt2 link` + `apksigner`; nenhum APK de produção é necessário.

## Uso

```bash
scripts/tests/safe-instrument-matrix.sh
```

`KEEP=1` mantém o diretório temporário com logs/saídas para inspeção.

## Cobertura

45 cenários: dry-run sem chamadas de estado (app parado, rodando e com IME
mudando), preferências alteradas com o mesmo tamanho, falha de cópia de
prefs/banco, relançamento ok/falho/processo ausente, A34 recusando
`--execute --no-freeze`, congelamento verificado (falha, processo vivo, já
parado, sucesso), alterações lógicas em decisões/mensagens/anexos detectadas
por tabela (incluindo texto e vínculo de `passages`), tabela persistente sem
cobertura, banco fisicamente diferente e logicamente igual, `pidof` com erro de
transporte/resposta inválida, leitura de IME falhando no cleanup, sucesso com
IME restaurado antes do PASS, falha de IME (75), falha de instalação do teste e
do app, `FAILURES`, saída incompleta, `OK (0 tests)`, crash, timeout, assinatura
divergente, banco corrompido/vazio, APK errado, test APK ausente, prefs e
publicações alteradas, rollback malsucedido, SIGINT (130) e SIGTERM (143).

A matriz sai não-zero se qualquer expectativa falhar.
