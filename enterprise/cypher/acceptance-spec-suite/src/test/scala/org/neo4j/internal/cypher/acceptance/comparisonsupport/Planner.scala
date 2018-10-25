/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.internal.cypher.acceptance.comparisonsupport

case class Planners(planners: Planner*)

object Planners {
  implicit def plannerToPlanners(planner: Planner): Planners = Planners(planner)

  val all = Planners(Cost, Rule, Default)

  def definedBy(preParserArgs: Array[String]): Planners = Planners(all.planners.filter(_.isDefinedBy(preParserArgs)): _*)

  object Cost extends Planner(Set("COST", "IDP", "PROCEDURE"), "planner=cost")

  object Rule extends Planner(Set("RULE", "PROCEDURE"), "planner=rule")

  object Default extends Planner(Set("COST", "IDP", "RULE", "PROCEDURE"), "") {
    override def isDefinedBy(preParserArgs: Array[String]): Boolean =
      preParserArgs.toSet.forall(_.contains("planner") == false)
  }

}

case class Planner(acceptedPlannerNames: Set[String], preparserOption: String) {
  def isDefinedBy(preParserArgs: Array[String]): Boolean = preparserOption.split(" ").forall(preParserArgs.contains(_))
}