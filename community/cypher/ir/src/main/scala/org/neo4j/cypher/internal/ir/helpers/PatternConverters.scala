/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.ir.helpers

import org.neo4j.cypher.internal.expressions.EveryPath
import org.neo4j.cypher.internal.expressions.NamedPatternPart
import org.neo4j.cypher.internal.expressions.NodePattern
import org.neo4j.cypher.internal.expressions.Pattern
import org.neo4j.cypher.internal.expressions.PatternElement
import org.neo4j.cypher.internal.expressions.RelationshipChain
import org.neo4j.cypher.internal.expressions.RelationshipPattern
import org.neo4j.cypher.internal.expressions.ShortestPaths
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.ShortestPathPattern
import org.neo4j.cypher.internal.ir.helpers.ExpressionConverters.RangeConvertor
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.exceptions.InternalException

object PatternConverters {

  object DestructResult { def empty = DestructResult(Seq.empty, Seq.empty, Seq.empty) }

  case class DestructResult(nodeIds: Seq[String], rels: Seq[PatternRelationship], shortestPaths: Seq[ShortestPathPattern]) {
    def addNodeId(newId: String*): DestructResult = copy(nodeIds = nodeIds ++ newId)
    def addRel(r: PatternRelationship*): DestructResult = copy(rels = rels ++ r)
    def addShortestPaths(r: ShortestPathPattern*): DestructResult = copy(shortestPaths = shortestPaths ++ r)
  }

  implicit class PatternElementDestructor(val pattern: PatternElement) extends AnyVal {
    def destructed(ignoreLabels: Boolean): DestructResult = pattern match {
      case relchain: RelationshipChain => relchain.destructedRelationshipChain(ignoreLabels)
      case node: NodePattern           => node.destructedNodePattern
    }
  }

  implicit class NodePatternConverter(val node: NodePattern) extends AnyVal {
    def destructedNodePattern =
      DestructResult(nodeIds = Seq(node.variable.get.name), Seq.empty, Seq.empty)
  }

  implicit class RelationshipChainDestructor(val chain: RelationshipChain) extends AnyVal {
    def destructedRelationshipChain(ignorePropertiesLabelsAndPredicates: Boolean): DestructResult = chain match {
      // (a)->[r]->(b)
      case RelationshipChain(NodePattern(Some(leftNodeId), Seq(), None),
      RelationshipPattern(Some(relId), relTypes, length, None, direction, _),
      NodePattern(Some(rightNodeId), Seq(), None)) =>
        val leftNode = leftNodeId.name
        val rightNode = rightNodeId.name
        val r = PatternRelationship(relId.name, (leftNode, rightNode), direction, relTypes, length.asPatternLength)
        DestructResult(Seq(leftNode, rightNode), Seq(r), Seq.empty)

      // ...->[r]->(b)
      case RelationshipChain(relChain: RelationshipChain,
      RelationshipPattern(Some(relId), relTypes, length, None, direction, _),
      NodePattern(Some(rightNodeId), Seq(), None)) =>
        val destructed = relChain.destructedRelationshipChain(ignorePropertiesLabelsAndPredicates)
        val leftNode = destructed.rels.last.right
        val rightNode = rightNodeId.name
        val newRel = PatternRelationship(relId.name, (leftNode, rightNode), direction, relTypes, length.asPatternLength)
        destructed.
          addNodeId(rightNode).
          addRel(newRel)

      // ...->[r]->(b)
      case RelationshipChain(relChain: RelationshipChain,
      RelationshipPattern(Some(relId), relTypes, length, _, direction, _),
      NodePattern(Some(rightNodeId), _, _)) if ignorePropertiesLabelsAndPredicates =>
        val destructed = relChain.destructedRelationshipChain(ignorePropertiesLabelsAndPredicates)
        val leftNode = destructed.rels.last.right
        val rightNode = rightNodeId.name
        val newRel = PatternRelationship(relId.name, (leftNode, rightNode), direction, relTypes, length.asPatternLength)
        destructed
          .addNodeId(rightNode)
          .addRel(newRel)

      // (a)->[r]->(b)
      case RelationshipChain(NodePattern(Some(leftNodeId), _, _),
      RelationshipPattern(Some(relId), relTypes, length, _, direction, _),
      NodePattern(Some(rightNodeId), _, _)) if ignorePropertiesLabelsAndPredicates =>
        val leftNode = leftNodeId.name
        val rightNode = rightNodeId.name
        val r = PatternRelationship(relId.name, (leftNode, rightNode), direction, relTypes, length.asPatternLength)
        DestructResult(Seq(leftNode, rightNode), Seq(r), Seq.empty)
    }
  }

  implicit class PatternDestructor(val pattern: Pattern) extends AnyVal {
    def destructed(anonymousVariableNameGenerator: AnonymousVariableNameGenerator): DestructResult = {
      pattern.patternParts.foldLeft(DestructResult.empty) {
        case (acc, NamedPatternPart(ident, sps@ShortestPaths(element, single))) =>
          val destructedElement: DestructResult = element.destructed(false)
          val pathName = ident.name
          val newShortest = ShortestPathPattern(Some(pathName), destructedElement.rels.head, single)(sps)
          acc.
            addNodeId(destructedElement.nodeIds:_*).
            addShortestPaths(newShortest)

        case (acc, sps@ShortestPaths(element, single)) =>
          val destructedElement = element.destructed(false)
          val newShortest = ShortestPathPattern(Some(anonymousVariableNameGenerator.nextName), destructedElement.rels.head, single)(sps)
          acc.
            addNodeId(destructedElement.nodeIds:_*).
            addShortestPaths(newShortest)

        case (acc, everyPath: EveryPath) =>
          val destructedElement = everyPath.element.destructed(false)
          acc.
            addNodeId(destructedElement.nodeIds:_*).
            addRel(destructedElement.rels:_*)

        case p =>
          throw new InternalException(s"Unknown pattern element encountered $p")
      }

    }
  }
}
