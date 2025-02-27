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
package org.neo4j.cypher.internal.frontend

import org.neo4j.cypher.internal.ast.semantics.SemanticError
import org.neo4j.cypher.internal.frontend.helpers.ErrorCollectingContext
import org.neo4j.cypher.internal.frontend.helpers.ErrorCollectingContext.failWith
import org.neo4j.cypher.internal.frontend.helpers.NoPlannerName
import org.neo4j.cypher.internal.frontend.phases.InitialState
import org.neo4j.cypher.internal.frontend.phases.Parsing
import org.neo4j.cypher.internal.frontend.phases.SemanticAnalysis
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.symbols.CTInteger
import org.neo4j.cypher.internal.util.symbols.CTString
import org.neo4j.cypher.internal.util.symbols.CypherType
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class SemanticAnalysisTest extends CypherFunSuite {

  // This test invokes SemanticAnalysis twice because that's what the production pipeline does
  private val pipeline = Parsing andThen SemanticAnalysis(warn = true) andThen SemanticAnalysis(warn = false)

  test("can inject starting semantic state") {
    val query = "RETURN name AS name"
    val startState = initStartState(query, Map("name" -> CTString))

    val context = new ErrorCollectingContext()
    pipeline.transform(startState, context)

    context.errors shouldBe empty
  }

  test("can inject starting semantic state for larger query") {
    val query = "MATCH (n:Label {name: name}) WHERE n.age > age RETURN n.name AS name"

    val startState = initStartState(query, Map("name" -> CTString, "age" -> CTInteger))
    val context = new ErrorCollectingContext()
    pipeline.transform(startState, context)

    context.errors shouldBe empty
  }

  test("should fail for max() with no arguments") {
    val query = "RETURN max() AS max"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()
    pipeline.transform(startState, context)

    context should failWith("Insufficient parameters for function 'max'")
  }

  test("Should allow overriding variable name in RETURN clause with an ORDER BY") {
    val query = "MATCH (n) RETURN n.prop AS n ORDER BY n + 2"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors shouldBe empty
  }

  test("Should not allow multiple columns with the same name in WITH") {
    val query = "MATCH (n) WITH n.prop AS n, n.foo AS n ORDER BY n + 2 RETURN 1 AS one"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors.map(_.msg) should equal(List("Multiple result columns with the same name are not supported"))
  }

  test("Should not allow duplicate variable name") {
    val query = "CREATE (n),(n) RETURN 1 as one"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors.map(_.msg) should equal(List("Variable `n` already declared"))
  }

  test("Should not allow Distinct in functions that aren't aggregate") {
    val nonAggregateFunctions = Seq(
      ("localdatetime", "'param1'"),
      ("duration", "'param1'"),
      ("left", "'param1', 4"),
      ("right", "'param1', 4"),
      ("reverse", "'param1'"),
      ("trim", "'param1'"),
      ("ceil", "0.1"),
      ("floor", "0.1"),
      ("sign", "0.1"),
      ("round", "0.1"),
      ("abs", "0.1"),
      ("asin", "0.1"),
      ("isEmpty", "'param1'"),
      ("toBoolean", "'param1'")
    )
    nonAggregateFunctions.foreach {
      case (func, params) =>
        val query = s"RETURN $func(DISTINCT $params) as foo"

        val context = new ErrorCollectingContext()
        pipeline.transform(initStartState(query, Map.empty), context)

        context.errors shouldBe Seq(SemanticError(s"Invalid use of DISTINCT with function '$func'", InputPosition(7, 1, 8)))
    }
  }

  test("Should allow parameter as valid predicate in FilteringExpression") {
    val queries = Seq(
      "RETURN [x IN [1,2,3] WHERE $p | x + 1] AS foo",
      "RETURN all(x IN [1,2,3] WHERE $p) AS foo",
      "RETURN any(x IN [1,2,3] WHERE $p) AS foo",
      "RETURN none(x IN [1,2,3] WHERE $p) AS foo",
      "RETURN single(x IN [1,2,3] WHERE $p) AS foo",
    )
    queries.foreach { query =>
      withClue(query) {
        val context = new ErrorCollectingContext()
        pipeline.transform(initStartState(query, Map.empty).withParams(Map("p" -> 42)), context)
        context.errors shouldBe empty
      }
    }
  }

  test("Should allow pattern as valid predicate in FilteringExpression") {
    val queries = Seq(
      "MATCH (n) RETURN [x IN [1,2,3] WHERE (n)--() | x + 1] AS foo",
      "MATCH (n) RETURN all(x IN [1,2,3] WHERE (n)--()) AS foo",
      "MATCH (n) RETURN any(x IN [1,2,3] WHERE (n)--()) AS foo",
      "MATCH (n) RETURN none(x IN [1,2,3] WHERE (n)--()) AS foo",
      "MATCH (n) RETURN single(x IN [1,2,3] WHERE (n)--()) AS foo",
    )
    queries.foreach { query =>
      withClue(query) {
        val context = new ErrorCollectingContext()
        pipeline.transform(initStartState(query, Map.empty), context)
        context.errors shouldBe empty
      }
    }
  }

  // Escaped backticks in tokens

  test("Should allow escaped backticks in node property key name") {
    // Property without escaping: `abc123``
    val query = "CREATE ({prop: 5, ```abc123`````: 1})"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors should be(empty)
  }

  test("Should allow escaped backticks in relationship property key name") {
    // Property without escaping: abc`123
    val query = "MATCH ()-[r]->() RETURN r.`abc``123` as result"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors should be(empty)
  }

  test("Should allow escaped backticks in label") {
    // Label without escaping: `abc123
    val query = "MATCH (n) SET n:```abc123`"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors should be(empty)
  }

  test("Should allow escaped backtick in relationship type") {
    // Relationship type without escaping: abc123``
    val query = "MERGE ()-[r:`abc123`````]->()"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors should be(empty)
  }

  test("Should allow escaped backtick in indexes") {
    // Query without proper escaping: CREATE INDEX `abc`123`` FOR (n:`Per`son`) ON (n.first``name`, n.``last`name)
    val query = "CREATE INDEX ```abc``123````` FOR (n:```Per``son```) ON (n.`first````name```, n.`````last``name`)"
    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors should be(empty)
  }

  test("Should allow escaped backtick in constraints") {
    // Query without proper escaping: CREATE CONSTRAINT abc123` ON (n:``Label) ASSERT (n.pr``op) IS NODE KEY
    val query = "CREATE CONSTRAINT `abc123``` ON (n:`````Label`) ASSERT (n.`pr````op`) IS NODE KEY"

    val startState = initStartState(query, Map.empty)
    val context = new ErrorCollectingContext()

    pipeline.transform(startState, context)

    context.errors should be(empty)
  }

  private def initStartState(query: String, initialFields: Map[String, CypherType]) =
    InitialState(query, None, NoPlannerName, new AnonymousVariableNameGenerator, initialFields)
}
