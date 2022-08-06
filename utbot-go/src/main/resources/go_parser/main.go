package main

import (
	"encoding/json"
	"flag"
	"go/ast"
	"go/importer"
	"go/parser"
	"go/token"
	"go/types"
	"log"
	"os"
)

func checkError(err error) {
	if err != nil {
		log.Fatal(err.Error())
	}
}

func parseTarget(target ParsingTarget) ParsingResult {
	// first of all, parse AST
	fset := token.NewFileSet()
	fileAst, astErr := parser.ParseFile(fset, target.FilePath, nil, 0)
	checkError(astErr)

	// collect info about types
	typesConfig := types.Config{Importer: importer.Default()}
	info := &types.Info{
		Defs:  make(map[*ast.Ident]types.Object),
		Uses:  make(map[*ast.Ident]types.Object),
		Types: make(map[ast.Expr]types.TypeAndValue),
	}
	_, typesCheckErr := typesConfig.Check(target.FilePath, fset, []*ast.File{fileAst}, info)
	checkError(typesCheckErr)

	// collect required info about selected functions
	parsedFunctions, notSupportedFunctionsNames, notFoundFunctionsNames :=
		collectSelectedParsedFunctions(info, target.SelectedFunctionsNames)

	return ParsingResult{
		FilePath:                   target.FilePath,
		PackageName:                fileAst.Name.String(),
		ParsedFunctions:            parsedFunctions,
		NotSupportedFunctionsNames: notSupportedFunctionsNames,
		NotFoundFunctionsNames:     notFoundFunctionsNames,
	}
}

func main() {
	var targetsFilePath, resultsFilePath string
	flag.StringVar(&targetsFilePath, "targets", "", "path to JSON file to read parsing targets from")
	flag.StringVar(&resultsFilePath, "results", "", "path to JSON file to write parsing results to")
	flag.Parse()

	// read and deserialize targets
	targetsBytes, readErr := os.ReadFile(targetsFilePath)
	checkError(readErr)

	var parsingTargets ParsingTargets
	fromJsonErr := json.Unmarshal(targetsBytes, &parsingTargets)
	checkError(fromJsonErr)

	// parse each requested Go source file
	parsingResults := ParsingResults{Results: []ParsingResult{}}
	for _, target := range parsingTargets.Targets {
		result := parseTarget(target)
		parsingResults.Results = append(parsingResults.Results, result)
	}

	// serialize and write results
	jsonBytes, toJsonErr := json.MarshalIndent(parsingResults, "", "  ")
	checkError(toJsonErr)

	writeErr := os.WriteFile(resultsFilePath, jsonBytes, os.ModePerm)
	checkError(writeErr)
}
