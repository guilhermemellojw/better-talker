package com.bettertalker.app.data.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.bettertalker.app.data.repo.DownloadCandidate

/**
 * Phase 3 — Scanner recursivo de árvore SAF (Downloads).
 *
 * Varre a árvore escolhida pelo usuário via ACTION_OPEN_DOCUMENT_TREE,
 * coletando DownloadCandidate. Filtra por extensão (detectKind) e ignora
 * arquivos .part/.crdownload e arquivos muito grandes.
 *
 * Pure-ish: a lógica de filtragem é testável; a varredura real usa DocumentFile.
 */
class DownloadsTreeScanner(
    private val context: Context,
    private val treeUri: Uri,
) {
    /** Varre a árvore recursivamente, coletando DownloadCandidate. */
    fun scan(maxDepth: Int = 4, maxFiles: Int = 200): List<DownloadCandidate> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val result = mutableListOf<DownloadCandidate>()
        scanRecursive(root, 0, maxDepth, maxFiles, result)
        return result
    }

    private fun scanRecursive(
        dir: DocumentFile,
        depth: Int,
        maxDepth: Int,
        maxFiles: Int,
        out: MutableList<DownloadCandidate>,
    ) {
        if (depth > maxDepth) return
        if (out.size >= maxFiles) return
        for (child in dir.listFiles()) {
            if (out.size >= maxFiles) break
            if (child.isDirectory) {
                scanRecursive(child, depth + 1, maxDepth, maxFiles, out)
            } else {
                val name = child.name.orEmpty()
                if (name.isBlank()) continue
                if (name.endsWith(".part", ignoreCase = true)) continue
                if (name.endsWith(".crdownload", ignoreCase = true)) continue
                if (detectKind(name) == DocKind.UNSUPPORTED) continue
                if (child.length() > ImportLimits.LIBRARY_BYTES) continue
                out += DownloadCandidate(
                    uriString = child.uri.toString(),
                    name = name,
                    sizeBytes = child.length(),
                )
            }
        }
    }

    companion object {
        /**
         * Filtragem pura de candidatos (testável sem DocumentFile).
         * Remove .part/.crdownload, não suportados e muito grandes.
         */
        fun filterCandidates(
            raw: List<Triple<String, String, Long>>, // (uriString, name, sizeBytes)
        ): List<DownloadCandidate> {
            return raw.mapNotNull { (uri, name, size) ->
                if (name.isBlank()) return@mapNotNull null
                if (name.endsWith(".part", ignoreCase = true)) return@mapNotNull null
                if (name.endsWith(".crdownload", ignoreCase = true)) return@mapNotNull null
                if (detectKind(name) == DocKind.UNSUPPORTED) return@mapNotNull null
                if (size > ImportLimits.LIBRARY_BYTES) return@mapNotNull null
                DownloadCandidate(uri, name, size)
            }
        }
    }
}
