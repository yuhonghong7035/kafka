/*
 * Copyright 2010 LinkedIn
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/
package kafka.producer

import kafka.utils.{StringSerializer, ZkUtils, ZKConfig}
import org.I0Itec.zkclient.{IZkChildListener, ZkClient}
import collection.mutable.HashMap
import collection.mutable.Map
import org.apache.log4j.Logger

/**
 * If zookeeper based auto partition discovery is enabled, fetch broker info like
 * host, port, number of partitions from zookeeper
 */
class ZKBrokerPartitionInfo(config: ZKConfig, producerCbk: (Int, String, Int) => Unit) extends BrokerPartitionInfo {
  private val logger = Logger.getLogger(classOf[ZKBrokerPartitionInfo])
  private val zkClient = new ZkClient(config.zkConnect, config.zkSessionTimeoutMs, config.zkConnectionTimeoutMs,
    StringSerializer)
  // maintain a map from topic -> list of (broker, num_partitions) from zoookeeper
  private val topicBrokerPartitions = getZKTopicPartitionInfo
  // register listener for change of topics to keep topicsBrokerPartitions updated
  private val topicsListener = new TopicsListener(topicBrokerPartitions)
  zkClient.subscribeChildChanges(ZkUtils.brokerTopicsPath, topicsListener)
  // register listener for change of brokers for each topic to keep topicsBrokerPartitions updated  
  topicBrokerPartitions.keySet.foreach(topic => zkClient.subscribeChildChanges(ZkUtils.brokerTopicsPath + "/" + topic,
                                                topicsListener))
  private var allBrokers = getZKBrokerInfo

  // register listener for new broker
  private val brokerListener = new BrokerListener(allBrokers.keySet.toSeq)
  zkClient.subscribeChildChanges(ZkUtils.brokerIdsPath, brokerListener)

  /**
   * Return a sequence of (brokerId, numPartitions)
   * @param topic the topic for which this information is to be returned
   * @return a sequence of (brokerId, numPartitions)
   */
  def getBrokerPartitionInfo(topic: String): Seq[(Int, Int)] = {
    val brokerPartitions = topicBrokerPartitions.get(topic)
    var numBrokerPartitions: Seq[(Int, Int)] = Nil
    brokerPartitions match {
      case Some(bp) => numBrokerPartitions = brokerPartitions.get
      case None =>  // no brokers currently registered for this topic. Find the list of all brokers in the cluster.
        val allBrokersIds = ZkUtils.getChildren(zkClient, ZkUtils.brokerIdsPath)
        // since we do not have the in formation about number of partitions on these brokers, just assume single partition
        // i.e. pick partition 0 from each broker as a candidate
        numBrokerPartitions = allBrokersIds.map(b => (b.toInt, 1))
    }
    numBrokerPartitions
  }

  /**
   * Generate the host and port information for the broker identified
   * by the given broker id
   * @param brokerId the broker for which the info is to be returned
   * @return host and port of brokerId
   */
  def getBrokerInfo(brokerId: Int): Option[(String, Int)] =  allBrokers.get(brokerId)

  /**
   * Generate a mapping from broker id to the host and port for all brokers
   * @return mapping from id to host and port of all brokers
   */
  def getAllBrokerInfo: Map[Int, (String, Int)] = allBrokers

  /**
   * Generate a sequence of (brokerId, numPartitions) for all topics
   * registered in zookeeper
   * @return a mapping from topic to sequence of (brokerId, numPartitions)
   */
  private def getZKTopicPartitionInfo(): collection.mutable.Map[String, List[(Int, Int)]] = {
    val brokerPartitionsPerTopic = new HashMap[String, List[(Int, Int)]]()
    val topics = ZkUtils.getChildren(zkClient, ZkUtils.brokerTopicsPath)
    topics.foreach { topic =>
    // find the number of broker partitions registered for this topic
      val brokerTopicPath = ZkUtils.brokerTopicsPath + "/" + topic
      val brokerList = ZkUtils.getChildren(zkClient, brokerTopicPath)
      val numPartitions = brokerList.map(bid => ZkUtils.readData(zkClient, brokerTopicPath + "/" + bid).toInt)
      val brokerPartitions = brokerList.map(bid => bid.toInt).zip(numPartitions)
      val sortedBrokerPartitions = brokerPartitions.sortWith((id1, id2) => id1._1 < id2._1)
      logger.info("Sorted list of broker ids = " + sortedBrokerPartitions.toString)

      var brokerParts: List[(Int, Int)] = Nil
      sortedBrokerPartitions.foreach { bp =>
        for(i <- 0 until bp._2) {
          brokerParts ::= (bp._1, i)
        }
      }
      brokerParts = brokerParts.reverse
      brokerPartitionsPerTopic += (topic -> brokerParts)
    }
    brokerPartitionsPerTopic
  }

  /**
   * Generate a mapping from broker id to (brokerId, numPartitions) for all brokers
   * registered in zookeeper
   * @return a mapping from brokerId to (host, port)
   */
  private def getZKBrokerInfo(): Map[Int, (String, Int)] = {
    val brokers = new HashMap[Int, (String, Int)]()
    val allBrokerIds = ZkUtils.getChildren(zkClient, ZkUtils.brokerIdsPath).map(bid => bid.toInt)
    allBrokerIds.foreach { bid =>
      val brokerInfo = ZkUtils.readData(zkClient, ZkUtils.brokerIdsPath + "/" + bid)
      val brokerHostPort = brokerInfo.split(":")
      brokers += (bid -> (brokerHostPort(1), brokerHostPort(2).toInt))
    }
    brokers
  }

  /**
   * Listens to new topic registrations in zookeeper and keeps the related data structures updated
   */
  class TopicsListener(val originalTopicBrokerPartitionsMap: collection.mutable.Map[String, List[(Int, Int)]])
          extends IZkChildListener {
    private var oldTopicBrokerPartitionsMap: collection.mutable.Map[String, List[(Int, Int)]] = originalTopicBrokerPartitionsMap

    @throws(classOf[Exception])
    def handleChildChange(parentPath : String, curChilds : java.util.List[String]) {
      // check if event is for new topic
      import scala.collection.JavaConversions._
      // check to see if this event indicates new topic or a newly registered broker for an existing topic
      if(parentPath.equals(ZkUtils.brokerTopicsPath))
        processNewTopic(asBuffer(curChilds))
      else
        processNewBrokerInExistingTopic(parentPath, asBuffer(curChilds))
    }

    /**
     * Generate a mapping from broker id to (brokerId, numPartitions) for the list of brokers
     * specified
     * @param topic the topic to which the brokers have registered
     * @param brokerList the list of brokers for which the partitions info is to be generated
     * @return a sequence of (brokerId, numPartitions) for brokers in brokerList
     */
    private def getBrokerPartitions(topic: String, brokerList: List[Int]): List[(Int, Int)] = {
      val brokerTopicPath = ZkUtils.brokerTopicsPath + "/" + topic
      val numPartitions = brokerList.map(bid => ZkUtils.readData(zkClient, brokerTopicPath + "/" + bid).toInt)
      val brokerPartitions = brokerList.zip(numPartitions)

      val sortedBrokerPartitions = brokerPartitions.sortWith((id1, id2) => id1._1 < id2._1)
      logger.info("Sorted list of broker ids = " + sortedBrokerPartitions.toString)

      var brokerParts: List[(Int, Int)] = Nil
      sortedBrokerPartitions.foreach { bp =>
        for(i <- 0 until bp._2) brokerParts ::= (bp._1, i)
      }
      brokerParts.reverse
    }

    /**
     * Generate a mapping from broker id to (brokerId, numPartitions) for the list of brokers
     * registered under the specified topics
     * @param updatedTopics the list of new topics in zookeeper
     */
    private def processNewTopic(updatedTopics: Seq[String]) = {
      val newTopics = oldTopicBrokerPartitionsMap.keySet.diff(updatedTopics.toSet)
      newTopics.foreach { topic =>
      // find the number of broker partitions registered for this topic
        val brokerTopicPath = ZkUtils.brokerTopicsPath + "/" + topic
        val brokerList = ZkUtils.getChildren(zkClient, brokerTopicPath)
        val brokerParts = getBrokerPartitions(topic, brokerList.map(b => b.toInt).toList)
        logger.info("List of broker partitions for new topic " + topic + " are " + brokerParts.toString)
        topicBrokerPartitions += (topic -> brokerParts)
      }
    }

    /**
     * Generate the updated mapping of (brokerId, numPartitions) for the new list of brokers
     * registered under some topic
     * @param parentPath the path of the topic under which the brokers have changed
     * @param curChilds the list of changed brokers 
     */
    private def processNewBrokerInExistingTopic(parentPath: String, curChilds: Seq[String]) = {
      val topic = parentPath.split("/").last
      val brokerTopicPath = ZkUtils.brokerTopicsPath + "/" + topic
      logger.info("List of brokers changed for topic " + topic)
      val updatedBrokerList = curChilds.map(b => b.toInt)
      // find the old list of brokers for this topic
      val brokerList = oldTopicBrokerPartitionsMap.get(topic)
      brokerList match {
        case Some(brokersParts) =>
          val newBrokers = brokersParts.map(bp => bp._1).diff(updatedBrokerList)
          var newBrokerParts = getBrokerPartitions(topic, newBrokers)
          newBrokerParts ++= brokersParts
          newBrokerParts = newBrokerParts.sortWith((id1, id2) => id1._1 < id2._1)
          logger.info("New list of broker partitions for topic " + topic + " are " + newBrokerParts.toString)
          topicBrokerPartitions += (topic -> newBrokerParts)
        case None => // find the no. of registered partitions for all brokers
          val brokerParts = getBrokerPartitions(topic, updatedBrokerList.toList)
          logger.info("List of broker partitions for topic " + topic + " are " + brokerParts.toString)
          topicBrokerPartitions += (topic -> brokerParts)
      }
    }
  }

  /**
   * Listens to new broker registrations in zookeeper and keeps the related data structures updated
   */
  class BrokerListener(val brokerList: Seq[Int])
          extends IZkChildListener {
    private var oldBrokerIds = brokerList

    @throws(classOf[Exception])
    def handleChildChange(parentPath : String, curChilds : java.util.List[String]) {
      if(parentPath.equals(ZkUtils.brokerIdsPath)) {
        import scala.collection.JavaConversions._
        val updatedBrokerList = asBuffer(curChilds).map(bid => bid.toInt)
        val newBrokers = oldBrokerIds.diff(updatedBrokerList)
        newBrokers.foreach { bid =>
          val brokerInfo = ZkUtils.readData(zkClient, ZkUtils.brokerIdsPath + "/" + bid)
          val brokerHostPort = brokerInfo.split(":")
          allBrokers += (bid -> (brokerHostPort(1), brokerHostPort(2).toInt))
          producerCbk(bid, brokerHostPort(1), brokerHostPort(2).toInt)
        }
      }
    }
  }
}