# Validação no A34 — release 2.8.1 (A1/A2)

**Data:** 2026-10-26
**Aparelho:** Samsung Galaxy A34 (Android 16)
**APK:** release assinado 2.8.1 / versionCode 26
**SHA-256 do APK local (não é o artefato da CI):** `ca8a42a6b2fe1ca93d51f80f9c65c493af249bad1d4c65e69352852fc40e3cae`
**CI dry-run:** run [40418833924](https://github.com/guilhermemellojw/better-talker/actions/runs/40418833924) — success; APK da CI com SHA-256 `dd49abf1b289737e3fc05007c1d29e34a14e68f06ef78ff689dce425a8e08781` e mesmo certificado de release.
**Release publicado:** [v2.8.1](https://github.com/guilhermemellojw/better-talker/releases/tag/v2.8.1) — SHA-256 `1397ca64779d6ecc102c615f147345a9b70a65c483601d3dc3e21966cfa93133`.
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
| Exportação | ✅ “Salvar como PDF” gerou `N 192 — Você está no caminho para a vida eterna.PDF` (local) e `…(1).PDF` no APK da CI: 1 página A4, 17.803 bytes, texto e formatação presentes (`pdfinfo`/`pdftotext`); “Compartilhar” abriu a share sheet com o `.html`; sem crash. |
| TTS | ✅ Piper offline tocou a resposta no chat local e no APK da CI; botão alternou para “Parar leitura” e voltou para “Ouvir”; áudio continuou em background e parou ao voltar; sem crash. Troca de voz não tem seletor exposto (Piper é o padrão; fallback nativo automático). |
| STT | ⚠️ Confirmado também no APK da CI: o serviço do sistema falhou (`SpeechRecognizer error=10`); o fallback mostrou “Serviço de voz interrompeu”, preservou a transcrição e ofereceu Retomar/Parar. Ao parar, abriu “Resultado do ensaio” com “Pouca fala capturada”, “Áudio não foi gravado” e aviso de que o serviço do sistema pode usar rede. Sem crash; fala humana não validada. |
| Refs | ⚠️ `w25.07` importado e Biblioteca mostra “No acervo”; pergunta direta no chat registrou `refs=0`/`inventory_chars=0` no APK da CI. `lmd` continua “Faltando” com ação de download. Inventário/chat de refs fica para o C2. |

## CI dry-run — 2026-10-26

| Item | Resultado |
|---|---|
| Run | [40418833924](https://github.com/guilhermemellojw/better-talker/actions/runs/40418833924) — **success** |
| Etapas | secrets, testes, `lintRelease`, `assembleRelease`, `apksigner`, SHA-256 e upload — todas verdes |
| APK da CI | SHA-256 `dd49abf1b289737e3fc05007c1d29e34a14e68f06ef78ff689dce425a8e08781` |
| Certificado | mesmo certificado de release (`ad1b0a30…62c33bba4`) |
| Download do artefato | ✅ baixado em 32 ranges paralelos; zip SHA-256 `4f1fe1c8…b45b520d`; APK `dd49abf1…a08781`; instalado no A34 |
| Smoke no artefato da CI | ✅ chat real (`budget=2048/stop`), TTS play/stop/background, PDF A4 e HTML; STT `error=10` persistiu com fallback; refs=0 |
| Secrets | quatro `BT_*` + `GOOGLE_SERVICES_JSON` aparecem em `gh secret list`; `github-secrets.txt` apagado |

## Release publicado — v2.8.1

| Item | Resultado |
|---|---|
| Release | [v2.8.1](https://github.com/guilhermemellojw/better-talker/releases/tag/v2.8.1) |
| APK publicado | `app-release.apk` (102.671.973 bytes) |
| SHA-256 | `1397ca64779d6ecc102c615f147345a9b70a65c483601d3dc3e21966cfa93133` (confere com `app-release.apk.sha256`) |
| Assinatura | `CN=Better Talker, OU=Release` · SHA-256 `ad1b0a30…62c33bba4` |
| Instalação | APK do release baixado com `gh release download`, verificado e instalado no A34 sobre o build anterior (mesma assinatura) |
| Pós-instalação | 2.8.1/26 abriu; “Chave salva” persistiu; “Testar conexão” → **Online**; `bmgr backupnow` → “Backup is not allowed” |
| Dry-run vs publicado | `dd49abf1…a08781` (CI dry-run) ≠ `1397ca64…a93133` (release). O hash válido é o do asset publicado. |

## Decisão de escopo

**Opção A — v2.8.1 security-only.** O `refs=0` é limitação conhecida de C2,
não regressão do C1 (as mudanças do C1 foram de orçamento/telemetria; a
recuperação e o inventário não foram tocados). TTS/STT P0 e refs entram no
v2.8.2. O v2.8.1 é o checkpoint de segurança/distribuição.

## Gaps e próximos passos

- **Backup:** o arquivo cifrado está em `/home/guilherme/chave_apk_better_talker/.better-talker-backups/`; falta confirmar as duas cópias offline e o gerenciador de senhas.
- **CI artifact:** baixado, verificado (`dd49abf1…a08781`) e instalado no A34.
- **Smoke final:** TTS play/stop/background e PDF/HTML passaram; STT fica como limitação do serviço do sistema; refs no chat ficam para C2; falta aparelho limpo de um amigo.
- C2, card-first, 45 min monolítico, `lmd` literal e sync continuam fora deste release.
