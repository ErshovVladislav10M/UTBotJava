package main

type ParsingTarget struct {
	FilePath               string   `json:"filePath"`
	SelectedFunctionsNames []string `json:"selectedFunctionsNames"`
}

type ParsingTargets struct {
	Targets []ParsingTarget `json:"targets"`
}
