/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.ast


sealed class WasmArgument {
    object None : WasmArgument()
    class DeclarationReference(val name: String) : WasmArgument()
    class LiteralValue<T : Number>(val value: T) : WasmArgument()
}

sealed class WasmInstruction(
    val mnemonic: String,
    val argument: WasmArgument = WasmArgument.None,
    val operands: List<WasmInstruction> = emptyList()
)

class WasmSimpleInstruction(mnemonic: String, operands: List<WasmInstruction>) :
    WasmInstruction(mnemonic, operands = operands)

class WasmNop : WasmInstruction("nop")

class WasmReturn(values: List<WasmInstruction>) :
    WasmInstruction("return", operands = values)

class WasmDrop(instructions: List<WasmInstruction>) :
    WasmInstruction("drop", operands = instructions)

class WasmCall(name: String, operands: List<WasmInstruction>) :
    WasmInstruction("call", WasmArgument.DeclarationReference(name), operands)

class WasmGetLocal(name: String) :
    WasmInstruction("get_local", WasmArgument.DeclarationReference(name))

class WasmGetGlobal(name: String) :
    WasmInstruction("get_global", WasmArgument.DeclarationReference(name))

class WasmSetGlobal(name: String, value: WasmInstruction) :
    WasmInstruction("set_global", WasmArgument.DeclarationReference(name), listOf(value))

class WasmSetLocal(name: String, value: WasmInstruction) :
    WasmInstruction("set_local", WasmArgument.DeclarationReference(name), listOf(value))

class WasmIf(condition: WasmInstruction, thenInstructions: WasmThen?, elseInstruction: WasmElse?) :
    WasmInstruction("if", operands = listOfNotNull(condition, thenInstructions, elseInstruction))

class WasmThen(inst: WasmInstruction) :
    WasmInstruction("then", operands = listOf(inst))

class WasmElse(inst: WasmInstruction) :
    WasmInstruction("else", operands = listOf(inst))

class WasmBlock(instructions: List<WasmInstruction>) :
    WasmInstruction("block", operands = instructions)

sealed class WasmConst<KotlinType : Number, WasmType : WasmValueType>(value: KotlinType, type: WasmType) :
    WasmInstruction(type.mnemonic + ".const", WasmArgument.LiteralValue<KotlinType>(value))

class WasmI32Const(value: Int) : WasmConst<Int, WasmI32>(value, WasmI32)
class WasmI64Const(value: Long) : WasmConst<Long, WasmI64>(value, WasmI64)
class WasmF32Const(value: Float) : WasmConst<Float, WasmF32>(value, WasmF32)
class WasmF64Const(value: Double) : WasmConst<Double, WasmF64>(value, WasmF64)
