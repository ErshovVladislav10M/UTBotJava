package main

import "go/token"

type ParsedType struct {
	Name            string `json:"name"`
	ImplementsError bool   `json:"implementsError"`
}

type ParsedFunctionParameter struct {
	Name string     `json:"name"`
	Type ParsedType `json:"type"`
}

type ParsedFunction struct {
	Name        string                    `json:"name"`
	Parameters  []ParsedFunctionParameter `json:"parameters"`
	ResultTypes []ParsedType              `json:"resultTypes"`
	position    token.Pos
}

type ParsingResult struct {
	FilePath                   string           `json:"filePath"`
	PackageName                string           `json:"packageName"`
	ParsedFunctions            []ParsedFunction `json:"parsedFunctions"`
	NotSupportedFunctionsNames []string         `json:"notSupportedFunctionsNames"`
	NotFoundFunctionsNames     []string         `json:"notFoundFunctionsNames"`
}

type ParsingResults struct {
	Results []ParsingResult `json:"results"`
}
