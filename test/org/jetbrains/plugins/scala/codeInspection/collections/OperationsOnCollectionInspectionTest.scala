package org.jetbrains.plugins.scala
package codeInspection.collections
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestAdapter
import com.intellij.codeInsight.CodeInsightTestCase
import org.jetbrains.plugins.scala.codeInspection.InspectionBundle

/**
 * Nikolay.Tropin
 * 5/21/13
 */
abstract class OperationsOnCollectionInspectionTest extends ScalaLightCodeInsightFixtureTestAdapter {
  val START = CodeInsightTestCase.SELECTION_START_MARKER
  val END = CodeInsightTestCase.SELECTION_END_MARKER
  val annotation = InspectionBundle.message("operation.on.collection.name")
  def hint: String

  protected def check(text: String) {
    checkTextHasError(text, annotation, classOf[OperationOnCollectionInspection])
  }

  protected def testFix(text: String, result: String, hint: String) {
    testQuickFix(text.replace("\r", ""), result.replace("\r", ""), hint, classOf[OperationOnCollectionInspection])
  }
}