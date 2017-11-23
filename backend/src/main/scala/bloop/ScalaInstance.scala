package bloop

import java.io.File
import java.net.URLClassLoader
import java.util.Properties

import coursier._
import scalaz.\/
import scalaz.concurrent.Task

class ScalaInstance(
    val organization: String,
    val name: String,
    override val version: String,
    override val allJars: Array[File],
) extends xsbti.compile.ScalaInstance {

  override lazy val loader: ClassLoader =
    new URLClassLoader(allJars.map(_.toURI.toURL), null)

  private def isJar(filename: String): Boolean = filename.endsWith(".jar")
  private def hasScalaCompilerName(filename: String): Boolean =
    filename.startsWith("scala-compiler-")
  private def hasScalaLibraryName(filename: String): Boolean =
    filename.startsWith("scala-library-")

  override val compilerJar: File =
    allJars.find(f => isJar(f.getName) && hasScalaCompilerName(f.getName)).orNull
  override val libraryJar: File =
    allJars.find(f => isJar(f.getName) && hasScalaLibraryName(f.getName)).orNull
  override val otherJars: Array[File] = allJars.filter { file =>
    val filename = file.getName
    isJar(filename) && !hasScalaCompilerName(filename) && !hasScalaLibraryName(filename)
  }

  /** Tells us what the real version of the classloaded scalac compiler in this instance is. */
  override def actualVersion(): String = {
    // TODO: Report when the `actualVersion` and the passed in version do not match.
    Option(loader.getResource("compiler.properties")).map { url =>
      val stream = url.openStream()
      val properties = new Properties()
      properties.load(stream)
      properties.get("version.number").asInstanceOf[String]
    }.orNull
  }
}

object ScalaInstance {
  // Cannot wait to use opaque types for this
  type InstanceId = (String, String, String)
  import scala.collection.mutable
  private val instances = new mutable.HashMap[InstanceId, ScalaInstance]

  def apply(scalaOrg: String, scalaName: String, scalaVersion: String): ScalaInstance = {
    def resolveInstance: ScalaInstance = {
      val start = Resolution(Set(Dependency(Module(scalaOrg, scalaName), scalaVersion)))
      val repositories = Seq(Cache.ivy2Local, MavenRepository("https://repo1.maven.org/maven2"))
      val fetch = Fetch.from(repositories, Cache.fetch())
      val resolution = start.process.run(fetch).unsafePerformSync
      // TODO: Do something with the errors.
      //val errors: Seq[((Module, String), Seq[String])] = resolution.metadataErrors
      val localArtifacts: Seq[FileError \/ File] =
        Task.gatherUnordered(resolution.artifacts.map(Cache.file(_).run)).unsafePerformSync
      val allJars = localArtifacts.flatMap(_.toList).filter(_.getName.endsWith(".jar"))
      new ScalaInstance(scalaOrg, scalaName, scalaVersion, allJars.toArray)
    }

    val instanceId = (scalaOrg, scalaName, scalaVersion)
    instances.get(instanceId) match {
      case Some(instance) => instance
      case None =>
        val newInstance = resolveInstance
        instances.put(instanceId, newInstance)
        newInstance
    }
  }
}
