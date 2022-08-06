package org.utbot.go.parser

internal data class ParsedType(val name: String, val implementsError: Boolean)

internal data class ParsedFunctionParameter(val name: String, val type: ParsedType)

internal data class ParsedFunction(
    val name: String,
    val parameters: List<ParsedFunctionParameter>,
    val resultTypes: List<ParsedType>,
)

internal data class ParsingResult(
    val filePath: String,
    val packageName: String,
    val parsedFunctions: List<ParsedFunction>,
    val notSupportedFunctionsNames: List<String>,
    val notFoundFunctionsNames: List<String>
)

internal data class ParsingResults(val results: List<ParsingResult>)