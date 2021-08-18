/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.incremental

import org.jetbrains.kotlin.incremental.ClasspathChanges
import org.jetbrains.kotlin.incremental.LookupSymbol
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.resolve.sam.SAM_LOOKUP_NAME
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

abstract class ClasspathChangesComputerTest : ClasspathSnapshotTestCommon() {

    protected abstract val testSourceFile: ChangeableTestSourceFile

    private lateinit var originalSnapshot: ClassSnapshot

    @Before
    fun setUp() {
        originalSnapshot = testSourceFile.compileAndSnapshot()
    }

    // TODO Add more test cases:
    //   - private/non-private fields
    //   - inline functions
    //   - changing supertype by adding somethings that changes/does not change the supertype ABI
    //   - adding an annotation

    @Test
    fun testCollectClassChanges_changedPublicMethodSignature() {
        val updatedSnapshot = testSourceFile.changePublicMethodSignature().compileAndSnapshot()
        val classpathChanges = computeClassChanges(updatedSnapshot, originalSnapshot)

        val testClass = testSourceFile.sourceFile.unixStyleRelativePath.substringBeforeLast('.').replace('/', '.')
        assertEquals(
            Changes(
                lookupSymbols = listOf(
                    LookupSymbol(name = SAM_LOOKUP_NAME.asString(), scope = testClass),
                    LookupSymbol(name = "changedPublicMethod", scope = testClass),
                    LookupSymbol(name = "publicMethod", scope = testClass)
                ),
                fqNames = listOf(FqName(testClass)),
            ),
            classpathChanges
        )
    }

    @Test
    fun testCollectClassChanges_changedMethodImplementation() {
        val updatedSnapshot = testSourceFile.changeMethodImplementation().compileAndSnapshot()
        val classpathChanges = computeClassChanges(updatedSnapshot, originalSnapshot)

        assertEquals(Changes(emptyList(), emptyList()), classpathChanges)
    }

    private data class Changes(val lookupSymbols: List<LookupSymbol>, val fqNames: List<FqName>)

    private fun computeClassChanges(current: ClassSnapshot, previous: ClassSnapshot): Changes {
        val currentClasspathSnapshot =
            ClasspathSnapshot(listOf(ClasspathEntrySnapshot(LinkedHashMap<String, ClassSnapshot>(1).also { it["a"] = current })))
        val previousClasspathSnapshot =
            ClasspathSnapshot(listOf(ClasspathEntrySnapshot(LinkedHashMap<String, ClassSnapshot>(1).also { it["a"] = previous })))
        val classpathChanges = ClasspathChangesComputer.getChanges(currentClasspathSnapshot, previousClasspathSnapshot) as ClasspathChanges.Available
        return Changes(classpathChanges.lookupSymbols, classpathChanges.fqNames)
    }

    @Test
    fun testA() {
        val sdkDir = "/usr/local/google/home/hungnv/Setup/Android/Sdk"
        val coreLambda = File("$sdkDir/build-tools/30.0.3/core-lambda-stubs.jar")
        val a = ZipFile(coreLambda).use {
            return@use listOf(
                it.getInputStream(it.getEntry("java/lang/invoke/MethodHandles.class")).readBytes(),
                it.getInputStream(it.getEntry("java/lang/invoke/MethodHandles\$Lookup.class")).readBytes(),
            )
        }
        val androidJar = File("$sdkDir/platforms/android-30/android.jar")
        val b = ZipFile(androidJar).use {
            return@use listOf(
                it.getInputStream(it.getEntry("java/lang/invoke/MethodHandles.class")).readBytes(),
                it.getInputStream(it.getEntry("java/lang/invoke/MethodHandles\$Lookup.class")).readBytes(),
            )
        }

        val aSnap = ClassSnapshotter.snapshot(
            listOf(
                ClassFileWithContents(ClassFile(coreLambda, "java/lang/invoke/MethodHandles.class"), a.get(0)),
                ClassFileWithContents(ClassFile(coreLambda, "java/lang/invoke/MethodHandles\$Lookup.class"), a.get(1)),
            )
        )
        val bSnap = ClassSnapshotter.snapshot(
            listOf(
                ClassFileWithContents(ClassFile(androidJar, "java/lang/invoke/MethodHandles.class"), b.get(0)),
                ClassFileWithContents(ClassFile(androidJar, "java/lang/invoke/MethodHandles\$Lookup.class"), b.get(1)),
            )
        )

        aSnap.forEachIndexed { index, classSnapshot ->
            println(computeClassChanges(classSnapshot, bSnap.get(index)))
        }
    }
}

class KotlinClassesClasspathChangesComputerTest : ClasspathChangesComputerTest() {
    override val testSourceFile = SimpleKotlinClass(tmpDir)
}

class JavaClassesClasspathChangesComputerTest : ClasspathChangesComputerTest() {
    override val testSourceFile = SimpleJavaClass(tmpDir)
}


