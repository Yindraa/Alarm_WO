package com.missionalarm.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeededMathQuestionGeneratorTest {
  private val generator = SeededMathQuestionGeneratorV1()

  @Test
  fun sameVersionAndOccurrenceProduceStableQuestionSet() {
    val first = generator.generate("instance-a", OCCURRENCE_ID, 10, 7, "math-v1")
    val second = generator.generate("instance-a", OCCURRENCE_ID, 10, 7, "math-v1")

    assertEquals(first, second)
    assertEquals((0..9).toList(), first.map { it.ordinal })
    assertTrue(first.all { !it.answered && it.answeredAtMs == null })
  }

  @Test
  fun operationMaskIsEnforcedAndAnswersAreExactIntegers() {
    val questions = generator.generate("instance-a", OCCURRENCE_ID, 10, 2, "math-v1")

    assertTrue(questions.all { it.operation == "SUBTRACT" })
    assertTrue(questions.all { it.correctAnswer == it.operandA - it.operandB })
    assertTrue(questions.any { it.correctAnswer < 0 })
  }

  private companion object {
    const val OCCURRENCE_ID = "b8ce9d56-1214-4cac-853d-ab9a5fbe0f74"
  }
}
