case class LouvainConfig(
  inputFile: String,
  outputDir: String,
  parallelism: Int,
  minimumCompressionProgress: Int,
  progressCounter: Int,
  delimiter: String)
