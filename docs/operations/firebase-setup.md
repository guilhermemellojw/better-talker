# Firebase — setup do projeto `better-talker`

Projeto existente: **better-talker** (`project_number 338115469610`).
O `google-services.json` **nunca é commitado** (`.gitignore`); ele fica em
`android/app/src/syncBeta/google-services.json` na máquina de build/CI. O
flavor público não lê esse arquivo.

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
> com a conta de teste.

### Passos no Console

1. **Configurações do projeto → Seus apps → com.bettertalker.app**
   - Adicionar impressão digital SHA-1 `90:F0:BB:…:C9:7E` (release).
   - (Opcional) adicionar SHA-256 do release.
2. **Adicionar app** → Android → pacote `com.bettertalker.app.debug`
   - Nome: `Better Talker Debug`.
   - SHA-1 `EF:02:BC:…:C2:5B` e SHA-256 do debug.
3. **Authentication → Sign-in method → Google**: ativar.
4. **Firestore Database**: criar (modo produção) se ainda não existir.
5. Baixar o **novo** `google-services.json` (com os dois clients) e salvar em
   `android/app/src/syncBeta/google-services.json`. No CI, o secret
   `GOOGLE_SERVICES_JSON` é gravado nesse caminho.

## 2. Build

O flavor `syncBeta` lê o json e gera os string resources manualmente (sem o
plugin `google-services`, incompatível com AGP 8.13):

- `syncBetaRelease` usa o client `com.bettertalker.app`;
- `syncBetaDebug` usa o client `com.bettertalker.app.debug`;
- `publicApp` não recebe Firebase, Credential Manager nem o json.

Confira no log do Gradle:

```
[BetterTalker] Firebase do syncBeta (com.bettertalker.app) carregado.
```

Builds:

```bash
./gradlew :app:assemblePublicAppDebug      # público, 100% local
./gradlew :app:assembleSyncBetaRelease     # beta assinado (mesmo applicationId)
```

## 3. Rules

O repo versiona `firestore.rules` + `firebase.json`. Para publicar:

```bash
firebase login
firebase use better-talker
firebase deploy --only firestore:rules
```

Testes locais no Emulator (sem tocar na nuvem):

```bash
cd firestore-tests && npm ci && npm test
```

## 4. Verificação

- `./gradlew :app:assembleSyncBetaRelease` deve logar o client do beta.
- Instalar o `syncBetaRelease` e ativar Configurações → Conta →
  Sincronização em nuvem (Experimental). O sync começa **desligado**.
- Fazer login Google com a conta de teste e escolher o primeiro sync.
- `adb logcat -s SyncWorker` deve mostrar o sync rodando (sem erro de
  permissão/rules).
- No Console → Firestore, conferir `users/{uid}/notes`.
