// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.core.script

import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdkType
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.NonClasspathDirectoriesScope.compose
import org.jetbrains.kotlin.idea.base.util.runReadActionInSmartMode
import org.jetbrains.kotlin.idea.core.script.ScriptConfigurationManager.Companion.toVfsRoots
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.ScriptDependenciesProvider
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.io.path.pathString
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.jdkHome
import kotlin.script.experimental.jvm.jvm
import kotlin.time.measureTime

/**
 * Plain stateful implementation of `ScriptDependenciesProvider` that holds pre-refined scripts configuration data.
 *
 * Does not support realtime per-file updates.
 * For full batch-style update call [reloadConfigurations]
 */
class K2ScriptDependenciesProvider(project: Project) : ScriptDependenciesProvider(project), ScriptDependencyAware {
    private val lock = ReentrantReadWriteLock()

    private var allClasses: Set<VirtualFile> = setOf()
    private var allSources: Set<VirtualFile> = setOf()
    private var sdks: Map<Path, Sdk> = mapOf()

    override fun getAllScriptsDependenciesClassFiles(): Set<VirtualFile> = lock.read { allClasses }
    override fun getAllScriptDependenciesSources(): Set<VirtualFile> = lock.read { allSources }

    override fun getAllScriptDependenciesSourcesScope(): GlobalSearchScope = lock.read { compose(allSources.toList() + getSdkSources()) }
    override fun getAllScriptsDependenciesClassFilesScope(): GlobalSearchScope =
        lock.read { compose(allClasses.toList() + getSdkClasses()) }

    override fun getScriptDependenciesClassFiles(virtualFile: VirtualFile): List<VirtualFile> {
        val dependencies = configurationsByFile[virtualFile]?.valueOrNull()?.dependenciesClassPath ?: return emptyList()
        return toVfsRoots(dependencies)
    }

    override fun getScriptSdk(virtualFile: VirtualFile): Sdk? = lock.read {
        val configurationWrapper = getConfiguration(virtualFile)?.valueOrNull()
        return configurationWrapper?.javaHome?.let { sdks[it.toPath()] } ?: getDefaultSdk()
    }

    override fun getFirstScriptsSdk(): Sdk? = lock.read { sdks.values.firstOrNull() }

    private fun getSdkSources(): List<VirtualFile> = sdks.values.flatMap { it.rootProvider.getFiles(OrderRootType.SOURCES).toList() }
    private fun getSdkClasses(): List<VirtualFile> = sdks.values.flatMap { it.rootProvider.getFiles(OrderRootType.CLASSES).toList() }

    override fun getScriptDependenciesClassFilesScope(virtualFile: VirtualFile): GlobalSearchScope = lock.read {
        val configurationWrapper = getConfiguration(virtualFile)?.valueOrNull() ?: return GlobalSearchScope.EMPTY_SCOPE
        val roots = toVfsRoots(configurationWrapper.dependenciesClassPath)

        val sdk = configurationWrapper.javaHome?.let { sdks[it.toPath()] }?.rootProvider?.getFiles(OrderRootType.CLASSES)?.toList()
            ?: emptyList<VirtualFile>()
        return compose(roots + sdk)
    }

    private fun getDefaultSdk(): Sdk? {
        val projectSdk = ProjectRootManager.getInstance(project).projectSdk?.takeIf { it.canBeUsedForScript() }
        if (projectSdk != null) return projectSdk

        return ProjectJdkTable.getInstance().allJdks.find { it.canBeUsedForScript() }
    }

    private fun Sdk.canBeUsedForScript() = sdkType is JavaSdkType && hasValidClassPathRoots()

    private fun Sdk.hasValidClassPathRoots(): Boolean {
        val rootClasses = rootProvider.getFiles(OrderRootType.CLASSES)
        return rootClasses.isNotEmpty() && rootClasses.all { it.isValid }
    }

    private val configurationsByFile = ConcurrentHashMap<VirtualFile, ResultWithDiagnostics<ScriptCompilationConfigurationWrapper>>()

    fun addConfiguration(script: SourceCode): ScriptCompilationConfigurationResult {
        val definition = findScriptDefinition(project, script)
        val virtualFile = script.getVirtualFile(definition)

        val configuration = project.runReadActionInSmartMode {
            refineScriptCompilationConfiguration(script, definition, project, definition.compilationConfiguration)
        }

        configurationsByFile[virtualFile] = configuration

        return configuration
    }

    fun reloadConfigurations(scripts: Set<ScriptModel>, javaHome: String?) {
        val classes = mutableSetOf<VirtualFile>()
        val sources = mutableSetOf<VirtualFile>()
        val sdks = mutableMapOf<Path, Sdk>()

        var counter = 0

        val duration = measureTime {
            for (script in scripts) {

                val sourceCode = VirtualFileScriptSource(script.virtualFile)
                val definition = findScriptDefinition(project, sourceCode)

                val configuration = definition.compilationConfiguration.with {
                    javaHome?.let {
                        jvm.jdkHome(Path.of(javaHome).toFile())
                    }
                    defaultImports(script.imports)
                    dependencies(JvmDependency(script.classPath.map { File(it) }))
                    ide.dependenciesSources(JvmDependency(script.sourcePath.map { File(it) }))
                }.adjustByDefinition(definition)

                val updatedConfiguration = project.runReadActionInSmartMode {
                    refineScriptCompilationConfiguration(sourceCode, definition, project, configuration)
                }
                configurationsByFile[script.virtualFile] = updatedConfiguration

                val configurationWrapper = updatedConfiguration.valueOrNull() ?: continue

                classes.addAll(toVfsRoots(configurationWrapper.dependenciesClassPath))
                sources.addAll(toVfsRoots(configurationWrapper.dependenciesSources))
                configurationWrapper.javaHome?.toPath()?.let {
                    sdks[it] = ExternalSystemJdkUtil.lookupJdkByPath(it.pathString)
                }

                counter++
            }

            lock.write {
                allClasses = classes
                allSources = sources
                this.sdks = sdks
            }
        }

        scriptingDebugLog { "duration=$duration finished refinement for $counter scripts" }
    }

    fun getConfiguration(virtualFile: VirtualFile): ResultWithDiagnostics<ScriptCompilationConfigurationWrapper>? =
        configurationsByFile[virtualFile]

    override fun getScriptConfigurationResult(file: KtFile): ScriptCompilationConfigurationResult? =
        configurationsByFile[file.alwaysVirtualFile]

    private val KtFile.alwaysVirtualFile: VirtualFile get() = originalFile.virtualFile ?: viewProvider.virtualFile

    companion object {
        fun getInstance(project: Project): K2ScriptDependenciesProvider =
            project.service<ScriptDependenciesProvider>() as K2ScriptDependenciesProvider

        fun getInstanceIfCreated(project: Project): K2ScriptDependenciesProvider? =
            project.serviceIfCreated<ScriptDependenciesProvider>() as? K2ScriptDependenciesProvider
    }
}

data class ScriptModel(
    val virtualFile: VirtualFile,
    val classPath: List<String> = listOf(),
    val sourcePath: List<String> = listOf(),
    val imports: List<String> = listOf(),
)