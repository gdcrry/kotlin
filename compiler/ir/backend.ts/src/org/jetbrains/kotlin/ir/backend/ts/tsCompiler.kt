/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.backend.ts

import org.jetbrains.kotlin.backend.common.IrModuleDependencies
import org.jetbrains.kotlin.backend.common.linkage.issues.checkNoUnboundSymbols
import org.jetbrains.kotlin.backend.common.serialization.KotlinIrLinker
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.perfManager
import org.jetbrains.kotlin.config.phaser.PhaserState
import org.jetbrains.kotlin.ir.backend.ts.lower.moveBodilessDeclarationsToSeparatePlace
import org.jetbrains.kotlin.ir.declarations.IrFactory
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.ExternalDependenciesGenerator
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.util.PhaseType
import org.jetbrains.kotlin.util.PotentiallyIncorrectPhaseTimeMeasurement
import org.jetbrains.kotlin.util.tryMeasurePhaseTime


class LoweredIr(
    val context: TsIrBackendContext,
    val mainModule: IrModuleFragment,
    val allModules: List<IrModuleFragment>,
    val moduleFragmentToUniqueName: Map<IrModuleFragment, String>,
)

fun compile(
    mainCallArguments: List<String>?,
    modulesStructure: ModulesStructure,
    irFactory: IrFactory,
    filesToLower: Set<String>? = null,
): LoweredIr {
    val (moduleFragment: IrModuleFragment, moduleDependencies, irBuiltIns, symbolTable, deserializer) =
        loadIr(modulesStructure, irFactory, filesToLower, loadFunctionInterfacesIntoStdlib = true)
    return compileIr(
        moduleFragment = moduleFragment,
        mainModule = modulesStructure.mainModule,
        mainCallArguments = mainCallArguments,
        configuration = modulesStructure.compilerConfiguration,
        moduleDependencies = moduleDependencies,
        symbolTable = symbolTable,
        irLinker = deserializer,
    )
}

fun compileIr(
    moduleFragment: IrModuleFragment,
    mainModule: MainModule,
    mainCallArguments: List<String>?,
    configuration: CompilerConfiguration,
    moduleDependencies: IrModuleDependencies,
    symbolTable: SymbolTable,
    irLinker: KotlinIrLinker,
): LoweredIr {
    val moduleDescriptor = moduleFragment.descriptor
    val irFactory = symbolTable.irFactory
    // todo: maybe support polyfills?
    val performanceManager = configuration.perfManager
    val context = TsIrBackendContext(moduleDescriptor)


    // Load declarations referenced during `context` initialization
    val irProviders = listOf(irLinker)
    ExternalDependenciesGenerator(symbolTable, irProviders).generateUnboundSymbolsAsDependencies()

    irLinker.postProcess(inOrAfterLinkageStep = true)
    irLinker.checkNoUnboundSymbols(symbolTable, "at the end of IR linkage process")
    irLinker.clear()

    val sortedModuleDependencies = irLinker.moduleDependencyTracker.reverseTopoOrder(moduleDependencies)

    val allModules = when (mainModule) {
        is MainModule.SourceFiles -> sortedModuleDependencies.all + listOf(moduleFragment)
        is MainModule.Klib -> sortedModuleDependencies.all
    }

    allModules.forEach { module ->
        moveBodilessDeclarationsToSeparatePlace(context, module)
    }

    // todo: generate JS Tests

    @OptIn(PotentiallyIncorrectPhaseTimeMeasurement::class)
    performanceManager?.notifyCurrentPhaseFinishedIfNeeded() // It should be `notifyTranslationToIRFinished`, but this phase not always started or already finished
    performanceManager.tryMeasurePhaseTime(PhaseType.IrLowering) {
        (irFactory.stageController as? WholeWorldStageController)?.let {
            lowerPreservingTags(allModules, context, it)
        } ?: run {
            PhaserState()
        }
    }

    return LoweredIr(context, moduleFragment, allModules, moduleDependencies.fragmentNames)
}
