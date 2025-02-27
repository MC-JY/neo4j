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
package org.neo4j.cypher.internal.ast

import org.neo4j.cypher.internal.ast.semantics.Scope
import org.neo4j.cypher.internal.ast.semantics.SemanticAnalysisTooling
import org.neo4j.cypher.internal.ast.semantics.SemanticCheck
import org.neo4j.cypher.internal.ast.semantics.SemanticCheckResult
import org.neo4j.cypher.internal.ast.semantics.SemanticCheckResult.success
import org.neo4j.cypher.internal.ast.semantics.SemanticCheckable
import org.neo4j.cypher.internal.ast.semantics.SemanticError
import org.neo4j.cypher.internal.ast.semantics.SemanticExpressionCheck
import org.neo4j.cypher.internal.expressions.ExistsSubClause
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.MapProjection
import org.neo4j.cypher.internal.util.ASTNode
import org.neo4j.cypher.internal.util.InputPosition

/**
 *
 * @param includeExisting       Users must specify return items for the projection, either all variables (*), no variables (-), or explicit expressions.
 *                              Neo4j does not support the no variables case on the surface, but it may appear as the result of expanding the star (*) when no variables are in scope.
 *                              This field is true if the dash (-) was used by a user.
 *
 * @param defaultOrderOnColumns For some clauses the default order of alphabetical columns is inconvenient, primarily show command clauses.
 *                              If this field is set, the given order will be used instead of the alphabetical order.
 */
final case class ReturnItems(
                              includeExisting: Boolean,
                              items: Seq[ReturnItem],
                              defaultOrderOnColumns: Option[List[String]] = None
                            )(val position: InputPosition) extends ASTNode with SemanticCheckable with SemanticAnalysisTooling {

  def withExisting(includeExisting: Boolean): ReturnItems =
    copy(includeExisting = includeExisting)(position)

  def withDefaultOrderOnColumns(defaultOrderOnColumns: List[String]): ReturnItems =
    copy(defaultOrderOnColumns = Some(defaultOrderOnColumns))(position)

  def semanticCheck: SemanticCheck = items.semanticCheck chain ensureProjectedToUniqueIds

  def aliases: Set[LogicalVariable] = items.flatMap(_.alias).toSet

  def passedThrough: Set[LogicalVariable] = items.collect {
    case item => item.alias.collect { case ident if ident == item.expression => ident }
  }.flatten.toSet

  def mapItems(f: Seq[ReturnItem] => Seq[ReturnItem]): ReturnItems =
    copy(items = f(items))(position)

  def declareVariables(previousScope: Scope): SemanticCheck =
    when (includeExisting) {
      s => success(s.importValuesFromScope(previousScope))
    } chain items.foldSemanticCheck(item => item.alias match {
      case Some(variable) if item.expression == variable =>
        val positions = previousScope.symbol(variable.name).fold(Set.empty[InputPosition])(_.positions)
        declareVariable(variable, types(item.expression), positions, overriding = true)
      case Some(variable) =>
        declareVariable(variable, types(item.expression), overriding = true)
      case None           => state => SemanticCheckResult(state, Seq.empty)
    })

  private def ensureProjectedToUniqueIds: SemanticCheck = {
    items.groupBy(_.name).foldLeft(success) {
       case (acc, (_, groupedItems)) if groupedItems.size > 1 =>
        acc chain SemanticError("Multiple result columns with the same name are not supported", groupedItems.head.position)
       case (acc, _) =>
         acc
    }
  }

  def containsAggregate: Boolean = items.exists(_.expression.containsAggregate)
}

sealed trait ReturnItem extends ASTNode with SemanticCheckable {
  def expression: Expression
  def alias: Option[LogicalVariable]
  def name: String
  def isPassThrough: Boolean = alias.contains(expression)

  def semanticCheck: SemanticCheck = SemanticExpressionCheck.check(Expression.SemanticContext.Results, expression) chain checkForExists

  private def checkForExists: SemanticCheck = {
    val invalid: Option[Expression] = expression.folder.treeFind[Expression] { case _: ExistsSubClause => true }
    invalid.map(exp => SemanticError("The EXISTS subclause is not valid inside a WITH or RETURN clause.", exp.position))
  }
}

case class UnaliasedReturnItem(expression: Expression, inputText: String)(val position: InputPosition) extends ReturnItem {
  val alias: Option[LogicalVariable] = expression match {
    case i: LogicalVariable => Some(i.newUniqueVariable)
    case x: MapProjection => Some(x.name.newUniqueVariable)
    case _ => None
  }
  val name: String = alias.map(_.name) getOrElse { inputText.trim }
}

object AliasedReturnItem {
  def apply(v:LogicalVariable):AliasedReturnItem = AliasedReturnItem(v.copyId, v.copyId)(v.position)
}

//TODO variable should not be a Variable. A Variable is an expression, and the return item alias isn't
case class AliasedReturnItem(expression: Expression, variable: LogicalVariable)(val position: InputPosition) extends ReturnItem {
  val alias = Some(variable)
  val name: String = variable.name
}
