package com.androidstudio.mcpserver.services

import com.androidstudio.mcpserver.models.args.StructuralSearchArgs
import com.androidstudio.mcpserver.models.results.SearchMatch
import com.androidstudio.mcpserver.models.results.SearchMatchResult
import com.androidstudio.mcpserver.util.PsiUtils
import com.intellij.openapi.project.Project

/**
 * Performs structural search using IntelliJ SSR API.
 * TODO: Integrate with MatchOptions/Matcher when correct API usage is confirmed.
 * For now returns stub results.
 */
object StructuralSearcher {
    fun search(project: Project, args: StructuralSearchArgs): SearchMatchResult {
        return PsiUtils.smartReadAction(project) {
            // Stub: full SSR integration requires MatchOptions.searchPattern and Matcher.processMatches
            SearchMatchResult(total = 0, matches = emptyList())
        }
    }
}
