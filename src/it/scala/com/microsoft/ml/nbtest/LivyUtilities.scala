package com.microsoft.ml.nbtest

import java.io.{File, FileInputStream}

import com.microsoft.ml.spark.Secrets
import com.microsoft.ml.spark.build.BuildInfo
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.write
import com.microsoft.ml.spark.cognitive.RESTHelpers
import com.microsoft.ml.spark.core.env.FileUtilities
import org.apache.commons.io.IOUtils
import org.apache.http.entity.StringEntity
import org.json4s.{Formats, NoTypeHints}
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods._
import org.json4s._
import org.spark_project.guava.io.BaseEncoding

case class LivyBatch(id: Int,
                     state: String,
                     appId: Option[String],
                     appInfo: Option[JObject],
                     log: Seq[String])

case class LivyLogs(id: Int,
                    from: Int,
                    total: Int,
                    log: Seq[String])
//noinspection ScalaStyle
object LivyUtilities {

  implicit val formats: Formats = Serialization.formats(NoTypeHints)

  val NotebookFiles: Array[String] = Option(
    FileUtilities.join(BuildInfo.baseDirectory, "notebooks", "samples").getCanonicalFile.listFiles().map(file => file.getAbsolutePath)
  ).get

  lazy val Token: String = sys.env.getOrElse("MML_SYNAPSE_TOKEN", Secrets.AdbToken)

  def poll (id: Int, livyUrl: String, backoffs: List[Int] = List(100, 500, 1000)): LivyBatch = {
    val getStatsRequest = new HttpGet(s"$livyUrl/batches/$id")
    val statsResponse = RESTHelpers.safeSend(getStatsRequest, backoffs = backoffs, close=false)
    val batch = parse(IOUtils.toString(statsResponse.getEntity.getContent, "utf-8")).extract[LivyBatch]
    statsResponse.close()
    batch
  }

  def getLogs(id: Int, livyUrl: String, backoffs: List[Int] = List(100, 500, 1000)): Seq[String] = {
    val getLogsRequest = new HttpGet(s"$livyUrl/batches/$id/log?from=0&size=10000")
    val statsResponse = RESTHelpers.safeSend(getLogsRequest, backoffs=backoffs, close=false)
    val batchString = parse(IOUtils.toString(statsResponse.getEntity.getContent, "utf-8"))
    val batch = batchString.extract[LivyLogs]
    statsResponse.close()
    batch.log
  }

  def postMortem(batch: LivyBatch, livyUrl: String) : Any = {
    getLogs(batch.id, livyUrl).foreach(println)
    assert(false, write(batch))
  }

  def retry(id: Int, livyUrl: String, backoffs: List[Int]): LivyBatch = {
    val batch = poll(id, livyUrl)
    println(s"batch state $id : ${batch.state}")
    if (batch.state == "success") {
      batch
    }else{
      val waitTime : Int = backoffs.headOption.getOrElse(assert(false, "cannot poll"))
      if (batch.state == "dead"){
        postMortem(batch, livyUrl)
      }
      Thread.sleep(waitTime.toLong)
      println(s"retrying id $id")
      retry(id, livyUrl, backoffs.tail)
    }
  }

  def uploadAndSubmitNotebook(livyUrl: String, notebook: File ): Unit = {
    uploadNotebook(notebook, "destination-path")
    submitRun(livyUrl, "destination-path")
  }

  def submitRun(livyUrl: String, path: String): LivyBatch =
  {
    // MMLSpark info
    val truncatedScalaVersion: String = BuildInfo.scalaVersion
      .split(".".toCharArray.head).dropRight(1).mkString(".")
    val deploymentBuild = s"com.microsoft.ml.spark:${BuildInfo.name}_$truncatedScalaVersion:${BuildInfo.version}"
    val repository = "https://mmlspark.azureedge.net/maven"

    val sparkPackages = Array(deploymentBuild)
    val livyPayload =
      s"""
         |{
         | "file" : "$path",
         | "conf" :
         |      {
         |        "spark.jars.packages" : "${sparkPackages.map(s => s.trim).mkString(",")}",
         |        "spark.jars.repositories" : "https://mmlspark.azureedge.net/maven"
         |      }
         | }
      """.stripMargin

    val createRequest = new HttpPost(s"$livyUrl/batches")
    println(s"livy payload: $livyPayload")
    createRequest.setHeader("Content-Type", "application/json")
    createRequest.setHeader("Authorization", s"Bearer $Token")
    createRequest.setEntity(new StringEntity(livyPayload))
    val response = RESTHelpers.safeSend(createRequest, close = false)

    println(response.getEntity.getContent)
    val content = IOUtils.toString(response.getEntity.getContent, "utf-8")
    val batch = parse(content).extract[LivyBatch]
    val status = response.getStatusLine.getStatusCode
    assert(status == 201)
    batch
  }

  def uploadNotebook(file: File, dest: String): Unit = {
    val content = BaseEncoding.base64().encode(
      IOUtils.toByteArray(new FileInputStream(file)))
  }

}
