/*
 * Copyright 2022 Matthew Lutze
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.language.phase.unification

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast.{Ast, Kind, LevelEnv, RigidityEnv, Symbol, Type, TypeConstructor}
import ca.uwaterloo.flix.language.phase.unification.Unification.unifyTypes
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.{InternalCompilerException, Result}

object RecordUnification {

  /**
    * Performs unification on the given record rows in the given rigidity environment.
    *
    * The given types must have kind [[Kind.RecordRow]]
    */
  def unifyRows(tpe1: Type, tpe2: Type, renv: RigidityEnv, lenv: LevelEnv)(implicit flix: Flix): Result[(Substitution, List[Ast.BroadEqualityConstraint]), UnificationError] = (tpe1, tpe2) match {

    case (tvar: Type.Var, tpe) => Unification.unifyVar(tvar, tpe, renv, lenv)

    case (tpe, tvar: Type.Var) => Unification.unifyVar(tvar, tpe, renv, lenv)

    case (Type.RecordRowEmpty, Type.RecordRowEmpty) => Ok((Substitution.empty, Nil))

    case (Type.RecordRowEmpty, _) => Err(UnificationError.MismatchedTypes(tpe1, tpe2))

    case (row1@Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(_), _), _, _), restRow1, _), row2) =>
      // Attempt to write the row to match.
      rewriteRecordRow(row2, row1, renv, lenv) flatMap {
        case (subst1, restRow2, econstrs1) =>
          unifyTypes(subst1(restRow1), subst1(restRow2), renv, lenv) flatMap {
            case (subst2, econstrs2) => Result.Ok((subst2 @@ subst1, econstrs1 ++ econstrs2))
          }
      }

    case _ => throw InternalCompilerException(s"unexpected types: ($tpe1), ($tpe2)", tpe1.loc)
  }

  /**
    * Attempts to rewrite the given row type `rewrittenRow` such that it shares a first label with `staticRow`.
    */
  private def rewriteRecordRow(rewrittenRow: Type, staticRow: Type, renv: RigidityEnv, lenv: LevelEnv)(implicit flix: Flix): Result[(Substitution, Type, List[Ast.BroadEqualityConstraint]), UnificationError] = {

    def visit(row: Type): Result[(Substitution, Type, List[Ast.BroadEqualityConstraint]), UnificationError] = (row, staticRow) match {
      case (Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(field2), _), fieldType2, _), restRow2, loc),
      Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(field1), _), fieldType1, _), _, _)) =>
        // Case 1: The row is of the form { field2 :: fieldType2 | restRow2 }
        if (field1 == field2) {
          // Case 1.1: The fields match, their types must match.
          for {
            (subst, econstrs) <- unifyTypes(fieldType1, fieldType2, renv, lenv)
          } yield (subst, restRow2, econstrs)
        } else {
          // Case 1.2: The fields do not match, attempt to match with a field further down.
          visit(restRow2) map {
            case (subst, rewrittenRow, econstrs) => (subst, Type.mkRecordRowExtend(field2, fieldType2, rewrittenRow, loc), econstrs)
          }
        }
      case (tvar: Type.Var, Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(field1), _), fieldType1, _), _, _)) =>
        val tv = tvar
        // Case 2: The row is a type variable.
        if (staticRow.typeVars contains tv) {
          Err(UnificationError.OccursCheck(tv, staticRow))
        } else {
          // Introduce a fresh type variable to represent one more level of the row.
          val restRow2 = Type.freshVar(Kind.RecordRow, tvar.loc)
          val type2 = Type.mkRecordRowExtend(field1, fieldType1, restRow2, tvar.loc)
          val subst = Substitution.singleton(tv.sym, type2)
          Ok((subst, restRow2, Nil)) // TODO ASSOC-TYPES Nil right?
        }

      case (Type.Cst(TypeConstructor.RecordRowEmpty, _), Type.Apply(Type.Apply(Type.Cst(TypeConstructor.RecordRowExtend(field1), _), fieldType1, _), _, _)) =>
        // Case 3: The `field` does not exist in the record.
        Err(UnificationError.UndefinedField(field1, fieldType1, rewrittenRow))

      case _ =>
        // Case 4: The type is not a row.
        Err(UnificationError.NonRecordType(rewrittenRow))
    }

    visit(rewrittenRow)
  }
}
