/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.scopes

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.providers.createDeclarationProvider
import org.jetbrains.kotlin.analysis.providers.createPackageProvider
import org.jetbrains.kotlin.fir.scopes.impl.FirPackageMemberScope
import org.jetbrains.kotlin.idea.frontend.api.tokens.ValidityToken
import org.jetbrains.kotlin.idea.frontend.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.idea.frontend.api.fir.components.KtFirScopeProvider
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.lazyThreadUnsafeWeakRef
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.weakRef
import org.jetbrains.kotlin.idea.frontend.api.scopes.KtPackageScope
import org.jetbrains.kotlin.idea.frontend.api.scopes.KtScopeNameFilter
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtCallableSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtClassifierSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtPackageSymbol
import org.jetbrains.kotlin.idea.frontend.api.withValidityAssertion
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.isJvm

internal class KtFirPackageScope(
    override val fqName: FqName,
    private val project: Project,
    private val builder: KtSymbolByFirBuilder,
    _scopeProvider: KtFirScopeProvider,
    override val token: ValidityToken,
    private val searchScope: GlobalSearchScope,
    private val targetPlatform: TargetPlatform,
) : KtPackageScope {
    private val scopeProvider by weakRef(_scopeProvider)
    private val declarationsProvider = project.createDeclarationProvider(searchScope)
    private val packageProvider = project.createPackageProvider(searchScope)

    private val firScope: FirPackageMemberScope by lazyThreadUnsafeWeakRef {
        val scope = FirPackageMemberScope(fqName, builder.rootSession)
        scopeProvider.registerScope(scope)
        scope
    }

    override fun getPossibleCallableNames() = withValidityAssertion {
        hashSetOf<Name>().apply {
            addAll(declarationsProvider.getFunctionsNamesInPackage(fqName))
            addAll(declarationsProvider.getPropertyNamesInPackage(fqName))
        }
    }

    override fun getPossibleClassifierNames(): Set<Name> = withValidityAssertion {
        hashSetOf<Name>().apply {
            addAll(declarationsProvider.getClassNamesInPackage(fqName))
            addAll(declarationsProvider.getTypeAliasNamesInPackage(fqName))

            JavaPsiFacade.getInstance(project)
                .findPackage(fqName.asString())
                ?.getClasses(searchScope)
                ?.mapNotNullTo(this) { it.name?.let(Name::identifier) }
        }
    }

    override fun getCallableSymbols(nameFilter: KtScopeNameFilter): Sequence<KtCallableSymbol> = withValidityAssertion {
        firScope.getCallableSymbols(getPossibleCallableNames().filter(nameFilter), builder)
    }

    override fun getClassifierSymbols(nameFilter: KtScopeNameFilter): Sequence<KtClassifierSymbol> = withValidityAssertion {
        firScope.getClassifierSymbols(getPossibleClassifierNames().filter(nameFilter), builder)
    }

    override fun getPackageSymbols(nameFilter: KtScopeNameFilter): Sequence<KtPackageSymbol> = withValidityAssertion {
        sequence {
            if (targetPlatform.isJvm()) {
                val javaPackage = JavaPsiFacade.getInstance(project).findPackage(fqName.asString())
                if (javaPackage != null) {
                    for (psiPackage in javaPackage.getSubPackages(searchScope)) {
                        val fqName = FqName(psiPackage.qualifiedName)
                        if (nameFilter(fqName.shortName())) {
                            yield(builder.createPackageSymbol(fqName))
                        }
                    }
                }
            }
            packageProvider.getKotlinSubPackageFqNames(fqName).forEach {
                if (nameFilter(it)) {
                    yield(builder.createPackageSymbol(fqName.child(it)))
                }
            }
        }
    }
}
