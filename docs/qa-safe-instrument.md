# QA seguro no A34 — testes instrumentados sem perda de dados

Documento operacional do QA-OPS-P1/P2. Substitui o uso de
`connectedPublicAppDebugAndroidTest` em aparelho com dados.

## 1. Por que o incidente aconteceu

- A task `connected*AndroidTest` do AGP instala, roda e **desinstala** o APK do
  app e o APK de teste no fim da execução.
- No AGP 8.13 isso é controlado por
  `android.injected.androidTest.leaveApksInstalledAfterRun`
  (`BooleanOption.ANDROID_TEST_LEAVE_APKS_INSTALLED_AFTER_RUN`), **default
  false** — foi o que apagou os dados de `com.bettertalker.app.debug`.
- Existe também `android.experimental.testOptions.uninstallIncompatibleApks`,
  que pode desinstalar APK incompatível (assinatura/versão) antes de instalar.
- `adb install -r` preserva dados quando package, assinatura e versionCode são
  compatíveis. Não existe “install -r seguro” com assinatura diferente.

## 2. Procedimento seguro

1. `scripts/preflight-install.sh <apk> --allow-debug-on-a34` (inspeção).
2. `scripts/safe-instrument.sh --serial <serial> --class <classe> --allow-debug-on-a34`
   (dry-run: **somente leitura** no aparelho — não para o app, não instala, não
   altera IME e não executa teste; o banco é apenas inspecionado).
3. Repetir com `--execute` **somente com autorização**.
4. O executor usa `adb install -r` e `adb shell am instrument`; nunca
   `connectedAndroidTest`, nunca uninstall, nunca `pm clear`.

Se o Gradle for inevitável, a única mitigação conhecida é
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`; ainda assim
o risco de `uninstallIncompatibleApks` permanece. Prefira `am instrument`.

## 3. Verificação de origem e assinatura

```bash
aapt2 dump badging "$APK" | head -1
apksigner verify --print-certs "$APK" | grep 'SHA-256 digest'
```

- `.debug` exige package `com.bettertalker.app.debug` e cert
  `1389b756e137d59de009375ba366e0bc41318fbca5cf1d427adc39246dfd62a8`.
- O APK de teste precisa ser `com.bettertalker.app.debug.test`, ter
  `targetPackage=com.bettertalker.app.debug` e runner
  `androidx.test.runner.AndroidJUnitRunner`.
- O executor salva o `base.apk` instalado, confere o certificado e recusa
  downgrade. Cert divergente = abort antes de escrever.

## 4. Snapshot válido (definição)

Um snapshot só é aceito quando **todos** os itens abaixo valem:

1. antes do congelamento, o estado inicial do app é lido com o mesmo contrato
   estrito de `pidof`; leitura inconclusiva aborta (**78**, fase
   `estado-inicial`) em vez de assumir "app parado". No modo `--execute`, o
   congelamento é **verificado**: `am force-stop` precisa
   retornar 0 e `pidof` precisa confirmar ausência com contrato estrito —
   exit 0 + PID = ainda ativo; exit 1 sem PID = ausente; qualquer outro exit,
   falha de transporte ou resposta inválida = falha de confirmação. Falha aborta com **78** + `FREEZE-FALHOU.txt` **antes de
   instalar**; o mesmo vale após a instrumentação. No dry-run **não há parada**:
   a cópia é declarada **inspeção parcial** no `preflight.txt`
   (`snapshot_kind=inspecao-parcial-somente-leitura`);
2. `better-talker.db`, `-wal` e `-shm` copiados e verificados por tamanho
   com `run-as wc -c` (sentinela); ao abrir o DB com `sqlite3`, o WAL é
   integrado ao arquivo principal e os arquivos `-wal`/`-shm` são removidos
   pelo próprio SQLite (checkpoint) — o snapshot final é um DB único e
   íntegro;
3. `pragma integrity_check` = `ok` e `pragma journal_mode` registrado;
4. contagens numéricas e não-zero de `notes` e `passages`;
   após o teste, as contagens, o fingerprint de `shared_prefs`/`datastore` e o
   inventário de `files/publicacoes` precisam ser **idênticos** aos de antes;
5. `shared_prefs/` e `datastore/` copiados com existência confirmada por
   sentinela executada **dentro** do `run-as` (`__DIR__`/`__ABSENT__`) e com
   verificação de tamanho; o fingerprint é **hash do conteúdo** de cada arquivo
   (nome + SHA-256), detectando mudança de bytes com o mesmo tamanho,
   criação, exclusão e renomeação; falha de leitura/cópia invalida o snapshot;
6. inventário de `files/publicacoes` em `snapshot/publicacoes.txt`, com aviso
   para anexo indexado cujo arquivo não existe;
7. `run-as ... test -w databases` (restauração é possível);
8. SHA-256 do banco e metadados em `preflight.txt`;
9. **cobertura lógica obrigatória nos dois snapshots**: toda tabela persistente
   do banco (inicial e posterior) precisa estar em `LOGICAL_TABLES`. Tabela nova
   sem cobertura no início → **70**; criada apenas durante/após o teste → **74**
   + `DATA-COBERTURA-FALHOU.txt` com `fase=pos-instrumentacao` (nunca ignora em
   silêncio);
10. **fingerprint lógico por tabela** (`notes`, `passages`, `chat_messages`,
   `decision_records`, `attachments`, `note_images`, `image_backups`, `outlines`,
   `folders`, `s34_*`, `speech_sections`, `sub_points`, `tombstones`,
   `publication_units`): hash do `.dump` de cada tabela, independente do layout
   físico do SQLite/WAL. Mudança de conteúdo, exclusão, edição com a mesma
   contagem ou alteração de vínculo → exit **74** +
   `DATA-FINGERPRINT-FALHOU.txt` com o nome da tabela (sem dados pessoais).
   `passages` está incluído: edição de texto ou de `attachmentId` com a mesma
   contagem é detectada.

Os snapshots ficam em `results/` (default `qa-results/`, ignorado pelo Git) com
`umask 077`. **Não publique `qa-results/`**: as preferências podem conter chaves.

**Preservado no snapshot:** banco + WAL/SHM, preferências/DataStore, metadados
do acervo, APK instalado (rollback). **Não preservado:** os arquivos grandes de
publicações (`files/publicacoes/*`), imagens da nota e qualquer dado fora do
pacote. Copiar somente o `bt.db` **não** é suficiente com WAL ativo.

## 5. Falha após substituir o APK

Antes de instalar, o executor salva o `base.apk` atual em
`results/rollback/base-antes.apk`. Se algo falhar depois da instalação
(testes, timeout, SIGINT/SIGTERM), o `cleanup`:

1. para o pacote de teste;
2. reinstala o APK anterior com `adb install -r` (rollback);
3. restaura o IME original;
4. relança o app se ele estava rodando antes.

O `PASS` só é impresso **pelo cleanup**, depois de IME restaurado, rollback
concluído e app relançado. Se qualquer etapa de cleanup falhar, o script imprime
`FALHOU NO CLEANUP` e sai não-zero — nunca há PASS.

- falha ao restaurar IME → exit **75** + `results/IME-NAO-RESTAURADO.txt`
  (IME esperado/atual, serial e comando de restauração);
- app parado pelo executor que não volta a rodar (falha do `am start` ou
  processo ausente) → exit **77** + `results/RELAUNCH-FALHOU.txt`;
- falha ao congelar (force-stop ≠ 0 ou processo ainda ativo) → exit **78** +
  `FREEZE-FALHOU.txt`, antes de qualquer instalação;
- leitura do IME falha no cleanup → exit **75** + `IME-NAO-RESTAURADO.txt`
  (restauração não confirmada);
- rollback malsucedido → mensagem `ROLLBACK FALHOU` + exit não-zero (ou **76**
  se o resto estava OK) + `results/ROLLBACK-FALHOU.txt` com caminho, SHA-256,
  serial, comando de reinstalação e caminho do snapshot do banco.

Instalar o APK de teste com `adb install -r` **substitui** o APK de teste
instalado (versão/hash podem mudar). Isso é esperado e não afeta os dados do
app; o APK do app é restaurado pelo rollback em caso de falha.

### 5.1 O que o rollback recupera — e o que não recupera

**Recupera:** o APK debug anterior (`results/rollback/base-antes.apk`, reinstalado
com `install -r`), o IME original, o estado de execução do app (relançado quando
estava rodando) e os arquivos de evidência do próprio executor (snapshots,
fingerprints, logs de cleanup e os marcadores `*-FALHOU.txt`).

**Não recupera:** arquivos grandes de publicações (`files/publicacoes/*`), imagens
da nota, preferências/DataStore sobrescritos, banco modificado por terceiros
durante a janela, nem qualquer dado fora do pacote debug. O snapshot é uma
**cópia de verificação**: ele detecta divergência pelo fingerprint lógico e
aborta, mas não restaura o banco automaticamente. A restauração de dados do
acervo depende do backup próprio do aplicativo e está fora do escopo do executor.
O APK de teste substituído não é restaurado — apenas o APK do app é objeto do
rollback. A release `com.bettertalker.app` nunca é instalada, limpa ou
substituída; nenhuma ação de recuperação a modifica.

**Fail-closed:** rollback malsucedido produz `ROLLBACK FALHOU`, exit não-zero e
`results/ROLLBACK-FALHOU.txt`; falha de cleanup nunca gera `PASS`.

## 6. Testes adversariais do executor

| Cenário | Esperado | Obtido |
|---|---|---|
| dry-run (app parado ou rodando) | 0, **nenhuma** chamada de estado | 0 |
| dry-run com IME mudando no meio | 0, **sem** `ime set` | 0 |
| preferências mudam com o mesmo tamanho | 74 | 74 |
| falha de cópia de prefs / banco | 70, snapshot inválido | 70 |
| `force-stop` falha | 78, sem instalar | 78 |
| `force-stop` ok, processo ainda ativo | 78, sem instalar | 78 |
| processo já parado | 0 | 0 |
| congelamento correto | 0 + PASS | 0 |
| `pidof` com erro de transporte | 78, sem instalar | 78 |
| `pidof` com resposta inválida | 78, sem instalar | 78 |
| edição de passagem (mesma contagem) | 74 + `passages` | 74 |
| vínculo `attachmentId` de passagem alterado | 74 + `passages` | 74 |
| tabela persistente sem cobertura (inicial) | 70 + `DATA-COBERTURA-FALHOU.txt` | 70 |
| tabela criada só no banco posterior | 74 + fase `pos-instrumentacao` | 74 |
| leitura inicial do processo falha (transporte) | 78 + fase `estado-inicial` | 78 |
| exclusão de decisão | 74 + tabela | 74 |
| edição de decisão (mesma contagem) | 74 + tabela | 74 |
| edição de mensagem (mesma contagem) | 74 + tabela | 74 |
| vínculo de anexo alterado | 74 + tabela | 74 |
| banco idêntico com layout físico diferente (VACUUM) | 0 | 0 |
| leitura do IME falha no cleanup | 75 | 75 |
| WAL/SHM ausentes | registrados como ausentes, DB íntegro | 0 |
| `dev_size` com falha de transporte | 70, sem instalar | 70 |
| `dev_size` com falha de permissão | 70, sem instalar | 70 |
| prefs existentes inventariadas e copiadas | manifest + fingerprint | 0 |
| relançamento falha ou processo fica ausente | 77 + `RELAUNCH-FALHOU.txt` | 77 |
| A34 com `--execute --no-freeze` | 65 | 65 |
| execução normal | 0 + `OK (2 tests)` | 0 |
| sucesso com IME alterado | `IME restaurado` antes de `== PASS` | 0 |
| falha ao instalar o teste | 71, app intacto | 71 |
| falha ao instalar o app | 71 + rollback | 71 |
| `FAILURES!!!` | 73 + rollback | 73 |
| saída sem `OK (N tests)` | 73 + rollback | 73 |
| timeout | 72 + rollback | 72 |
| assinatura instalada divergente | 65 | 65 |
| banco corrompido | 70 | 70 |
| banco vazio | 70 | 70 |
| APK com package errado | 65 | 65 |
| `INSTRUMENTATION_FAILED` | 73 + rollback | 73 |
| SIGINT durante o teste | 130 + rollback + IME | 130 |
| SIGTERM durante o teste | 143 + rollback + IME | 143 |
| rollback malsucedido | não-zero + `ROLLBACK FALHOU` + arquivo | 73 |
| serial inválido | 70 | 70 |
| transporte extra | 70 | 70 |
| teste APK ausente | 66 | 66 |
| `OK (0 tests)` | 73 | 73 |
| pull do APK instalado falha | 70, sem instalar | 70 |
| preferências/DataStore mudaram | 74 | 74 |
| arquivos de publicações mudaram | 74 | 74 |
| falha ao restaurar IME | 75 | 75 |

Em todas as execuções simuladas: **0** chamadas a `uninstall`, `pm clear` ou
`connected*AndroidTest`, e o pacote release nunca foi citado.

### Reprodução da matriz (host-only)

```bash
scripts/tests/safe-instrument-matrix.sh
```

Requer SDK (aapt2/apksigner), `sqlite3`, `keytool` e a debug keystore
(`~/.android/debug.keystore` ou `DEBUG_KEYSTORE=...`). Os APKs de fixture são
gerados no workdir (aapt2 + apksigner); o ADB é simulado por
`scripts/tests/mock-adb.sh`; **nenhum aparelho é acessado**. A matriz cobre 50
cenários (+1 autoteste do mock) e sai não-zero se qualquer expectativa falhar.

## 7. Pendência de recuperação

O original `nwt_T.epub` do pacote debug continua ausente. Caminho esperado:

```
/data/user/0/com.bettertalker.app.debug/files/publicacoes/\
att-4ae5e709-0004-457d-80f1-3902931fb4ae-nwt_T.epub
```

- tamanho: `14846651` bytes
- SHA-256 registrado: `cce5efdc4767dbf1ca92a3f20886425fe26a3e7fdbb91262eecb8ee2bee6b052`

As 31.078 passagens TNM seguem indexadas; o arquivo só é necessário para
re-indexar/reutilizar o anexo ou abrir o original. Restauração = copiar um EPUB
verificado para o caminho acima, **sem tocar no banco**; reversível apagando o
arquivo. Requer autorização do coordenador.
