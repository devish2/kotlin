/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.fir.analysis.cfa.TraverseDirection
import org.jetbrains.kotlin.fir.analysis.cfa.traverse
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.isOverride
import org.jetbrains.kotlin.fir.declarations.utils.isTailRec
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.arguments
import org.jetbrains.kotlin.fir.expressions.impl.FirNoReceiverExpression
import org.jetbrains.kotlin.fir.expressions.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.*
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol

object FirTailrecFunctionChecker : FirSimpleFunctionChecker() {
    override fun check(declaration: FirSimpleFunction, context: CheckerContext, reporter: DiagnosticReporter) {
        if (!declaration.isTailRec) return
        if (!declaration.isEffectivelyFinal(context, treatEnumAsFinal = false)) {
            reporter.reportOn(declaration.source, FirErrors.TAILREC_ON_VIRTUAL_MEMBER_ERROR, context)
        }
        val graph = declaration.controlFlowGraphReference?.controlFlowGraph ?: return
        var tailrecCount = 0
        graph.traverse(TraverseDirection.Forward, object : ControlFlowGraphVisitorVoid() {
            override fun visitNode(node: CFGNode<*>) {}
            override fun visitFunctionCallNode(node: FunctionCallNode) {
                val functionCall = node.fir
                val resolvedSymbol = functionCall.calleeReference.toResolvedCallableSymbol() as? FirNamedFunctionSymbol ?: return
                if (resolvedSymbol != declaration.symbol) return
                if (functionCall.arguments.size != resolvedSymbol.valueParameterSymbols.size && resolvedSymbol.isOverride) {
                    // Overridden functions using default arguments at tail call are not included: KT-4285
                    reporter.reportOn(functionCall.source, FirErrors.NON_TAIL_RECURSIVE_CALL, context)
                    return
                }
                val dispatchReceiver = functionCall.dispatchReceiver
                if (dispatchReceiver !is FirThisReceiverExpression &&
                    dispatchReceiver !is FirNoReceiverExpression &&
                    ((dispatchReceiver as? FirResolvedQualifier)?.symbol as? FirRegularClassSymbol?)?.classKind?.isSingleton != true
                ) {
                    // A tailrec call does not support changing dispatchers. This check does a simple `this` check and it's sufficient.
                    // Specifically, there is no need to check if `this` in here references the same `this` made available from
                    // `declaration`. This is because if `this` is not labeled, then it references the inner most this receiver. If the
                    // innermost scope is not the `declaration` body, then follow-up checks on following nodes would report there to be
                    // more instructions, which would then make this call non-tailrec.
                    // If `this` is labeled, then one of the following is possible.
                    //  1. the call is in some context that has additional implicit this declared. But this can only happen if the call is
                    //     placed inside some extension lambda, which would be covered by a later check.
                    //  2. `declaration` is a member function in a local class and the receiver is a labeled this pointing to the outer
                    //     non-local class. In this case, the resolved symbol cannot be the same as the symbol of `declaration`. So there
                    //     is no need to report anything.
                    reporter.reportOn(functionCall.source, FirErrors.NON_TAIL_RECURSIVE_CALL, context)
                    return
                }
                when (node.getIllegalFollowingNodeStatusForTailrec(declaration)) {
                    IllegalFollowingNodeStatusForTailRec.NONE -> {
                        if (!node.isDead) {
                            tailrecCount++
                        }
                    }
                    IllegalFollowingNodeStatusForTailRec.IN_TRY -> {
                        reporter.reportOn(functionCall.source, FirErrors.TAIL_RECURSION_IN_TRY_IS_NOT_SUPPORTED, context)
                    }
                    IllegalFollowingNodeStatusForTailRec.MORE_INSTRUCTIONS -> {
                        reporter.reportOn(functionCall.source, FirErrors.NON_TAIL_RECURSIVE_CALL, context)
                    }
                }
            }
        })
        if (tailrecCount == 0) {
            reporter.reportOn(declaration.source, FirErrors.NO_TAIL_CALLS_FOUND, context)
        }
    }

    enum class IllegalFollowingNodeStatusForTailRec {
        NONE,
        IN_TRY,
        MORE_INSTRUCTIONS
    }

    private fun CFGNode<*>.getIllegalFollowingNodeStatusForTailrec(declaration: FirSimpleFunction): IllegalFollowingNodeStatusForTailRec {
        for (next in followingNodes) {
            val edge = outgoingEdges.getValue(next)
            if (!edge.kind.usedInCfa || edge.kind.isDead) continue
            if (edge.kind.isBack) return IllegalFollowingNodeStatusForTailRec.MORE_INSTRUCTIONS
            val nextStatus = when (next) {
                is TryMainBlockExitNode, is CatchClauseExitNode, is FinallyBlockExitNode -> return IllegalFollowingNodeStatusForTailRec.IN_TRY
                // If exiting another function, then it means this call is inside a nested local function, in which case, it's not a tailrec call.
                is FunctionExitNode -> if (next.fir == declaration) IllegalFollowingNodeStatusForTailRec.NONE else return IllegalFollowingNodeStatusForTailRec.MORE_INSTRUCTIONS
                is JumpNode, is BinaryAndExitNode, is BinaryOrExitNode, is WhenBranchResultExitNode, is WhenExitNode, is BlockExitNode -> next.getIllegalFollowingNodeStatusForTailrec(
                    declaration
                )
                else -> return IllegalFollowingNodeStatusForTailRec.MORE_INSTRUCTIONS
            }
            if (nextStatus != IllegalFollowingNodeStatusForTailRec.NONE) return nextStatus
        }
        return IllegalFollowingNodeStatusForTailRec.NONE
    }
}