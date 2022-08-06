package org.utbot.go.parser

internal data class ParsingTarget(val filePath: String, val selectedFunctionsNames: List<String>)

internal data class ParsingTargets(val targets: List<ParsingTarget>)