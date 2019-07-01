import java.util.Base64

import sys.process._
import spray.json._
import DefaultJsonProtocol._
import org.apache.commons.io.IOUtils

object Secrets {
  private val kvName = "mmlspark-keys"
  private val subscriptionID = "ca9d21ff-2a46-4e8b-bf06-8d65242342e5"

  protected def exec(command: String): String = {
    val os = sys.props("os.name").toLowerCase
    os match {
      case x if x contains "windows" => Seq("cmd", "/C") ++ Seq(command) !!
      case _ => command !!
    }
  }

  private def getSecret(secretName: String): String = {
    println(s"fetching secret: $secretName")
    exec(s"az account set -s $subscriptionID")
    val secretJson = exec(s"az keyvault secret show --vault-name $kvName --name $secretName")
    secretJson.parseJson.asJsObject().fields("value").convertTo[String]
  }

  lazy val nexusUsername: String = getSecret("nexus-un")
  lazy val nexusPassword: String = getSecret("nexus-pw")
  lazy val pgpPublic: String = new String(Base64.getDecoder.decode(
    getSecret("pgp-public").getBytes("UTF-8")))
  lazy val pgpPrivate: String = new String(Base64.getDecoder.decode(
    getSecret("pgp-private").getBytes("UTF-8")))
  lazy val pgpPassword: String = getSecret("pgp-pw")

}