# Firebase — setup do projeto `better-talker`

Projeto existente: **better-talker** (`project_number 338115469610`).
O `google-services.json` **nunca é commitado** (`.gitignore`); ele fica em
`android/app/google-services.json` na máquina de build.

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

> **Estado em 2026-11-02:** o app `com.bettertalker.app.debug` foi criado com o
> SHA-1 do debug (`ef02bc…`) e o login **funciona**. O app de release ainda está
> com o SHA-1 do debug; **falta adicionar `90:F0:BB:…:C9:7E`** para o login do
> release funcionar.

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
   `android/app/google-services.json`.

## 2. Build

O build lê o json e gera os string resources manualmente (sem o plugin
`google-services`, incompatível com AGP 8.13):

- `defaultConfig` usa o client `com.bettertalker.app`;
- `buildTypes.debug` sobrescreve com o client `com.bettertalker.app.debug`
  (quando presente);
- sem o json, o build segue 100% local e o Firebase fica inativo.

Confira no log do Gradle:

```
[BetterTalker] Firebase: clientes release + debug encontrados.
[BetterTalker] Firebase resources gerados do google-services.json (login + Firestore ativos).
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

- `./gradlew :app:assembleDebug` deve logar os dois clients.
- Instalar o `.debug` no A34 e fazer login Google em Configurações → Conta.
- `adb logcat -s SyncWorker` deve mostrar o sync rodando (sem erro de
  permissão/rules).
- No Console → Firestore, conferir `users/{uid}/notes`.
