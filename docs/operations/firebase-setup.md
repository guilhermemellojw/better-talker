# Firebase — setup do projeto `better-talker`

Projeto existente: **better-talker** (`project_number 338115469610`).
O `google-services.json` **nunca é commitado** (`.gitignore`). A partir do 5.4
ele pode ficar em dois caminhos, um por flavor:

| Flavor | Caminho | Firebase usado |
|---|---|---|
| `publicApp` | `android/app/src/publicApp/google-services.json` | Auth + Storage (imagens) |
| `syncBeta` | `android/app/src/syncBeta/google-services.json` | Auth + Firestore + Storage |

Sem o json do flavor, o app compila com Firebase inativo e continua 100% local.

## 1. Apps Android no Firebase

O app usa **dois pacotes**:

| Build | Pacote | Keystore | SHA-1 |
|---|---|---|---|
| release | `com.bettertalker.app` | `better-talker-release.jks` | `90:F0:BB:59:5A:6B:D7:E4:2C:65:BC:91:0B:99:D7:34:28:A1:C9:7E` |
| debug | `com.bettertalker.app.debug` | `~/.android/debug.keystore` | `EF:02:BC:F1:FB:10:A2:40:B1:06:63:9C:81:34:01:80:77:36:C2:5B` |

SHAs-256 (opcional, recomendado):

| Build | SHA-256 |
|---|---|
| release | `AD:1B:0A:30:22:56:A6:6A:A0:83:D7:6B:76:72:91:EB:2E:3C:8C:4B:07:F0:7A:E7:41:DD:99:71:62:33:BB:A4` |
| debug | `13:89:B7:56:E1:37:D5:9D:E0:09:37:5B:A3:66:E0:BC:41:31:8F:BC:A5:CF:1D:42:7A:DC:39:24:6D:FD:62:A8` |

> **Estado em 2026-11-04:** os dois apps têm SHA-1/SHA-256 de debug e de
> release registrados. O login do APK `syncBetaRelease` foi validado no tablet
> com a conta de teste. O login do `publicApp` usa os mesmos clients.

### Passos no Console

1. **Configurações do projeto → Seus apps → com.bettertalker.app**
   - Adicionar impressão digital SHA-1 `90:F0:BB:…:C9:7E` (release).
   - (Opcional) adicionar SHA-256 do release.
2. **Adicionar app** → Android → pacote `com.bettertalker.app.debug`
   - Nome: `Better Talker Debug`.
   - SHA-1 `EF:02:BC:…:C2:5B` e SHA-256 do debug.
3. **Authentication → Sign-in method → Google**: ativar.
4. **Firestore Database**: criar (modo produção) se ainda não existir. Só o
   flavor `syncBeta` usa Firestore; o público não inclui a dependência.
5. **Storage**: abrir **Storage → Get Started** e criar o bucket
   `better-talker.firebasestorage.app` (o mesmo do `google-services.json`).
   O Storage exige o plano Blaze com orçamento/alertas configurados; o uso
   típico de imagens fica dentro da cota gratuita.
6. Baixar o **novo** `google-services.json` (com os dois clients) e salvar em
   `android/app/src/publicApp/` e, se for buildar o beta,
   `android/app/src/syncBeta/`. No CI, o secret `GOOGLE_SERVICES_JSON` é
   gravado nos dois caminhos pelo workflow de build; o release público exige
   o secret e grava só em `publicApp`.

## 2. Build

O build gera os string resources manualmente (sem o plugin
`google-services`, incompatível com AGP 8.13):

- `publicAppRelease` e `publicAppDebug` usam o json de `src/publicApp`;
- `syncBetaRelease` e `syncBetaDebug` usam o json de `src/syncBeta`;
- o release público usa o client `com.bettertalker.app`; debug usa `.debug`.

Confira no log do Gradle:

```
[BetterTalker] Firebase do publicApp (com.bettertalker.app.debug) carregado.
```

Builds:

```bash
./gradlew :app:assemblePublicAppDebug      # público: Auth + Storage opt-in
./gradlew :app:assembleSyncBetaRelease     # beta assinado (mesmo applicationId)
```

## 3. Rules

O repo versiona `firestore.rules`, `storage.rules` e `firebase.json`. Para
publicar:

```bash
firebase login
firebase use better-talker
firebase deploy --only firestore:rules,storage
```

`storage.rules` só permite `users/{uid}/images/...` para o próprio uid, exige
`contentType` de imagem e limita cada objeto a 10 MB; não há URL pública.
Testes locais dos dois conjuntos de rules no Emulator:

```bash
cd firestore-tests && npm ci && npm test
cd storage-tests && npm ci && npm test
```

## 4. Orçamento e alerta

No Google Cloud Console → **Billing → Budgets & alerts**:

1. Crie um orçamento para o projeto `better-talker` (conta em BRL).
2. Defina o limite recomendado (ex.: **R$ 5**, ≈ US$ 1) e alertas em
   50%, 80% e 100%.
3. Envie para o e-mail da conta. O app mostra o **uso real** no card
   **Conta → Backup de imagens**; o alerta de faturamento fica no GCP.
   O orçamento `Better Talker - alerta R$ 5` foi criado em 2026-11-10.

O upload é opt-in e automático só depois do login; sem login, nenhuma imagem
sai do aparelho. O limite por imagem é 10 MB; GIF até 2 MB mantém animação.

## 5. Verificação

- `./gradlew :app:assemblePublicAppDebug` deve logar o client do público.
- Instalar o debug e abrir **Configurações → Conta → Backup de imagens**.
- Entrar com Google; o switch **Envio automático** deve ligar.
- Anexar uma imagem numa nota de discurso; em alguns segundos o card deve
  mostrar `1 de N imagens` e o objeto deve aparecer em
  `users/{uid}/images/{noteId}/{imageId}.webp` (ou `.gif`).
- `adb logcat -s ImageBackupWorker` deve mostrar sucesso; falhas de rede
  viram retry e ficam visíveis no card.
- Para testar o restore: apague os arquivos locais em
  `files/images/<noteId>/` mantendo a nota; toque **Baixar imagens**. O card
  mostra `N na nuvem`, `M sem nota local` e quantas foram restauradas.
  Objetos antigos sem metadata usam caminho + defaults; os novos gravam
  `noteId`, `sectionId`, `caption`, `kind`, `order`, `mime`, `addedAt` e
  `sha256`. O download usa `.part`, confere tamanho/sha e só então renomeia.
- **Apagar backup de imagens** remove os objetos da conta, limpa o estado
  local de backup e pausa o envio automático; as imagens locais continuam.
- Beta: ativar **Sincronização em nuvem**, fazer login e escolher o primeiro
  sync; `adb logcat -s SyncWorker` deve mostrar o sync rodando.
