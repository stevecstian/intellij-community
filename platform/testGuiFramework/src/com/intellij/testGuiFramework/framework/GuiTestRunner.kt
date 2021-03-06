/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.testGuiFramework.framework

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Ref
import com.intellij.testGuiFramework.impl.GuiTestStarter
import com.intellij.testGuiFramework.impl.GuiTestThread
import com.intellij.testGuiFramework.impl.GuiTestUtilKt
import com.intellij.testGuiFramework.launcher.GuiTestLocalLauncher.runIdeLocally
import com.intellij.testGuiFramework.launcher.ide.Ide
import com.intellij.testGuiFramework.remote.IdeProcessControlManager
import com.intellij.testGuiFramework.remote.server.JUnitServer
import com.intellij.testGuiFramework.remote.server.JUnitServerHolder
import com.intellij.testGuiFramework.remote.transport.*
import com.intellij.testGuiFramework.testCases.PluginTestCase.Companion.PLUGINS_INSTALLED
import com.intellij.testGuiFramework.testCases.SystemPropertiesTestCase.Companion.SYSTEM_PROPERTIES
import org.junit.Assert
import org.junit.AssumptionViolatedException
import org.junit.internal.runners.model.EachTestNotifier
import org.junit.runner.Description
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import org.junit.runner.notification.RunNotifier
import org.junit.runners.model.FrameworkMethod
import java.net.SocketException
import java.util.concurrent.TimeUnit


class GuiTestRunner internal constructor(val runner: GuiTestRunnerInterface) {

  private val SERVER_LOG = org.apache.log4j.Logger.getLogger("#com.intellij.testGuiFramework.framework.GuiTestRunner")!!
  private val criticalError = Ref(false)

  fun runChild(method: FrameworkMethod, notifier: RunNotifier) {
    if (!GuiTestStarter.isGuiTestThread())
      runOnServerSide(method, notifier)
    else
      runOnClientSide(method, notifier)
  }

  /**
   * it suites only to test one test class. IntelliJ IDEA starting with "guitest" argument and list of tests. So we cannot calculate a list
   * of tests on invoking of this method. Therefore could be launched one test only.
   *
   * We are not relaunching IDE if it has been already started. We assume that test argument passed and it is only one.
   */
  private fun runOnServerSide(method: FrameworkMethod, notifier: RunNotifier) {

    val description = runner.describeChild(method)

    val eachNotifier = EachTestNotifier(notifier, description)
    if (criticalError.get()) {
      eachNotifier.fireTestIgnored(); return
    }

    val testName = runner.getTestName(method.name)
    SERVER_LOG.info("Starting test on server side: $testName")
    val server = JUnitServerHolder.getServer()

    try {
      if (!server.isConnected()) {
        val localIde = runner.ide ?: getIdeFromAnnotation(method.declaringClass)
        SERVER_LOG.info("Starting IDE ($localIde) with port for running tests: ${server.getPort()}")
        runIde(port = server.getPort(), ide = localIde)
        if (!server.isStarted()) server.start()
      }
      val jUnitTestContainer = JUnitTestContainer(method.declaringClass, testName)
      server.send(TransportMessage(MessageType.RUN_TEST, jUnitTestContainer))
    }
    catch (e: Exception) {
      SERVER_LOG.error(e)
      notifier.fireTestIgnored(description)
      Assert.fail(e.message)
    }
    var testIsRunning = true
    while (testIsRunning) {
      try {
        val message = server.receive()
        if (message.content is JUnitInfo && message.content.testClassAndMethodName == JUnitInfo.getClassAndMethodName(description)) {
          when (message.content.type) {
            Type.STARTED -> eachNotifier.fireTestStarted()
            Type.ASSUMPTION_FAILURE -> eachNotifier.addFailedAssumption(
              (message.content.obj as Failure).exception as AssumptionViolatedException)
            Type.IGNORED -> {
              eachNotifier.fireTestIgnored(); testIsRunning = false
            }
            Type.FAILURE -> {
              //reconstruct Throwable
              val (className, messageFromException, stackTraceFromException) = message.content.obj as FailureException
              val throwable = Throwable("thrown from $className: $messageFromException")
              throwable.stackTrace = stackTraceFromException
              eachNotifier.addFailure(throwable)
            }
            Type.FINISHED -> {
              eachNotifier.fireTestFinished(); testIsRunning = false
            }
            else -> throw UnsupportedOperationException("Unable to recognize received from JUnitClient")
          }
        }
        if (message.type == MessageType.RESTART_IDE) {
          restartIde(server, getIdeFromMethod(method))
          sendRunTestCommand(method, testName, server)
        }
        if (message.type == MessageType.RESTART_IDE_AND_RESUME) {
          if (message.content !is RestartIdeAndResumeContainer) throw Exception(
            "Transport exception: Message with type RESTART_IDE_AND_RESUME should have content type RestartIdeAndResumeContainer but has a ${message.content?.javaClass?.canonicalName}")
          when (message.content.restartIdeCause) {
            RestartIdeCause.PLUGIN_INSTALLED -> {
              restartIde(server, getIdeFromMethod(method))
              sendResumeTestCommand(method, server, PLUGINS_INSTALLED)
            }
            RestartIdeCause.RUN_WITH_SYSTEM_PROPERTIES -> {
              if (message.content !is RunWithSystemPropertiesContainer) throw Exception(
                "Transport exception: message.content caused by RUN_WITH_SYSTEM_PROPERTIES should have RunWithSystemPropertiesContainer type, but have: ${message.content.javaClass.canonicalName}")
              restartIde(server, getIdeFromMethod(method), additionalJvmOptions = message.content.systemProperties)
              sendResumeTestCommand(method, server, SYSTEM_PROPERTIES)
            }
          }
        }
      }
      catch (se: SocketException) {
        //let's fail this test and move to the next one test
        SERVER_LOG.warn("Server client connection is dead. Going to kill IDE process.")
        stopServerAndKillIde(server)
        eachNotifier.addFailure(se)
        eachNotifier.fireTestFinished()
        testIsRunning = false
      }
    }
  }

  private fun getIdeFromMethod(method: FrameworkMethod): Ide {
    return runner.ide ?: getIdeFromAnnotation(method.declaringClass)
  }

  /**
   * @additionalJvmOptions - an array of key-value pairs written without -D, for example: {@code arrayOf(Pair("idea.debug.mode", "true"))
   * By default set as an empty array – no additional JVM options
   */
  private fun restartIde(server: JUnitServer, ide: Ide, additionalJvmOptions: Array<Pair<String, String>> = emptyArray()) {
    //close previous IDE
    server.send(TransportMessage(MessageType.CLOSE_IDE))
    //await to close previous process
    IdeProcessControlManager.waitForCurrentProcess(2, TimeUnit.MINUTES)
    IdeProcessControlManager.killIdeProcess()
    //restart JUnitServer to let accept a new connection
    server.stopServer()
    //start a new one IDE
    runIde(port = server.getPort(), ide = ide, additionalJvmOptions = additionalJvmOptions)
    server.start()
  }

  private fun stopServerAndKillIde(server: JUnitServer) {
    IdeProcessControlManager.killIdeProcess()
    server.stopServer()
  }

  private fun sendRunTestCommand(method: FrameworkMethod,
                                 testName: String,
                                 server: JUnitServer) {
    val jUnitTestContainer = JUnitTestContainer(method.declaringClass, testName)
    server.send(TransportMessage(MessageType.RUN_TEST, jUnitTestContainer))
  }

  private fun sendResumeTestCommand(method: FrameworkMethod,
                                    server: JUnitServer, resumeTestLabel: String) {
    val jUnitTestContainer = JUnitTestContainer(method.declaringClass, method.name, additionalInfo = resumeTestLabel)
    server.send(TransportMessage(MessageType.RESUME_TEST, jUnitTestContainer))
  }

  private fun runOnClientSide(method: FrameworkMethod, notifier: RunNotifier) {
    val testName = runner.getTestName(method.name)

    val runListener: RunListener = object : RunListener() {
      override fun testFailure(failure: Failure?) {
        LOG.info("Test failed: '$testName'")
        notifier.removeListener(this)
        super.testFailure(failure)
      }

      override fun testFinished(description: Description?) {
        LOG.info("Test finished: '$testName'")
        notifier.removeListener(this)
        super.testFinished(description)
      }

      override fun testIgnored(description: Description?) {
        LOG.info("Test ignored: '$testName'")
        notifier.removeListener(this)
        super.testIgnored(description)
      }
    }

    try {
      notifier.addListener(runListener)
      LOG.info("Starting test: '$testName'")
      //if IDE has a fatal errors from a previous test
      if (GuiTestUtilKt.fatalErrorsFromIde().isNotEmpty() or GuiTestUtil.doesIdeHaveFatalErrors()) {
        val restartIdeMessage = TransportMessage(MessageType.RESTART_IDE,
                                                 "IDE has fatal errors from previous test, let's start a new instance")
        GuiTestThread.client?.send(restartIdeMessage) ?: throw Exception("JUnitClient is accidentally null")
      }
      else {
        if (!GuiTestStarter.isGuiTestThread())
          runIdeLocally() //TODO: investigate this case
        else {
          runner.doRunChild(method, notifier)
        }
      }
    }
    catch (e: Exception) {
      LOG.error(e)
      throw e
    }
  }

  /**
   * @additionalJvmOptions - an array of key-value pairs written without -D, for example: {@code arrayOf(Pair("idea.debug.mode", "true"))
   * By default set as an empty array – no additional JVM options
   */
  private fun runIde(port: Int, ide: Ide, additionalJvmOptions: Array<Pair<String, String>> = emptyArray()) {
    val testClassNames = runner.getTestClassesNames()
    if (testClassNames.isEmpty()) throw Exception("Test classes are not declared.")
    runIdeLocally(port = port,
                  ide = ide,
                  testClassNames = testClassNames,
                  additionalJvmOptions = additionalJvmOptions)
  }

  companion object {
    private val LOG = Logger.getInstance("#com.intellij.testGuiFramework.framework.GuiTestRunner")
  }

}
