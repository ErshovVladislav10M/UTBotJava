package main

import (
	"go/types"
	"sort"
)

func implementsError(typ types.Type) bool {
	return typ.Underlying().String() == "interface{Error() string}"

	// TODO: get types.Interface of "error", for now straightforward strings equals
	//if(types.Implements(underlyingType, ErrorInterface)) {
	//	return true
	//}
}

func toParsedType(typ types.Type) ParsedType {
	implementsError := implementsError(typ)
	var name string
	if implementsError {
		name = "error"
	} else {
		name = typ.Underlying().String()
	}
	return ParsedType{
		Name:            name,
		ImplementsError: implementsError,
	}
}

// for now supports only basic and error result types
func checkTypeIsSupported(typ types.Type, isResultType bool) bool {
	underlyingType := typ.Underlying() // analyze real type, not alias or defined type
	if _, ok := underlyingType.(*types.Basic); ok {
		return true
	}
	if isResultType && implementsError(underlyingType) {
		return true
	}
	return false
}

func checkIsSupported(signature *types.Signature) bool {
	if signature.Recv() != nil { // is method
		return false
	}
	if signature.TypeParams() != nil { // has type params
		return false
	}
	if signature.Variadic() { // is variadic
		return false
	}
	if results := signature.Results(); results != nil {
		for i := 0; i < results.Len(); i++ {
			result := results.At(i)
			if !checkTypeIsSupported(result.Type(), true) {
				return false
			}
		}
	}
	if parameters := signature.Params(); parameters != nil {
		for i := 0; i < parameters.Len(); i++ {
			parameter := parameters.At(i)
			if !checkTypeIsSupported(parameter.Type(), false) {
				return false
			}
		}
	}
	return true
}

//goland:noinspection GoPreferNilSlice
func collectSelectedParsedFunctions(info *types.Info, selectedFunctionsNames []string) (
	parsedFunctions []ParsedFunction,
	notSupportedFunctionsNames []string,
	notFoundFunctionsNames []string,
) {
	parsedFunctions = []ParsedFunction{}
	notSupportedFunctionsNames = []string{}
	notFoundFunctionsNames = []string{}

	selectAll := len(selectedFunctionsNames) == 0
	foundSelectedFunctionsNamesMap := map[string]bool{}
	for _, functionName := range selectedFunctionsNames {
		foundSelectedFunctionsNamesMap[functionName] = false
	}

	for _, obj := range info.Defs {
		switch typedObj := obj.(type) {
		case *types.Func:
			parsedFunction := ParsedFunction{
				Name:        typedObj.Name(),
				Parameters:  []ParsedFunctionParameter{},
				ResultTypes: []ParsedType{},
				position:    typedObj.Pos(),
			}

			if !selectAll {
				if isFound, ok := foundSelectedFunctionsNamesMap[parsedFunction.Name]; !ok || isFound {
					continue
				} else {
					foundSelectedFunctionsNamesMap[parsedFunction.Name] = true
				}
			}

			signature := typedObj.Type().(*types.Signature)
			if !checkIsSupported(signature) {
				notSupportedFunctionsNames = append(notSupportedFunctionsNames, parsedFunction.Name)
				continue
			}
			if parameters := signature.Params(); parameters != nil {
				for i := 0; i < parameters.Len(); i++ {
					parameter := parameters.At(i)
					parsedFunction.Parameters = append(parsedFunction.Parameters,
						ParsedFunctionParameter{
							Name: parameter.Name(),
							Type: toParsedType(parameter.Type()),
						})
				}
			}
			if results := signature.Results(); results != nil {
				for i := 0; i < results.Len(); i++ {
					result := results.At(i)
					parsedFunction.ResultTypes = append(parsedFunction.ResultTypes, toParsedType(result.Type()))
				}
			}

			parsedFunctions = append(parsedFunctions, parsedFunction)
		}
	}

	for functionName, isFound := range foundSelectedFunctionsNamesMap {
		if !isFound {
			notFoundFunctionsNames = append(notFoundFunctionsNames, functionName)
		}
	}
	sort.Slice(parsedFunctions, func(i, j int) bool {
		return parsedFunctions[i].position < parsedFunctions[j].position
	})
	sort.Sort(sort.StringSlice(notSupportedFunctionsNames))
	sort.Sort(sort.StringSlice(notFoundFunctionsNames))

	return parsedFunctions, notSupportedFunctionsNames, notFoundFunctionsNames
}
