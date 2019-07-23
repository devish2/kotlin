/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ide.konan

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.impl.PsiFileFactoryImpl
import com.intellij.psi.stubs.PsiFileStub
import com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.ide.konan.decompiler.KotlinNativeLoadingMetadataCache
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.decompiler.textBuilder.LoggingErrorReporter
import org.jetbrains.kotlin.konan.file.File as KFile
import org.jetbrains.kotlin.konan.library.KonanLibraryForIde
import org.jetbrains.kotlin.konan.library.KonanLibraryMetadataLoader
import org.jetbrains.kotlin.library.KLIB_FILE_EXTENSION
import org.jetbrains.kotlin.library.KLIB_MANIFEST_FILE_NAME
import org.jetbrains.kotlin.library.KLIB_PROPERTY_UNIQUE_NAME
import org.jetbrains.kotlin.metadata.konan.KonanProtoBuf
import org.jetbrains.kotlin.psi.stubs.elements.KtStubElementTypes
import java.io.IOException
import java.util.*

fun createFileStub(project: Project, text: String): PsiFileStub<*> {
    val virtualFile = LightVirtualFile("dummy.kt", KotlinFileType.INSTANCE, text)
    virtualFile.language = KotlinLanguage.INSTANCE
    SingleRootFileViewProvider.doNotCheckFileSizeLimit(virtualFile)

    val psiFileFactory = PsiFileFactory.getInstance(project) as PsiFileFactoryImpl
    val file = psiFileFactory.trySetupPsiForFile(virtualFile, KotlinLanguage.INSTANCE, false, false)!!
    return KtStubElementTypes.FILE.builder.buildStubTree(file) as PsiFileStub<*>
}

fun createLoggingErrorReporter(log: Logger) = LoggingErrorReporter(log)

internal val VirtualFile.isKonanLibraryRoot: Boolean
    get() {
        val extension = extension
        if (!extension.isNullOrEmpty() && extension != KLIB_FILE_EXTENSION) return false

        val manifestFile = findChild(KLIB_MANIFEST_FILE_NAME)?.takeIf { !it.isDirectory } ?: return false

        val manifestProperties = try {
            manifestFile.inputStream.use { Properties().apply { load(it) } }
        } catch (_: IOException) {
            return false
        }

        return manifestProperties.containsKey(KLIB_PROPERTY_UNIQUE_NAME)
    }

internal object CachingIdeKonanLibraryMetadataLoader : KonanLibraryMetadataLoader() {
    override fun loadModuleHeader(
        library: KonanLibraryForIde
    ): KonanProtoBuf.LinkDataLibrary {
        val virtualFile = getVirtualFile(library, library.metadataLayout.moduleHeaderFile)
        return cache.getCachedModuleHeader(virtualFile)
    }

    override fun loadPackageFragment(
        library: KonanLibraryForIde,
        packageFqName: String,
        partName: String
    ): KonanProtoBuf.LinkDataPackageFragment {
        val virtualFile = getVirtualFile(library, library.metadataLayout.packageFragmentFile(packageFqName, partName))
        return cache.getCachedPackageFragment(virtualFile)
    }

    private fun getVirtualFile(library: KonanLibraryForIde, file: KFile): VirtualFile =
        if (library.metadataLayout.isZipped) asJarFileSystemFile(library.libraryFile, file) else asLocalFile(file)

    private fun asJarFileSystemFile(jarFile: KFile, localFile: KFile): VirtualFile {
        val fullPath = jarFile.absolutePath + "!" + localFile.absolutePath
        return StandardFileSystems.jar().findFileByPath(fullPath) ?: error("File not found: $fullPath")
    }

    private fun asLocalFile(localFile: KFile): VirtualFile {
        val fullPath = localFile.absolutePath
        return StandardFileSystems.local().findFileByPath(fullPath) ?: error("File not found: $fullPath")
    }

    private val cache
        get() = KotlinNativeLoadingMetadataCache.getInstance()
}
