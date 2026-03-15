package com.codeintel.mcpserver.util

import com.codeintel.mcpserver.errors.McpErrorCode
import com.codeintel.mcpserver.errors.ToolException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

object ProjectUtils {

    fun findFile(project: Project, relativePath: String): VirtualFile {
        val basePath = project.basePath ?: throw ToolException(
            McpErrorCode.PSI_ERROR,
            mapOf("reason" to "Project base path is null")
        )
        return VirtualFileManager.getInstance().findFileByUrl("file://$basePath/$relativePath")
            ?: throw ToolException(
                McpErrorCode.SYMBOL_NOT_FOUND,
                mapOf("file" to relativePath, "reason" to "File not found")
            )
    }

    fun toRelativePath(project: Project, file: VirtualFile): String {
        val basePath = project.basePath ?: return file.path
        return if (file.path.startsWith(basePath)) {
            file.path.removePrefix(basePath).removePrefix("/")
        } else {
            file.path
        }
    }

    fun lineColumnToOffset(psiFile: PsiFile, line: Int, column: Int): Int {
        val document = PsiDocumentManager.getInstance(psiFile.project).getDocument(psiFile)
            ?: throw ToolException(
                McpErrorCode.PSI_ERROR,
                mapOf("reason" to "Cannot get document for file")
            )
        if (line < 1 || line > document.lineCount) {
            throw ToolException(
                McpErrorCode.SYMBOL_NOT_FOUND,
                mapOf("line" to line.toString(), "reason" to "Line out of range (1..${document.lineCount})")
            )
        }
        val lineStartOffset = document.getLineStartOffset(line - 1)
        val lineEndOffset = document.getLineEndOffset(line - 1)
        val maxColumn = lineEndOffset - lineStartOffset + 1
        if (column < 1 || column > maxColumn) {
            throw ToolException(
                McpErrorCode.SYMBOL_NOT_FOUND,
                mapOf("column" to column.toString(), "reason" to "Column out of range (1..$maxColumn)")
            )
        }
        return lineStartOffset + column - 1
    }

    fun getPsiFile(project: Project, virtualFile: VirtualFile): PsiFile {
        return PsiManager.getInstance(project).findFile(virtualFile)
            ?: throw ToolException(
                McpErrorCode.PSI_ERROR,
                mapOf("reason" to "Cannot find PSI file for ${virtualFile.path}")
            )
    }
}
