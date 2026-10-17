# Better Talker — Visão v1.2

## O que É
Ferramenta Android nativa para preparação e apresentação de discursos
bíblicos, focada no contexto das Testemunhas de Jeová.

## Fluxo principal
1. Abrir/importar esboço (ex.: S-34) ou começar do zero
2. Escrever no editor multi-seção WYSIWYG (autosave)
3. Pedir ajuda ao chat DeepSeek (BYOD, chave do usuário)
4. Exportar (HTML/PDF) ou apresentar no Ensaio (TTS/STT)
5. Sync opcional via Firebase

## O que NÃO É (v1.2)
- NÃO é app web / não tem build Capacitor/Vite
- NÃO é multi-LLM — apenas DeepSeek
- NÃO tem folders/tags/cores/pin na Home (organização: lista simples)
- NÃO é rede social / não tem feed / não tem comentários

## Decisões de escopo (2024-Q4)
- Web v1: REMOVIDO
- Multi-LLM (Gemini/Groq/Qwen/Gemma/Fallback/CircuitBreaker): REMOVIDO (DeepSeek-only)
- Folders/tags/cores/pin: A REMOVER DA UI (schema Room dormente)
- Ensaio + TTS/STT: MANTIDO
- Firebase sync: MANTIDO
- Export PDF/HTML: MANTIDO
