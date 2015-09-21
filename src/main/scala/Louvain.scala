import scala.reflect.ClassTag

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.serializers.DefaultArraySerializers.ObjectArraySerializer
import com.esotericsoftware.kryo.{Kryo, KryoSerializable}

import org.apache.spark._
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._
import org.apache.spark.graphx._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.{Logging, SparkContext}

class Louvain() extends Serializable{
  def getEdgeRDD(sc: SparkContext, conf: LouvainConfig, typeConversionMethod: String => Long = _.toLong): RDD[Edge[Long]] = {
    sc.textFile(conf.inputFile, conf.parallelism).map(row => {
      val tokens = row.split(conf.delimiter).map(_.trim())

      tokens.length match {
        case 2 => new Edge(typeConversionMethod(tokens(0)),
          typeConversionMethod(tokens(1)), 1L)
        case 3 => new Edge(typeConversionMethod(tokens(0)),
          typeConversionMethod(tokens(1)), tokens(2).toLong)
        case _ => throw new IllegalArgumentException("invalid input line: " + row)
      }
    })
  }

  /**
    * Generates a new graph of type Graph[VertexState,Long] based on an
    input graph of type.
    * Graph[VD,Long].  The resulting graph can be used for louvain computation.
    *
    */
  def createLouvainGraph[VD: ClassTag](graph: Graph[VD, Long]):
      Graph[LouvainData, Long] = {
    val nodeWeights = graph.aggregateMessages(
      (e:EdgeContext[VD,Long,Long]) => {
        e.sendToSrc(e.attr)
        e.sendToDst(e.attr)
      },
      (e1: Long, e2: Long) => e1 + e2
    )

    graph.outerJoinVertices(nodeWeights)((vid, data, weightOption) => {
      val weight = weightOption.getOrElse(0L)
      new LouvainData(vid, weight, 0L, weight, false)
    }).partitionBy(PartitionStrategy.EdgePartition2D).groupEdges(_ + _)
  }

  /**
    * Creates the messages passed between each vertex to convey
    neighborhood community data.
    */
  def sendCommunityData(e: EdgeContext[LouvainData, Long, Map[(Long, Long), Long]]) = {
    val m1 = (Map((e.srcAttr.community, e.srcAttr.communitySigmaTot) -> e.attr))
    val m2 = (Map((e.dstAttr.community, e.dstAttr.communitySigmaTot) -> e.attr))
    e.sendToSrc(m2)
    e.sendToDst(m1)
  }

  /**
    * Merge neighborhood community data into a single message for each vertex
    */
  def mergeCommunityMessages(m1: Map[(Long, Long), Long], m2: Map[(Long, Long), Long]) = {
    val newMap = scala.collection.mutable.HashMap[(Long, Long), Long]()

    m1.foreach({ case (k, v) =>
      if (newMap.contains(k)) newMap(k) = newMap(k) + v
      else newMap(k) = v
    })

    m2.foreach({ case (k, v) =>
      if (newMap.contains(k)) newMap(k) = newMap(k) + v
      else newMap(k) = v
    })

    newMap.toMap
  }

  /**
    * Returns the change in modularity that would result from a vertex
    moving to a specified community.
    */
  def q(
    currCommunityId: Long,
    testCommunityId: Long,
    testSigmaTot: Long,
    edgeWeightInCommunity: Long,
    nodeWeight: Long,
    internalWeight: Long,
    totalEdgeWeight: Long): BigDecimal = {

    val isCurrentCommunity = currCommunityId.equals(testCommunityId)
    val M = BigDecimal(totalEdgeWeight)
    val k_i_in_L = if (isCurrentCommunity) edgeWeightInCommunity + internalWeight else edgeWeightInCommunity
    val k_i_in = BigDecimal(k_i_in_L)
    val k_i = BigDecimal(nodeWeight + internalWeight)
    val sigma_tot = if (isCurrentCommunity) BigDecimal(testSigmaTot) - k_i else BigDecimal(testSigmaTot)

    var deltaQ = BigDecimal(0.0)

    if (!(isCurrentCommunity && sigma_tot.equals(BigDecimal.valueOf(0.0)))) {
      deltaQ = k_i_in - (k_i * sigma_tot / M)
      //println(s"      $deltaQ = $k_i_in - ( $k_i * $sigma_tot / $M")
    }

    deltaQ
  }

  /**
    * Join vertices with community data form their neighborhood and
    select the best community for each vertex to maximize change in
    modularity.
    * Returns a new set of vertices with the updated vertex state.
    */
  def louvainVertJoin(
    louvainGraph: Graph[LouvainData, Long],
    msgRDD: VertexRDD[Map[(Long, Long), Long]],
    totalEdgeWeight: Broadcast[Long],
    even: Boolean) = {

    // innerJoin[U, VD2](other: RDD[(VertexId, U)])(f: (VertexId, VD, U) => VD2): VertexRDD[VD2]
    louvainGraph.vertices.innerJoin(msgRDD)((vid, louvainData, communityMessages) => {

      var bestCommunity = louvainData.community
      val startingCommunityId = bestCommunity
      var maxDeltaQ = BigDecimal(0.0);
      var bestSigmaTot = 0L

      // VertexRDD[scala.collection.immutable.Map[(Long, Long),Long]]
      // e.g. (1,Map((3,10) -> 2, (6,4) -> 2, (2,8) -> 2, (4,8) -> 2, (5,8) -> 2))
      // e.g. communityId:3, sigmaTotal:10, communityEdgeWeight:2
      communityMessages.foreach({ case ((communityId, sigmaTotal), communityEdgeWeight) =>
        val deltaQ = q(
          startingCommunityId,
          communityId,
          sigmaTotal,
          communityEdgeWeight,
          louvainData.nodeWeight,
          louvainData.internalWeight,
          totalEdgeWeight.value)

        //println(" communtiy: "+communityId+" sigma:"+sigmaTotal+"
        //edgeweight:"+communityEdgeWeight+" q:"+deltaQ)
        if (deltaQ > maxDeltaQ || (deltaQ > 0 && (deltaQ == maxDeltaQ &&
          communityId > bestCommunity))) {
          maxDeltaQ = deltaQ
          bestCommunity = communityId
          bestSigmaTot = sigmaTotal
        }
      })

      // only allow changes from low to high communties on even cyces and
      // high to low on odd cycles
      if (louvainData.community != bestCommunity && ((even &&
        louvainData.community > bestCommunity) || (!even &&
          louvainData.community < bestCommunity))) {
        //println("  "+vid+" SWITCHED from "+vdata.community+" to "+bestCommunity)
        louvainData.community = bestCommunity
        louvainData.communitySigmaTot = bestSigmaTot
        louvainData.changed = true
      }
      else {
        louvainData.changed = false
      }

      if (louvainData == null)
        println("vdata is null: " + vid)

      louvainData
    })
  }

  def louvain(
    sc: SparkContext,
    graph: Graph[LouvainData, Long],
    minProgress: Int = 1,
    progressCounter: Int = 1): (Double, Graph[LouvainData, Long], Int) = {

    var louvainGraph = graph.cache()

    val graphWeight = louvainGraph.vertices.map(louvainVertex => {
      val (vertexId, louvainData) = louvainVertex
      louvainData.internalWeight + louvainData.nodeWeight
    }).reduce(_ + _)

    val totalGraphWeight = sc.broadcast(graphWeight)

    println("totalEdgeWeight: " + totalGraphWeight.value)

    // gather community information from each vertex's local neighborhood
    var communityRDD =
      louvainGraph.aggregateMessages(sendCommunityData, mergeCommunityMessages)

    var activeMessages = communityRDD.count() //materializes the msgRDD
                                              //and caches it in memory
    var updated = 0L - minProgress
    var even = false
    var count = 0
    val maxIter = 100000
    var stop = 0
    var updatedLastPhase = 0L
    do {
      count += 1
      even = !even

      // label each vertex with its best community based on neighboring
      // community information
      val labeledVertices = louvainVertJoin(louvainGraph, communityRDD,
        totalGraphWeight, even).cache()

      // calculate new sigma total value for each community (total weight
      // of each community)
      val communityUpdate = labeledVertices
        .map({ case (vid, vdata) => (vdata.community, vdata.nodeWeight +
          vdata.internalWeight)})
        .reduceByKey(_ + _).cache()

      // map each vertex ID to its updated community information
      val communityMapping = labeledVertices
        .map({ case (vid, vdata) => (vdata.community, vid)})
        .join(communityUpdate)
        .map({ case (community, (vid, sigmaTot)) => (vid, (community, sigmaTot))})
        .cache()

      // join the community labeled vertices with the updated community info
      val updatedVertices = labeledVertices.join(communityMapping).map({
        case (vertexId, (louvainData, communityTuple)) =>
          val (community, communitySigmaTot) = communityTuple
          louvainData.community = community
          louvainData.communitySigmaTot = communitySigmaTot
          (vertexId, louvainData)
      }).cache()

      updatedVertices.count()
      labeledVertices.unpersist(blocking = false)
      communityUpdate.unpersist(blocking = false)
      communityMapping.unpersist(blocking = false)

      val prevG = louvainGraph

      louvainGraph = louvainGraph.outerJoinVertices(updatedVertices)((vid, old, newOpt) => newOpt.getOrElse(old))
      louvainGraph.cache()

      // gather community information from each vertex's local neighborhood
      val oldMsgs = communityRDD
      communityRDD = louvainGraph.aggregateMessages(sendCommunityData, mergeCommunityMessages).cache()
      activeMessages = communityRDD.count() // materializes the graph
                                            // by forcing computation

      oldMsgs.unpersist(blocking = false)
      updatedVertices.unpersist(blocking = false)
      prevG.unpersistVertices(blocking = false)

      // half of the communites can swtich on even cycles and the other half
      // on odd cycles (to prevent deadlocks) so we only want to look for
      // progess on odd cycles (after all vertcies have had a chance to
      // move)
      if (even) updated = 0
      updated = updated + louvainGraph.vertices.filter(_._2.changed).count

      if (!even) {
        println("  # vertices moved: " + java.text.NumberFormat.getInstance().format(updated))

        if (updated >= updatedLastPhase - minProgress) stop += 1

        updatedLastPhase = updated
      }

    } while (stop <= progressCounter && (even || (updated > 0 && count < maxIter)))

    println("\nCompleted in " + count + " cycles")

    // Use each vertex's neighboring community data to calculate the
    // global modularity of the graph
    val newVertices =
      louvainGraph.vertices.innerJoin(communityRDD)((vertexId, louvainData,
        communityMap) => {
        // sum the nodes internal weight and all of its edges that are in
        // its community
        val community = louvainData.community
        var accumulatedInternalWeight = louvainData.internalWeight
        val sigmaTot = louvainData.communitySigmaTot.toDouble
        def accumulateTotalWeight(totalWeight: Long, item: ((Long, Long), Long)) = {
          val ((communityId, sigmaTotal), communityEdgeWeight) = item
          if (louvainData.community == communityId)
            totalWeight + communityEdgeWeight
          else
            totalWeight
        }

        accumulatedInternalWeight = communityMap.foldLeft(accumulatedInternalWeight)(accumulateTotalWeight)
        val M = totalGraphWeight.value
        val k_i = louvainData.nodeWeight + louvainData.internalWeight
        val q = (accumulatedInternalWeight.toDouble / M) - ((sigmaTot * k_i) / math.pow(M, 2))
        //println(s"vid: $vid community: $community $q = ($k_i_in / $M) - ( ($sigmaTot * $k_i) / math.pow($M, 2) )")
        if (q < 0)
          0
        else
          q
      })

    val actualQ = newVertices.values.reduce(_ + _)

    // return the modularity value of the graph along with the
    // graph. vertices are labeled with their community
    (actualQ, louvainGraph, count / 2)

  }

  def compressGraph(graph: Graph[LouvainData, Long], debug: Boolean = true): Graph[LouvainData, Long] = {
    // aggregate the edge weights of self loops. edges with both src and dst in the same community.
    // WARNING  can not use graph.mapReduceTriplets because we are mapping to new vertexIds
    val internalEdgeWeights = graph.triplets.flatMap(et => {
      if (et.srcAttr.community == et.dstAttr.community) {
        Iterator((et.srcAttr.community, 2 * et.attr)) // count the weight from both nodes
      }
      else Iterator.empty
    }).reduceByKey(_ + _)

    // aggregate the internal weights of all nodes in each community
    val internalWeights = graph.vertices.values.map(vdata =>
      (vdata.community, vdata.internalWeight))
      .reduceByKey(_ + _)

    // join internal weights and self edges to find new interal weight of each community
    val newVertices = internalWeights.leftOuterJoin(internalEdgeWeights).map({ case (vid, (weight1, weight2Option)) =>
      val weight2 = weight2Option.getOrElse(0L)
      val state = new LouvainData()
      state.community = vid
      state.changed = false
      state.communitySigmaTot = 0L
      state.internalWeight = weight1 + weight2
      state.nodeWeight = 0L
      (vid, state)
    }).cache()

    // translate each vertex edge to a community edge
    val edges = graph.triplets.flatMap(et => {
      val src = math.min(et.srcAttr.community, et.dstAttr.community)
      val dst = math.max(et.srcAttr.community, et.dstAttr.community)
      if (src != dst) Iterator(new Edge(src, dst, et.attr))
      else Iterator.empty
    }).cache()

    // generate a new graph where each community of the previous graph is
    // now represented as a single vertex
    val compressedGraph = Graph(newVertices, edges)
      .partitionBy(PartitionStrategy.EdgePartition2D).groupEdges(_ + _)

    // calculate the weighted degree of each node
    val nodeWeights = compressedGraph.aggregateMessages(
      (e:EdgeContext[LouvainData,Long,Long]) => {
        e.sendToSrc(e.attr)
        e.sendToDst(e.attr)
      },
      (e1: Long, e2: Long) => e1 + e2
    )

    // fill in the weighted degree of each node
    // val louvainGraph = compressedGraph.joinVertices(nodeWeights)((vid,data,weight)=> {
    val louvainGraph = compressedGraph.outerJoinVertices(nodeWeights)((vid, data, weightOption) => {
      val weight = weightOption.getOrElse(0L)
      data.communitySigmaTot = weight + data.internalWeight
      data.nodeWeight = weight
      data
    }).cache()
    louvainGraph.vertices.count()
    louvainGraph.triplets.count() // materialize the graph

    newVertices.unpersist(blocking = false)
    edges.unpersist(blocking = false)
    louvainGraph
  }

  def saveLevel(
    sc: SparkContext,
    config: LouvainConfig,
    level: Int,
    qValues: Array[(Int, Double)],
    graph: Graph[LouvainData, Long]) = {

    val vertexSavePath = config.outputDir + "/level_" + level + "_vertices"
    val edgeSavePath = config.outputDir + "/level_" + level + "_edges"

    // save
    graph.vertices.saveAsTextFile(vertexSavePath)
    graph.edges.saveAsTextFile(edgeSavePath)

    // overwrite the q values at each level
    sc.parallelize(qValues, 1).saveAsTextFile(config.outputDir + "/qvalues_" + level)
  }

  //def run[VD: ClassTag](sc: SparkContext, config: LouvainConfig, graph: Graph[VD, Long]): Unit = {
  def run[VD: ClassTag](sc: SparkContext, config: LouvainConfig): Unit = {
    val edgeRDD = getEdgeRDD(sc, config)
    val initialGraph = Graph.fromEdges(edgeRDD, None)
    var louvainGraph = createLouvainGraph(initialGraph)

    var compressionLevel = -1 // number of times the graph has been compressed
    var q_modularityValue = -1.0 // current modularity value
    var halt = false

    var qValues: Array[(Int, Double)] = Array()

    do {
      compressionLevel += 1
      println(s"\nStarting Louvain level $compressionLevel")

      // label each vertex with its best community choice at this level of compression
      val (currentQModularityValue, currentGraph, numberOfPasses) =
        louvain(sc, louvainGraph, config.minimumCompressionProgress, config.progressCounter)

      louvainGraph.unpersistVertices(blocking = false)
      louvainGraph = currentGraph

      println(s"qValue: $currentQModularityValue")

      qValues = qValues :+ ((compressionLevel, currentQModularityValue))

      saveLevel(sc, config, compressionLevel, qValues, louvainGraph)

      // If modularity was increased by at least 0.001 compress the graph and repeat
      // halt immediately if the community labeling took less than 3 passes
      //println(s"if ($passes > 2 && $currentQ > $q + 0.001 )")
      if (numberOfPasses > 2 && currentQModularityValue > q_modularityValue + 0.001) {
        q_modularityValue = currentQModularityValue
        louvainGraph = compressGraph(louvainGraph)
      }
      else {
        halt = true
      }

    } while (!halt)
      //finalSave(sc, compressionLevel, q_modularityValue, louvainGraph)


  }
}
