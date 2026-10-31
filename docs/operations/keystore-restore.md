# Keystore de release — custódia e restore

**P0.** Sem a keystore, não é possível atualizar o app com a mesma
assinatura. O Android tem `allowBackup=false`; o Keystore do Android guarda
a chave da API, mas **não** substitui a keystore de assinatura.

## Estado atual

- Original: `~/.better-talker-keystore/better-talker-release.jks`
- Cópias: `~/btwork/keystore-backup/better-talker-release.jks` e
  `better-talker-release-copy2.jks`
- Alias: `better-talker`
- SHA-256 do arquivo (idêntico nas duas cópias):
  `52f974bd4cc20ffece16aef045972b6e596f274f98299379075a11a276582fa3`
- Certificado SHA-256:
  `AD:1B:0A:30:22:56:A6:6A:A0:83:D7:6B:76:72:91:EB:2E:3C:8C:4B:07:F0:7A:E7:41:DD:99:71:62:33:BB:A4`
- Rehearsal local: `keytool -list -v` abriu a cópia e o certificado confere.
- **Pendência:** as duas cópias estão na mesma máquina e não há mídia
  externa montada. A cópia off-machine é uma ação humana.

## Runbook de custódia

1. Copie o `.jks` para um pendrive **e** para outra máquina (duas mídias
   diferentes; não deixe as duas no mesmo disco).
2. Guarde a senha no gerenciador de senhas, **separada** do arquivo.
   Nunca commite `keystore.properties`, `.jks` ou senhas no repositório.
3. Confira o hash nas cópias:
   ```bash
   sha256sum better-talker-release.jks
   # 52f974bd4cc20ffece16aef045972b6e596f274f98299379075a11a276582fa3
   ```
4. Na máquina de restore, abra a keystore e compare o certificado:
   ```bash
   keytool -list -v \
     -keystore better-talker-release.jks \
     -alias better-talker
   # Certificate fingerprint (SHA-256):
   # AD:1B:0A:30:22:56:A6:6A:A0:83:D7:6B:76:72:91:EB:
   # 2E:3C:8C:4B:07:F0:7A:E7:41:DD:99:71:62:33:BB:A4
   ```
5. Gere um APK de teste com a keystore restaurada e confira o signer:
   ```bash
   export BT_KEYSTORE_FILE=/caminho/better-talker-release.jks
   export BT_KEYSTORE_PASSWORD='...'   # do gerenciador; não ecoe
   export BT_KEY_ALIAS=better-talker
   export BT_KEY_PASSWORD='...'
   cd android && ./gradlew :app:assembleRelease
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   # Signer #1 certificate DN: CN=Better Talker
   ```
6. Guarde uma cópia off-site (cofre/outra cidade) e documente data,
   responsável e local. Teste o restore novamente a cada troca de máquina.

## O que não fazer

- Não gerar uma keystore nova para “resolver” a perda: isso muda a
  assinatura e impede atualizar o app instalado.
- Não colocar a senha no mesmo pendrive do `.jks`.
- Não usar a debug keystore para release.
- Não confiar no backup do Android (`allowBackup=false`).
