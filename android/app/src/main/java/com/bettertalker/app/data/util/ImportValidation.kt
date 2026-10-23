package com.bettertalker.app.data.util

import java.io.File

/**
 * v2.7.3 — valida a assinatura do arquivo antes de indexar.
 *
 * Um download de página/HTML salvo com extensão `.jwpub`/`.epub` chegava ao
 * extrator e falhava com "Arquivo .jwpub inválido" (mensagem genérica). Aqui a
 * falha vira uma mensagem acionável (“veio como página”) e o anexo não é criado.
 *
 * Puro/testável: sem Android.
 */
object ImportValidation {

    /** null = assinatura compatível; string = motivo da recusa. */
    fun invalidReason(file: File, kind: DocKind): String? {
        if (!file.exists()) return "Arquivo não encontrado."
        if (file.length() == 0L) return "Arquivo vazio ou incompleto."
        val head = ByteArray(8)
        val read = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        val ascii = String(head, 0, read, Charsets.US_ASCII)
        return when (kind) {
            DocKind.PDF ->
                if (!ascii.startsWith("%PDF")) {
                    "O download não é um PDF válido (veio como página?)."
                } else null

            DocKind.EPUB, DocKind.DOCX, DocKind.JWPUB, DocKind.ZIP ->
                if (read < 2 || head[0] != 0x50.toByte() || head[1] != 0x4B.toByte()) {
                    "O download não é um arquivo ZIP/EPUB/JWPUB válido (veio como página?)."
                } else null

            DocKind.RTF ->
                if (!ascii.startsWith("{\\rtf")) "O download não é um RTF válido." else null

            DocKind.TXT -> null

            DocKind.UNSUPPORTED -> "Formato não suportado."
        }
    }
}
