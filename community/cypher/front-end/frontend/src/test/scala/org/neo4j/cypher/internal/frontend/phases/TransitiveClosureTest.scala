/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.frontend.phases

import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.frontend.phases.rewriting.cnf.CNFNormalizer
import org.neo4j.cypher.internal.frontend.phases.rewriting.cnf.CNFNormalizerTest.SemanticWrapper
import org.neo4j.cypher.internal.rewriting.ListStepAccumulator
import org.neo4j.cypher.internal.util.StepSequencer
import org.neo4j.cypher.internal.util.helpers.NameDeduplicator.removeGeneratedNamesAndParamsOnTree
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class TransitiveClosureTest extends CypherFunSuite with AstConstructionTestSupport with RewritePhaseTest {

  private val removeGeneratedNames = new Transformer[BaseContext, BaseState, BaseState] {
    override def transform(from: BaseState, context: BaseContext): BaseState = from.withStatement(removeGeneratedNamesAndParamsOnTree(from.statement()))
    override def name: String = "do nothing"
    override def postConditions: Set[StepSequencer.Condition] = Set.empty
  }

  val cnfNormalizer: Transformer[BaseContext, BaseState, BaseState] =
    StepSequencer(ListStepAccumulator[Transformer[BaseContext, BaseState, BaseState] with StepSequencer.Step]())
      .orderSteps(
        CNFNormalizer.phaseSteps ++ Set(SemanticWrapper),
        Set.empty
      )
      .steps
      .reduceLeft[Transformer[BaseContext, BaseState, BaseState]]((t1, t2) => t1 andThen t2)

  override def rewriterPhaseUnderTest: Transformer[BaseContext, BaseState, BaseState] =
    transitiveClosure andThen cnfNormalizer andThen removeGeneratedNames

  override def rewriterPhaseForExpected: Transformer[BaseContext, BaseState, BaseState] =
    cnfNormalizer andThen removeGeneratedNames

  test("MATCH (a)-->(b) WHERE a.prop = b.prop AND b.prop = 42") {
    assertRewritten(
      "MATCH (a)-->(b) WHERE a.prop = b.prop AND b.prop = 42 RETURN a",
      "MATCH (a)-->(b) WHERE a.prop = 42 AND b.prop = 42 RETURN a")
  }

  // pending fix in frontend
  test("MATCH (a)-->(b) WHERE NOT a.prop = b.prop AND b.prop = 42") {
    assertNotRewritten(
      "MATCH (a)-->(b) WHERE NOT a.prop = b.prop AND b.prop = 42 RETURN a")
  }

  // pending fix in frontend
  test("MATCH (a)-->(b) WHERE a.prop = b.prop AND NOT b.prop = 42") {
    assertNotRewritten(
      "MATCH (a)-->(b) WHERE a.prop = b.prop AND NOT b.prop = 42 RETURN a")
  }

  test("MATCH (a)-->(b) WHERE NOT (a.prop = b.prop AND b.prop = 42)") {
    assertRewritten(
      "MATCH (a)-->(b) WHERE NOT (a.prop = b.prop AND b.prop = 42) RETURN a",
      "MATCH (a)-->(b) WHERE NOT (a.prop = 42 AND b.prop = 42) RETURN a")
  }

  test("MATCH (a)-->(b) WHERE b.prop = a.prop AND b.prop = 42") {
    assertRewritten(
      "MATCH (a)-->(b) WHERE b.prop = a.prop AND b.prop = 42 RETURN a",
      "MATCH (a)-->(b) WHERE b.prop = 42 AND a.prop = 42 RETURN a")
  }

  test("MATCH (a)-->(b) WHERE a.prop = b.prop OR b.prop = 42") {
    assertNotRewritten("MATCH (a)-->(b) WHERE a.prop = b.prop OR b.prop = 42 RETURN a")
  }

  test("MATCH (a)-->(b) WHERE a.prop = b.prop AND b.prop = b.prop2 AND b.prop2 = 42") {
    assertRewritten(
      "MATCH (a)-->(b) WHERE a.prop = b.prop AND b.prop = b.prop2 AND b.prop2 = 42 RETURN a",
      "MATCH (a)-->(b) WHERE a.prop = 42 AND b.prop = 42 AND b.prop2 = 42 RETURN a")
  }

  test("MATCH (a)-->(b) WHERE b.prop2 = 42 AND a.prop = b.prop AND b.prop = b.prop2") {
    assertRewritten(
      "MATCH (a)-->(b) WHERE b.prop2 = 42 AND a.prop = b.prop AND b.prop = b.prop2 RETURN a",
      "MATCH (a)-->(b) WHERE b.prop2 = 42 AND a.prop = 42 AND b.prop = 42 RETURN a")
  }

  test("MATCH (a)-->(b) WHERE (a.prop = b.prop AND b.prop = 42) OR (a.prop = b.prop2 AND b.prop2 = 42)") {
    assertRewritten(
      "MATCH (a)-->(b) WHERE (a.prop = b.prop AND b.prop = 42) OR (a.prop = b.prop2 AND b.prop2 = 42) RETURN a",
      "MATCH (a)-->(b) WHERE (a.prop = 42 AND b.prop = 42) OR (a.prop = 42 AND b.prop2 = 42) RETURN a")
  }

  test("MATCH (a)-->(b) WHERE (a.prop = b.prop AND b.prop = 42) OR (a.prop = b.prop AND b.prop2 = 43) OR (a.prop = b.prop AND b.prop2 = 44)") {
    assertRewritten(
      "MATCH (a)-->(b) WHERE (a.prop = b.prop AND b.prop = 42) OR (a.prop = b.prop AND b.prop = 43) OR (a.prop = b.prop AND b.prop = 44) RETURN a",
      "MATCH (a)-->(b) WHERE (a.prop = 42 AND b.prop = 42) OR (a.prop = 43 AND b.prop = 43) OR (a.prop = 44 AND b.prop = 44) RETURN a")
  }

  // Transitive closure inside EXISTS subquery

  test("MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE a.prop = b.prop AND b.prop = 42}") {
    assertRewritten(
      "MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE a.prop = b.prop AND b.prop = 42} RETURN a",
      "MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE a.prop = 42 AND b.prop = 42} RETURN a")
  }

  test("MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE NOT (a.prop = b.prop AND b.prop = 42)}") {
    assertRewritten(
      "MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE NOT (a.prop = b.prop AND b.prop = 42)} RETURN a",
      "MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE NOT (a.prop = 42 AND b.prop = 42)} RETURN a")
  }

  test("MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE a.prop = b.prop OR b.prop = 42}") {
    assertNotRewritten("MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE a.prop = b.prop OR b.prop = 42} RETURN a")
  }

  // Should not leak inner predicates to the outside

  test("MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE a.prop = b.prop} AND a.prop = 42") {
    assertNotRewritten( "MATCH (a) WHERE EXISTS {MATCH (a)-->(b) WHERE a.prop = b.prop} AND a.prop = 42 RETURN a")
  }

  test("MATCH (a) WHERE a.prop = 42 AND EXISTS {MATCH (a)-->(b) WHERE a.prop = b.prop}") {
    assertNotRewritten( "MATCH (a) WHERE a.prop = 42 AND EXISTS {MATCH (a)-->(b) WHERE a.prop = b.prop} RETURN a")
  }

  test("MATCH (a)-->(b) WHERE EXISTS {MATCH (a) WHERE a.prop = 42} AND a.prop = b.prop") {
    assertNotRewritten( "MATCH (a)-->(b) WHERE EXISTS {MATCH (a) WHERE a.prop = 42} AND a.prop = b.prop RETURN a")
  }

  test("MATCH (a)-->(b) WHERE a.prop = b.prop AND EXISTS {MATCH (a) WHERE a.prop = 42}") {
    assertNotRewritten( "MATCH (a)-->(b) WHERE a.prop = b.prop AND EXISTS {MATCH (a) WHERE a.prop = 42} RETURN a")
  }

  // Test for circular rewrites
  test("MATCH (n) WHERE (n:L) AND n.p = (n.p = $x) RETURN n") {
    assertNotRewritten(
      "MATCH (n) WHERE (n:L) AND n.p = (n.p = $x) RETURN n"
    )
  }

  test("MATCH (n) WHERE (n:L) AND n.p = (n.p = n.p) RETURN n") {
    assertNotRewritten(
      "MATCH (n) WHERE (n:L) AND n.p = (n.p = n.p) RETURN n"
    )
  }

  test("MATCH (n)-->(a) WHERE a.p = 1 AND n.p = (a.p = (n.p = n.p)) RETURN n") {
    assertNotRewritten(
      "MATCH (n)-->(a) WHERE a.p = 1 AND n.p = (a.p = (n.p = n.p)) RETURN n"
    )
  }

  test("MATCH (n) WHERE (n:L) AND n.p = (n.p = (n.p = $x)) RETURN n") {
    assertNotRewritten(
      "MATCH (n) WHERE (n:L) AND n.p = (n.p = (n.p = $x)) RETURN n"
    )
  }

  test("MATCH (n) WHERE n.p = 1 AND n.p = (n.p = n.p) RETURN n") {
    assertRewritten(
      "MATCH (n) WHERE n.p = 1 AND n.p = (n.p = n.p) RETURN n",
      "MATCH (n) WHERE n.p = (n.p = 1) AND n.p = 1 RETURN n"
    )

    assertRewritten(
      "MATCH (n) WHERE n.p = (n.p = n.p) AND n.p = 1 RETURN n",
      "MATCH (n) WHERE n.p = (n.p = 1) AND n.p = 1 RETURN n"
    )
  }
}
