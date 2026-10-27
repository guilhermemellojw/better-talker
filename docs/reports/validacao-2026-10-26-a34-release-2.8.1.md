# Validação no A34 — release 2.8.1 (A1/A2)

**Data:** 2026-10-26
**Aparelho:** Samsung Galaxy A34 (Android 16)
**APK:** release assinado 2.8.1 / versionCode 26
**SHA-256 do APK local (não é o artefato da CI):** `ca8a42a6b2fe1ca93d51f80f9c65c493af249bad1d4c65e69352852fc40e3cae`
**CI dry-run:** run [40418833924](https://github.com/guilhermemellojw/better-talker/actions/runs/40418833924) — success; APK da CI com SHA-256 `dd49abf1b289737e3fc05007c1d29e34a14e68f06ef78ff689dce425a8e08781` e mesmo certificado de release.
**Certificado:** `CN=Better Talker, OU=Release, O=Better Talker, L=Bom Jardim, ST=RJ, C=BR`
**SHA-256 do certificado:** `ad1b0a302256a66aa083d76b767291eb2e3c8c4b07f07ae741dd99716233bba4`
**Suíte:** **2.524 testes JVM / 0 falhas** · `lintRelease` verde

## A1 — resultado

| Item | Evidência |
|---|---|
| Backup desligado | `allowBackup=false` + `fullBackupContent=false`; `bmgr backupnow com.bettertalker.app` → “Backup is not allowed”. |
| Chave no Keystore | Chave salva, app reiniciado e “Testar conexão” → **Online** (decriptou e usou a chave real). |
| Remoção | “Remover chave” → status **Sem chave**; sem ciphertext legível no fluxo. |
| Migração do texto claro | Instalei 2.8.0 debug com a chave legada: DataStore com `sk-` e `deepseek_api_key`. Instalei 2.8.1 debug por cima: DataStore com **0 ocorrências de `sk-`** e apenas `deepseek_api_key_encrypted`; “Testar conexão” → **Online**. |
| Release não debugável | `run-as` falha no APK de release (`package not debuggable`), como esperado; a inspeção do DataStore foi feita no build debug equivalente. |
| Assinatura | `apksigner verify --print-certs` mostra certificado de release (RSA 4096), não “Android Debug”. |
| Rede | Manifest do APK: `usesCleartextTraffic=false`, `networkSecurityConfig` presente. |
| WebView | `allowFileAccess=false`, `allowContentAccess=false`, mixed content never; navegação externa só HTTPS. PDF export com WebView offline e sem rede. |
| ABI | `native-code: 'arm64-v8a'` no APK. |
| Sync | Sem `google-services.json`; `AuthRepository.isConfigured()` falso e `SyncScheduler` **não agenda** worker (sync desligado/experimental). |

## A2 — smoke no release

| Cenário | Resultado |
|---|---|
| Instalação limpa | Desinstalei a build de debug e instalei o release 2.8.1/26; abriu no onboarding/Home. |
| Chave + reinício | Chave salva, force-stop/reabertura manteve “Chave salva”; teste de conexão Online. |
| Remoção | Chave removida; status “Sem chave”. |
| Importar S-34 | `S-34_T_192.docx` importado pelo seletor de Documentos; nota “N.º 192 — Você está no caminho para a vida eterna?” criada com orientações do orador e seções. |
| Chat com chave real | `budget=2048`, `finish=stop`, 323 palavras, sem truncamento. |
| V&M discurso 5 min | Formulário “Criar esboço do zero” → Discurso público 5 min/3 pontos: prévia **Introdução 1 + Ponto 1 1 + Ponto 2 1 + Ponto 3 1 + Conclusão 1 = 5 min**; nota criada. |
| Demo 4 min | “Criar esboço do zero” → Demonstração livre 4 min; chat gerou `budget=2048`, `finish=stop`, `hasCard=true`, 309 palavras, `retries=0`. |
| Exportação | ✅ “Salvar como PDF” gerou `N 192 — Você está no caminho para a vida eterna.PDF`: 1 página A4, 17.803 bytes, texto e formatação presentes (`pdfinfo`/`pdftotext`); “Compartilhar” abriu a share sheet com o `.html`; sem crash. |
| TTS | ✅ Piper offline tocou a resposta no chat; botão alternou para “Parar leitura” e voltou para “Ouvir”; áudio ativo no AudioFlinger. Troca de voz/background não testados. |
| STT | ⚠️ O serviço do sistema falhou no A34 (`SpeechRecognizer error=10`); o fallback mostrou “Serviço de voz interrompeu”, preservou a transcrição e ofereceu Retomar/Parar. Ao parar, abriu “Resultado do ensaio” com “Pouca fala capturada”, “Áudio não foi gravado” e aviso de que o serviço do sistema pode usar rede. Sem crash; fala humana não validada. |
| Refs | ⚠️ `w25.07` importado e Biblioteca mostra “No acervo”; pergunta direta no chat ainda registrou `refs=0`/`inventory_chars=0`. `lmd` continua “Faltando” com ação de download. Inventário/chat de refs fica para o C2. |

## CI dry-run — 2026-10-26

| Item | Resultado |
|---|---|
| Run | [40418833924](https://github.com/guilhermemellojw/better-talker/actions/runs/40418833924) — **success** |
| Etapas | secrets, testes, `lintRelease`, `assembleRelease`, `apksigner`, SHA-256 e upload — todas verdes |
| APK da CI | SHA-256 `dd49abf1b289737e3fc05007c1d29e34a14e68f06ef78ff689dce425a8e08781` |
| Certificado | mesmo certificado de release (`ad1b0a30…62c33bba4`) |
| Download do artefato | **bloqueado nesta máquina**: o Azure Blob de artefatos está a ~6 KB/s (97 MB); não deu para baixar/instalar o APK da CI |
| Secrets | quatro `BT_*` + `GOOGLE_SERVICES_JSON` aparecem em `gh secret list`; `github-secrets.txt` apagado |

## Gaps e próximos passos

- **Backup:** o arquivo cifrado está em `/home/guilherme/chave_apk_better_talker/.better-talker-backups/`; falta confirmar as duas cópias offline e o gerenciador de senhas.
- **CI artifact:** baixar o APK da CI por um caminho mais rápido (GitHub UI/browser ou `gh run download` em outra rede) e instalar no A34; o hash esperado é `dd49abf1…a08781`.
- **Smoke final no artefato da CI:** TTS (troca de voz/background), refs no chat, STT (limitação do serviço do sistema) e aparelho limpo de um amigo.
- **Refs:** `w25.07` está no acervo, mas o chat ainda registra `refs=0`; isso é C2.
- C2, card-first, 45 min monolítico, `lmd` literal e sync continuam fora deste release.
