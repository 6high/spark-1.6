/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.rpc

import java.io.File
import java.nio.channels.ReadableByteChannel

import scala.concurrent.Future

import org.apache.spark.{SecurityManager, SparkConf}
import org.apache.spark.util.{RpcUtils, Utils}


/**
 * A RpcEnv implementation must have a [[RpcEnvFactory]] implementation with an empty constructor
 * so that it can be created via Reflection.
  * 半身对象,单例模式
 */
private[spark] object RpcEnv {

  private def getRpcEnvFactory(conf: SparkConf): RpcEnvFactory = {
    val rpcEnvNames = Map(
      "akka" -> "org.apache.spark.rpc.akka.AkkaRpcEnvFactory",
      "netty" -> "org.apache.spark.rpc.netty.NettyRpcEnvFactory")

    // 默认RPC实现为netty
    val rpcEnvName = conf.get("spark.rpc", "netty")
    val rpcEnvFactoryClassName = rpcEnvNames.getOrElse(rpcEnvName.toLowerCase, rpcEnvName)
    Utils.classForName(rpcEnvFactoryClassName).newInstance().asInstanceOf[RpcEnvFactory]
  }

  def create(
      name: String,// sparkDriver 或者 sparkExecutor
      host: String,
      port: Int,
      conf: SparkConf,
      securityManager: SecurityManager,
      clientMode: Boolean = false): RpcEnv = {
    // Using Reflection to create the RpcEnv to avoid to depend on Akka directly
    // 根据反射获得RpcEnvFactory实例，默认为NettyRpcEnvFactory，2.x版本中移除了Akka实现
    val config = RpcEnvConfig(conf, name, host, port, securityManager, clientMode)

    // 工厂模式,调用NettyRpcEnvFactory.create(config: RpcEnvConfig)方法
    getRpcEnvFactory(conf).create(config)
  }
}


/**
 * An RPC environment. [[RpcEndpoint]]s need to register itself with a name to [[RpcEnv]] to
 * receives messages. Then [[RpcEnv]] will process messages sent from [[RpcEndpointRef]] or remote
 * nodes, and deliver them to corresponding [[RpcEndpoint]]s. For uncaught exceptions caught by
 * [[RpcEnv]], [[RpcEnv]] will use [[RpcCallContext.sendFailure]] to send exceptions back to the
 * sender, or logging them if no such sender or `NotSerializableException`.
 *
 * [[RpcEnv]] also provides some methods to retrieve [[RpcEndpointRef]]s given name or uri.
  *
  * 主要方法为setupEndpoint，用法上对应例子中ActorSystem的actorOf方法，
  * 用于注册RpcEndpoint，内部使用Dispatcher维护注册的RpcEndpoint，
  * 也提供了多种获取RpcEndpointRef的方法，如asyncSetupEndpointRefByURI、
  * setupEndpointRefByURI和setupEndpointRef，以及移除RpcEndpoint的方法stop，
  * 关闭RpcEnv的方法shutdown，其还维护了RpcEnvFileServer，用于上传下载jar和file。
  * 最后，实例化RpcEnv时，需指定是server模式还是client(默认是server)，server模式下底层启动netty
 */
private[spark] abstract class RpcEnv(conf: SparkConf) {

  private[spark] val defaultLookupTimeout = RpcUtils.lookupRpcTimeout(conf)

  /**
   * Return RpcEndpointRef of the registered [[RpcEndpoint]]. Will be used to implement
   * [[RpcEndpoint.self]]. Return `null` if the corresponding [[RpcEndpointRef]] does not exist.
   */
  private[rpc] def endpointRef(endpoint: RpcEndpoint): RpcEndpointRef

  /**
   * Return the address that [[RpcEnv]] is listening to.
   */
  def address: RpcAddress

  /**
   * Register a [[RpcEndpoint]] with a name and return its [[RpcEndpointRef]]. [[RpcEnv]] does not
   * guarantee thread-safety.
   */
  // 对应ActorSystem的actorOf方法，用于注册RpcEndpoint
  def setupEndpoint(name: String, endpoint: RpcEndpoint): RpcEndpointRef

  /**
   * Retrieve the [[RpcEndpointRef]] represented by `uri` asynchronously.
    * 异步
   */
  def asyncSetupEndpointRefByURI(uri: String): Future[RpcEndpointRef]

  /**
   * Retrieve the [[RpcEndpointRef]] represented by `uri`. This is a blocking action.
   */
  def setupEndpointRefByURI(uri: String): RpcEndpointRef = {
    defaultLookupTimeout.awaitResult(asyncSetupEndpointRefByURI(uri))
  }

  /**
   * Retrieve the [[RpcEndpointRef]] represented by `systemName`, `address` and `endpointName`.
   * This is a blocking action.
   */
  def setupEndpointRef(
      systemName: String, address: RpcAddress, endpointName: String): RpcEndpointRef = {
    setupEndpointRefByURI(uriOf(systemName, address, endpointName))
  }

  /**
   * Stop [[RpcEndpoint]] specified by `endpoint`.
   */
  def stop(endpoint: RpcEndpointRef): Unit

  /**
   * Shutdown this [[RpcEnv]] asynchronously. If need to make sure [[RpcEnv]] exits successfully,
   * call [[awaitTermination()]] straight after [[shutdown()]].
   */
  def shutdown(): Unit

  /**
   * Wait until [[RpcEnv]] exits.
   *
   * TODO do we need a timeout parameter?
   */
  def awaitTermination(): Unit

  /**
   * Create a URI used to create a [[RpcEndpointRef]]. Use this one to create the URI instead of
   * creating it manually because different [[RpcEnv]] may have different formats.
   */
  def uriOf(systemName: String, address: RpcAddress, endpointName: String): String

  /**
   * [[RpcEndpointRef]] cannot be deserialized without [[RpcEnv]]. So when deserializing any object
   * that contains [[RpcEndpointRef]]s, the deserialization codes should be wrapped by this method.
   */
  def deserialize[T](deserializationAction: () => T): T

  /**
   * Return the instance of the file server used to serve files. This may be `null` if the
   * RpcEnv is not operating in server mode.
   */
  def fileServer: RpcEnvFileServer

  /**
   * Open a channel to download a file from the given URI. If the URIs returned by the
   * RpcEnvFileServer use the "spark" scheme, this method will be called by the Utils class to
   * retrieve the files.
   *
   * @param uri URI with location of the file.
   */
  def openChannel(uri: String): ReadableByteChannel

}

/**
 * A server used by the RpcEnv to server files to other processes owned by the application.
 *
 * The file server can return URIs handled by common libraries (such as "http" or "hdfs"), or
 * it can return "spark" URIs which will be handled by `RpcEnv#fetchFile`.
  *
  * RpcEnvFileServer作用于driver程序，为executor提供jar和file的远程下载服务
  * 启动文件服务器,基于netty或者jetty,提供远程下载jar和file
  * 子类NettyStreamManager的底层由netty实现
  * 子类HttpBasedFileServer的底层由jetty实现
 */
private[spark] trait RpcEnvFileServer {

  /**
   * Adds a file to be served by this RpcEnv. This is used to serve files from the driver
   * to executors when they're stored on the driver's local file system.
   *
   * @param file Local file to serve.
   * @return A URI for the location of the file.
    *
    *  添加file到文件服务器,用于exector下载
   */
  def addFile(file: File): String

  /**
   * Adds a jar to be served by this RpcEnv. Similar to `addFile` but for jars added using
   * `SparkContext.addJar`.
   *
   * @param file Local file to serve.
   * @return A URI for the location of the file.
    *  添加jar到文件拂去其,用于Executor下载
   */
  def addJar(file: File): String

}

private[spark] case class RpcEnvConfig(
    conf: SparkConf,
    name: String,
    host: String,
    port: Int,
    securityManager: SecurityManager,
    clientMode: Boolean)
