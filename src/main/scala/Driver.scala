import org.apache.spark.graphx.{Edge, Graph}
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.log4j.{Level, Logger}


object Driver {

  def main(args: Array[String]): Unit ={

    val config = LouvainConfig(
      "src/data/input/sample.txt",
      "src/data/output/",
      20,
      2000,
      1,
      ",")

    // def deleteOutputDir(config: LouvainConfig): Unit = {
    //   val hadoopConf = new org.apache.hadoop.conf.Configuration()

    //   val hdfs = org.apache.hadoop.fs.FileSystem.get(new java.net.URI("hdfs://localhost:8020"), hadoopConf)

    //   try {
    //     hdfs.delete(new org.apache.hadoop.fs.Path(config.outputDir), true)
    //   }
    //   catch {
    //     case _ : Throwable => { }
    //   }
    // }

    // val conf = new SparkConf().setAppName("ApproxTriangles").setMaster("local[2]")
    // conf.set("spark.default.parallelism", (8).toString)
    // conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    // conf.set("spark.logConf", "true")
    // //sparkConf.getAll.foreach(println(_))
    // val sc = new SparkContext(conf)
    // Logger.getRootLogger.setLevel(Level.WARN)

    val sc = new SparkContext("local", "Louvain", "/home/john/spark-1.5.0",
      List("target/scala-2.11/louvain-modularity_2.11-0.0.1.jar"))

    // deleteOutputDir(config)

    val louvain = new Louvain()
    louvain.run(sc, config)

  }
}
