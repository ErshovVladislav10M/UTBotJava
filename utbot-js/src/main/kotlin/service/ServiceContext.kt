package service

data class ServiceContext(
    val utbotDir: String,
    val projectPath: String,
    var filePathToInference: String,
    var trimmedFileText: String,
)