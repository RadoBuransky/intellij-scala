package org.jetbrains.sbt
package project.structure

import java.io._
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.{JarEntry, JarFile}

import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.sbt.project.structure.SbtRunner._

import scala.collection.JavaConverters._
import scala.xml.{Elem, XML}

/**
 * @author Pavel Fatin
 */
class SbtRunner(vmOptions: Seq[String], customLauncher: Option[File], customStructureDir: Option[String],
                vmExecutable: File, projectSbtVersion: Option[String]) {
  private val LauncherDir = getSbtLauncherDir
  private val SbtLauncher = customLauncher.getOrElse(LauncherDir / "sbt-launch.jar")
  private val DefaultSbtVersion = "0.13"
  private val SinceSbtVersion = "0.12.4"

  private val cancellationFlag: AtomicBoolean = new AtomicBoolean(false)

  def cancel(): Unit =
    cancellationFlag.set(true)

  def read(directory: File, download: Boolean, resolveClassifiers: Boolean, resolveSbtClassifiers: Boolean)
          (listener: (String) => Unit): Either[Exception, Elem] = {

    val options = download.seq("download") ++
            resolveClassifiers.seq("resolveClassifiers") ++
            resolveSbtClassifiers.seq("resolveSbtClassifiers")

    checkFilePresence.fold(read0(directory, options.mkString(", "))(listener))(it => Left(new FileNotFoundException(it)))
  }

  private def read0(directory: File, options: String)(listener: (String) => Unit): Either[Exception, Elem] = {
    val sbtVersion = projectSbtVersion
            .orElse(sbtVersionIn(directory))
            .orElse(implementationVersionOf(SbtLauncher))
            .getOrElse(DefaultSbtVersion)

    if (compare(sbtVersion, SinceSbtVersion) < 0) {
      val message = s"SBT $SinceSbtVersion+ required. Please update the project definition"
      Left(new UnsupportedOperationException(message))
    } else {
      read1(directory, sbtVersion, options, listener)
    }
  }

  private def checkFilePresence: Option[String] = {
    val files = Stream("SBT launcher" -> SbtLauncher)
    files.map((check _).tupled).flatten.headOption
  }

  private def check(entity: String, file: File) = (!file.exists()).option(s"$entity does not exist: $file")

  private def read1(directory: File, sbtVersion: String, options: String, listener: (String) => Unit) = {
    val majorSbtVersion = numbersOf(sbtVersion).take(2).mkString(".")
    val pluginFile = customStructureDir.map(new File(_)).getOrElse(LauncherDir) / s"sbt-structure-$majorSbtVersion.jar"

    val sbtOpts: Seq[String] = {
      val sbtOptsFile = directory / ".sbtopts"
      if (sbtOptsFile.exists && sbtOptsFile.isFile && sbtOptsFile.canRead)
        FileUtil.loadLines(sbtOptsFile).asScala.filterNot(_.startsWith("#"))
      else
        Seq.empty
    }

    usingTempFile("sbt-structure", Some(".xml")) { structureFile =>
      usingTempFile("sbt-commands", Some(".lst")) { commandsFile =>

        commandsFile.write(
          s"""set artifactPath := file("${path(structureFile)}")""",
          s"""set artifactClassifier := Some("$options")""",
          s"""apply -cp "${path(pluginFile)}" org.jetbrains.sbt.ReadProject""")

        val processCommandsRaw =
          path(vmExecutable) +:
//                    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005" +:
                  "-Djline.terminal=jline.UnsupportedTerminal" +:
                  "-Dsbt.log.noformat=true" +:
                  (vmOptions ++ sbtOpts) :+
                  s"-Dsbt.version=$sbtVersion" :+
                  "-jar" :+
                  path(SbtLauncher) :+
                  s"< ${path(commandsFile)}"
        val processCommands = processCommandsRaw.filterNot(_.isEmpty)

        try {
          val process = Runtime.getRuntime.exec(processCommands.toArray, null, directory)
          val result = handle(process, listener)
          result.map { output =>
            (structureFile.length > 0).either(
              XML.load(structureFile.toURI.toURL))(SbtException.fromSbtLog(output))
          }.getOrElse(Left(new ImportCancelledException))
        } catch {
          case e: Exception => Left(e)
        }
      }}
  }

  private def handle(process: Process, listener: (String) => Unit): Option[String] = {
    val output = new StringBuffer()

    val processListener: (OutputType, String) => Unit = {
      case (OutputType.StdOut, text) =>
        if (text.contains("(q)uit")) {
          val writer = new PrintWriter(process.getOutputStream)
          writer.println("q")
          writer.close()
        } else {
          output.append(text)
          listener(text)
        }
      case (OutputType.StdErr, text) =>
        output.append(text)
        listener(text)
    }

    val handler = new OSProcessHandler(process, null, null)
    handler.addProcessListener(new ListenerAdapter(processListener))
    handler.startNotify()

    var processEnded = false
    while (!processEnded && !cancellationFlag.get())
      processEnded = handler.waitFor(SBT_PROCESS_CHECK_TIMEOUT_MSEC)

    if (!processEnded) {
      handler.setShouldDestroyProcessRecursively(false)
      handler.destroyProcess()
      None
    } else {
      Some(output.toString)
    }
  }

  private def path(file: File): String = file.getAbsolutePath.replace('\\', '/')
}

object SbtRunner {
  class ImportCancelledException extends Exception

  val SBT_PROCESS_CHECK_TIMEOUT_MSEC = 100

  def getSbtLauncherDir: File = {
    val file: File = jarWith[this.type]
    val deep = if (file.getName == "classes") 1 else 2
    (file << deep) / "launcher"
  }

  def getDefaultLauncher = getSbtLauncherDir / "sbt-launch.jar"

  private def numbersOf(version: String): Seq[String] = version.split("\\.").toSeq

  private def compare(v1: String, v2: String): Int = numbersOf(v1).zip(numbersOf(v2)).foldLeft(0) {
    case (acc, (i1, i2)) if acc == 0 => i1.compareTo(i2)
    case (acc, _) => acc
  }

  private def implementationVersionOf(jar: File): Option[String] = {
    readManifestAttributeFrom(jar, "Implementation-Version")
  }

  private def readManifestAttributeFrom(file: File, name: String): Option[String] = {
    val jar = new JarFile(file)
    try {
      using(new BufferedInputStream(jar.getInputStream(new JarEntry("META-INF/MANIFEST.MF")))) { input =>
        val manifest = new java.util.jar.Manifest(input)
        val attributes = manifest.getMainAttributes
        Option(attributes.getValue(name))
      }
    }
    finally {
      if (jar.isInstanceOf[Closeable]) {
        jar.close()
      }
    }
  }

  private def sbtVersionIn(directory: File): Option[String] = {
    val propertiesFile = directory / "project" / "build.properties"
    if (propertiesFile.exists()) readPropertyFrom(propertiesFile, "sbt.version") else None
  }

  private def readPropertyFrom(file: File, name: String): Option[String] = {
    using(new BufferedInputStream(new FileInputStream(file))) { input =>
      val properties = new Properties()
      properties.load(input)
      Option(properties.getProperty(name))
    }
  }
}
