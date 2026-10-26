# Release — Better Talker Android

**Versão de referência:** 2.8.1 (versionCode 26) · **ABI:** `arm64-v8a` ·
**Distribuição:** GitHub Releases (APK + SHA-256) · **Play Store:** fora do escopo.

## Decisões A0

| Item | Decisão |
|---|---|
| Backup | `android:allowBackup="false"` + `fullBackupContent="false"`. Notas e chave não entram em Auto Backup nem em transferência. Revisitar só quando existir export/import JSON. |
| ABI | Só `arm64-v8a` (runtime sherpa-onnx/Piper). Aparelhos 32-bit exigem build separado no futuro. |
| Distribuição | APK direto via **GitHub Releases**, com `app-release.apk.sha256`. |
| Sync | Firebase/Firestore permanece opcional e desligado sem `google-services.json`. Não incluir no APK de release. |
| Rede | HTTPS only (`usesCleartextTraffic=false` + `network_security_config`). WebView sem acesso a arquivos/conteúdo local. |
| Chave DeepSeek | AES/GCM com chave não exportável no Android Keystore; DataStore guarda só o envelope. Migração automática do texto claro legado. |

## Keystore de release

A keystore **não fica no repositório**. Local usado nesta máquina:

```text
~/.better-talker-keystore/
├── better-talker-release.jks   # PKCS12, alias "better-talker", RSA 4096
├── keystore.properties         # storeFile + senhas (arquivo 600)
├── keystore.sha256             # hash do .jks para conferir o backup
└── LEIA-ME-BACKUP.txt
```

**Ação obrigatória:** copiar essa pasta para um backup offline e para o
gerenciador de senhas. Perder a keystore impede atualizar o app com a mesma
assinatura.

O build local lê `android/keystore.properties` (ignorado pelo git). O CI lê os
secrets:

| Secret | Valor |
|---|---|
| `BT_KEYSTORE_BASE64` | `base64 -w0 better-talker-release.jks` |
| `BT_KEYSTORE_PASSWORD` | senha da keystore |
| `BT_KEY_ALIAS` | `better-talker` |
| `BT_KEY_PASSWORD` | senha da chave |

Sem esses secrets, o workflow de release falha antes de gerar um APK sem
assinatura. O release **nunca** volta a usar a debug key.

## Build local

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:lintRelease
./gradlew :app:assembleRelease
/home/guilherme/Android/Sdk/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

O certificado esperado é `CN=Better Talker, OU=Release, O=Better Talker` —
nunca `Android Debug`.

## Release (GitHub)

1. Atualize `versionName`/`versionCode` e `README.md`/`docs/FREEZE.md`.
2. Rode a suíte, `lintRelease` e `assembleRelease`.
3. Confira `apksigner verify` e o SHA-256.
4. Crie `docs/releases/vX.Y.Z.md` com as notas.
5. Commit + tag `vX.Y.Z` + push da tag. O workflow
   `.github/workflows/android-release.yml` publica o APK e o checksum.
6. Anexe o SHA-256 no release e confira o download.

## Instalação

- APK sideload: permitir “instalar apps desconhecidos” para o navegador/gerenciador.
- Play Protect pode pedir confirmação; o APK é assinado, mas não é distribuído pela Play.
- **Quem instalou build de debug precisa desinstalar** para instalar o release
  (assinaturas diferentes). A desinstalação apaga os dados locais.
- O release usa `allowBackup=false`: não há restauração automática de notas.

## Privacidade (texto para o release)

> As notas ficam no aparelho. A chave da API do DeepSeek é guardada cifrada no
> Android Keystore. Quando o Copilot é usado, o texto do chat e o contexto
> selecionado são enviados por HTTPS à API do DeepSeek; sem chave, o app usa
> apenas o motor determinístico local. O backup do Android está desligado. O
> sync em nuvem permanece desligado/experimental e não é incluído no release.

## O que este release não resolve

C2 (contexto/refs, `lmd` literal, 45 min monolítico), card-first e sync.
