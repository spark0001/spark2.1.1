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

package org.apache.spark.executor

import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import org.apache.spark._
import org.apache.spark.TaskState.TaskState
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.deploy.worker.WorkerWatcher
import org.apache.spark.internal.Logging
import org.apache.spark.rpc._
import org.apache.spark.scheduler.{ExecutorLossReason, TaskDescription}
import org.apache.spark.scheduler.cluster.CoarseGrainedClusterMessages._
import org.apache.spark.serializer.SerializerInstance
import org.apache.spark.util.{ThreadUtils, Utils}

private[spark] class CoarseGrainedExecutorBackend(
    override val rpcEnv: RpcEnv,
    driverUrl: String,
    executorId: String,
    hostname: String,
    cores: Int,
    userClassPath: Seq[URL],
    env: SparkEnv)
  extends ThreadSafeRpcEndpoint with ExecutorBackend with Logging {

  private[this] val stopping = new AtomicBoolean(false)
  var executor: Executor = null
  @volatile var driver: Option[RpcEndpointRef] = None

  // If this CoarseGrainedExecutorBackend is changed to support multiple threads, then this may need
  // to be changed so that we don't share the serializer instance across threads
  // ::developer api::一个序列化器实例，一次只使用一个线程。从同一个SerializerInstance创建多个序列化/反序列化流是合法的，
  // 只要这些流都是在同一个线程中使用的
  private[this] val ser: SerializerInstance = env.closureSerializer.newInstance()

  override def onStart() {
    logInfo("Connecting to driver: " + driverUrl)
    rpcEnv.asyncSetupEndpointRefByURI(driverUrl).flatMap { ref =>
      // This is a very fast action so we can use "ThreadUtils.sameThread"
      driver = Some(ref)
      // 向Driver发送RegisterExecutor消息注册executor
      // RegisterExecutor类封装了executor的信息，app信息
      ref.ask[Boolean](RegisterExecutor(executorId, self, hostname, cores, extractLogUrls))
    }(ThreadUtils.sameThread).onComplete {
      // This is a very fast action so we can use "ThreadUtils.sameThread"
      case Success(msg) =>
        // Always receive `true`. Just ignore it
      case Failure(e) =>
        exitExecutor(1, s"Cannot register with driver: $driverUrl", e, notifyDriver = false)
    }(ThreadUtils.sameThread)
  }

  def extractLogUrls: Map[String, String] = {
    val prefix = "SPARK_LOG_URL_"
    sys.env.filterKeys(_.startsWith(prefix))
      .map(e => (e._1.substring(prefix.length).toLowerCase, e._2))
  }

  override def receive: PartialFunction[Any, Unit] = {
    // 接收来自 TaskScheduler底层驱动CoarseGrainedSchedulerBackend的RegisteredExecutor消息
    case RegisteredExecutor =>
      logInfo("Successfully registered with driver")
      try {
        // 创建Executor执行句柄
        executor = new Executor(executorId, hostname, env, userClassPath, isLocal = false)
      } catch {
        case NonFatal(e) =>
          exitExecutor(1, "Unable to create executor due to " + e.getMessage, e)
      }

    case RegisterExecutorFailed(message) =>
      exitExecutor(1, "Slave registration failed: " + message)
<<<<<<< HEAD

    /**
      * 从Driver发送过来的消息LaunchTask
      */
=======
    // 启动 task
>>>>>>> 49b170c6460970282f4fb3848fba3b8702adf514
    case LaunchTask(data) =>
      if (executor == null) {
        exitExecutor(1, "Received LaunchTask command but executor was null")
      } else {
        val taskDesc = ser.deserialize[TaskDescription](data.value)
        logInfo("Got assigned task " + taskDesc.taskId)
<<<<<<< HEAD
        // 调用executor的launchTask方法，分配线程给Task，通过launchTask来执行Task
=======
        // 启动 task的核心处理逻辑
>>>>>>> 49b170c6460970282f4fb3848fba3b8702adf514
        executor.launchTask(this, taskId = taskDesc.taskId, attemptNumber = taskDesc.attemptNumber,
          taskDesc.name, taskDesc.serializedTask)
      }

    case KillTask(taskId, _, interruptThread) =>
      if (executor == null) {
        exitExecutor(1, "Received KillTask command but executor was null")
      } else {
        executor.killTask(taskId, interruptThread)
      }

    case StopExecutor =>
      stopping.set(true)
      logInfo("Driver commanded a shutdown")
      // Cannot shutdown here because an ack may need to be sent back to the caller. So send
      // a message to self to actually do the shutdown.
      self.send(Shutdown)

    case Shutdown =>
      stopping.set(true)
      new Thread("CoarseGrainedExecutorBackend-stop-executor") {
        override def run(): Unit = {
          // executor.stop() will call `SparkEnv.stop()` which waits until RpcEnv stops totally.
          // However, if `executor.stop()` runs in some thread of RpcEnv, RpcEnv won't be able to
          // stop until `executor.stop()` returns, which becomes a dead-lock (See SPARK-14180).
          // Therefore, we put this line in a new thread.
          executor.stop()
        }
      }.start()
  }

  override def onDisconnected(remoteAddress: RpcAddress): Unit = {
    if (stopping.get()) {
      logInfo(s"Driver from $remoteAddress disconnected during shutdown")
    } else if (driver.exists(_.address == remoteAddress)) {
      exitExecutor(1, s"Driver $remoteAddress disassociated! Shutting down.", null,
        notifyDriver = false)
    } else {
      logWarning(s"An unknown ($remoteAddress) driver disconnected.")
    }
  }

  /**
    * 给Driver发送信息汇报自己的状态，说明自己的running状态--在taskRunner中的run方法中被调用
    * @param taskId
    * @param state
    * @param data
    */
  override def statusUpdate(taskId: Long, state: TaskState, data: ByteBuffer) {
    val msg = StatusUpdate(executorId, taskId, state, data)
    driver match {
      case Some(driverRef) => driverRef.send(msg)
      case None => logWarning(s"Drop $msg because has not yet connected to driver")
    }
  }

  /**
   * This function can be overloaded by other child classes to handle
   * executor exits differently. For e.g. when an executor goes down,
   * back-end may not want to take the parent process down.
   */
  protected def exitExecutor(code: Int,
                             reason: String,
                             throwable: Throwable = null,
                             notifyDriver: Boolean = true) = {
    val message = "Executor self-exiting due to : " + reason
    if (throwable != null) {
      logError(message, throwable)
    } else {
      logError(message)
    }

    if (notifyDriver && driver.nonEmpty) {
      driver.get.ask[Boolean](
        RemoveExecutor(executorId, new ExecutorLossReason(reason))
      ).onFailure { case e =>
        logWarning(s"Unable to notify the driver due to " + e.getMessage, e)
      }(ThreadUtils.sameThread)
    }

    System.exit(code)
  }
}

private[spark] object CoarseGrainedExecutorBackend extends Logging {

  private def run(
      driverUrl: String,
      executorId: String,
      hostname: String,
      cores: Int,
      appId: String,
      workerUrl: Option[String],
      userClassPath: Seq[URL]) {

    Utils.initDaemon(log)
    // 使用Hadoop UserGroupInformation作为线程局部变量（分布到子线程）运行给定函数，用于验证HDFS和YARN调用。 重要提示：
    // 如果此功能将在同一进程中重复使用，则需要查看https://issues.apache.org/jira/browse/HDFS-3545，并且可能需要执行
    // FileSystem.closeAllForUGI才能避免文件系统泄露
    SparkHadoopUtil.get.runAsSparkUser { () =>
      // Debug code
      Utils.checkHost(hostname)

      // Bootstrap to fetch the driver's Spark properties.
      val executorConf = new SparkConf
      val port = executorConf.getInt("spark.executor.port", 0)
      // 创建RpcEnv 作为一个客户端
      val fetcher = RpcEnv.create(
        "driverPropsFetcher",
        hostname,
        port,
        executorConf,
        new SecurityManager(executorConf),
        clientMode = true)
      // 通过URL的方式获取driver的RpcRef
      val driver = fetcher.setupEndpointRefByURI(driverUrl)
      // 给CoarseGrainedSchedulerBackend发送一条RetrieveSparkAppConfig消息 并等待其返回结果
      val cfg = driver.askWithRetry[SparkAppConfig](RetrieveSparkAppConfig)
      // 获取spark的配置参数
      val props = cfg.sparkProperties ++ Seq[(String, String)](("spark.app.id", appId))
      // 关闭RpcEnv
      fetcher.shutdown()

      // Create SparkEnv using properties we fetched from the driver.
      val driverConf = new SparkConf()
      for ((key, value) <- props) {
        // this is required for SSL in standalone mode
        // 返回在启动时是否将给定的配置传递给executor。 当连接到scheduler时，executor需要某些身份验证配置，
        // 而其余的spark配置可以从后面的驱动程序中继承
        if (SparkConf.isExecutorStartupConf(key)) {
          driverConf.setIfMissing(key, value)
        } else {
          driverConf.set(key, value)
        }
      }
      // 更新yarn的证书
      if (driverConf.contains("spark.yarn.credentials.file")) {
        logInfo("Will periodically update credentials from: " +
          driverConf.get("spark.yarn.credentials.file"))
        SparkHadoopUtil.get.startCredentialUpdater(driverConf)
      }
      // 为executor创建一个SparkEnv。 在粗粒度模式下，执行程序提供已经实例化的RpcEnv。
      val env = SparkEnv.createExecutorEnv(
        driverConf, executorId, hostname, port, cores, cfg.ioEncryptionKey, isLocal = false)
      // 创建 Executor 的RpcEndpointRef CoarseGrainedExecutorBackend即是 executor endpoint
      env.rpcEnv.setupEndpoint("Executor", new CoarseGrainedExecutorBackend(
        env.rpcEnv, driverUrl, executorId, hostname, cores, userClassPath, env))
      workerUrl.foreach { url =>
        env.rpcEnv.setupEndpoint("WorkerWatcher", new WorkerWatcher(env.rpcEnv, url))
      }
      env.rpcEnv.awaitTermination()
      // 停止执行凭据更新的线程
      SparkHadoopUtil.get.stopCredentialUpdater()
    }
  }

  /**
    * worker中会使用ProcessBuilder来调用这里的main函数来启动CoarseGrainedExecutorBackend
    * @param args
    */
  def main(args: Array[String]) {
    var driverUrl: String = null
    var executorId: String = null
    var hostname: String = null
    var cores: Int = 0
    var appId: String = null
    var workerUrl: Option[String] = None
    val userClassPath = new mutable.ListBuffer[URL]()

    var argv = args.toList
    // 解析参数
    while (!argv.isEmpty) {
      argv match {
        case ("--driver-url") :: value :: tail =>
          driverUrl = value
          argv = tail
        case ("--executor-id") :: value :: tail =>
          executorId = value
          argv = tail
        case ("--hostname") :: value :: tail =>
          hostname = value
          argv = tail
        case ("--cores") :: value :: tail =>
          cores = value.toInt
          argv = tail
        case ("--app-id") :: value :: tail =>
          appId = value
          argv = tail
        case ("--worker-url") :: value :: tail =>
          // Worker url is used in spark standalone mode to enforce fate-sharing with worker
          workerUrl = Some(value)
          argv = tail
        case ("--user-class-path") :: value :: tail =>
          userClassPath += new URL(value)
          argv = tail
        case Nil =>
        case tail =>
          // scalastyle:off println
          System.err.println(s"Unrecognized options: ${tail.mkString(" ")}")
          // scalastyle:on println
          printUsageAndExit()
      }
    }

    if (driverUrl == null || executorId == null || hostname == null || cores <= 0 ||
      appId == null) {
      printUsageAndExit()
    }
    // 开始执行Executor 该run方法中会构建一个CoarseGrainedExecutorBackend实例---也就是构建了一个RPC通信终端
    run(driverUrl, executorId, hostname, cores, appId, workerUrl, userClassPath)
    System.exit(0)
  }

  private def printUsageAndExit() = {
    // scalastyle:off println
    System.err.println(
      """
      |Usage: CoarseGrainedExecutorBackend [options]
      |
      | Options are:
      |   --driver-url <driverUrl>
      |   --executor-id <executorId>
      |   --hostname <hostname>
      |   --cores <cores>
      |   --app-id <appid>
      |   --worker-url <workerUrl>
      |   --user-class-path <url>
      |""".stripMargin)
    // scalastyle:on println
    System.exit(1)
  }

}
