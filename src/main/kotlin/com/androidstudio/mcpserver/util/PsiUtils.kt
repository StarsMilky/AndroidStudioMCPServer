package com.androidstudio.mcpserver.util

import com.androidstudio.mcpserver.errors.McpErrorCode
import com.androidstudio.mcpserver.errors.ToolException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import java.util.concurrent.Callable

object PsiUtils {

    /**
     * Fast-fail read action: throws INDEXING_IN_PROGRESS if IDE is indexing.
     * Use for high-frequency, low-latency tools (resolve_symbol, find_references, get_scope).
     */
    fun <T> readAction(project: Project, action: () -> T): T {
        if (DumbService.isDumb(project)) {
            throw ToolException(McpErrorCode.INDEXING_IN_PROGRESS)
        }
        return ApplicationManager.getApplication().runReadAction(Computable { action() })
    }

    /**
     * Non-blocking smart read action: waits for smart mode (indexing complete) before executing.
     * Use for analysis tools that need complete index (refactor, query_project, etc.).
     * Safe to call from background threads (handle() context).
     */
    fun <T> smartReadAction(project: Project, action: () -> T): T {
        return ReadAction.nonBlocking(Callable { action() })
            .inSmartMode(project)
            .executeSynchronously()
    }
}
