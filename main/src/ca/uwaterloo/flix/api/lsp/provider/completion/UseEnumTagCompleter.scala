/*
 * Copyright 2023 Xavier deSouza
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

package ca.uwaterloo.flix.api.lsp.provider.completion

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.lsp.Index
import ca.uwaterloo.flix.language.ast.TypedAst
import ca.uwaterloo.flix.language.ast.Symbol
import ca.uwaterloo.flix.language.ast.Symbol.{CaseSym, EnumSym}


object UseEnumTagCompleter extends Completer {
  override def getCompletions(context: CompletionContext)(implicit flix: Flix, index: Index, root: TypedAst.Root, delta: DeltaContext): Iterable[UseEnumTagCompletion] = {
    stripWord(context) match {
      case Some(word) =>
        getLocalEnumSyms(word).headOption match { //there should only be one possible enum symbol (I think)
          case Some(enumSym) =>
            val cases = getCases(enumSym)
            cases.map(cse => getUseEnumTagCompletion(cse)) //return each case as a completion
          case None => Nil
        }
      case None => Nil
    }
  }

  private def getCases(sym: EnumSym)(implicit flix: Flix, index: Index, root: TypedAst.Root, delta: DeltaContext): List[CaseSym] = {
    Nil
  }

  private def matchesTag(caseSym: CaseSym, tag: String): Boolean = caseSym.name.startsWith(tag)

  private def stripWord(ctx: CompletionContext): Option[String] = {
    val regex = raw"\s*use\s+(.*)".r
    ctx.prefix match {
      case regex(word) => Some(word)
      case _ => None
    }
  }

  // mostly reused from a different file I made
  private def getLocalEnumSyms(parsedWord: String)(implicit root: TypedAst.Root): List[Symbol.EnumSym] = {
    val modFragment = ModuleSymFragment.parseModuleSym(parsedWord)
    modFragment match {
      case ModuleSymFragment.Complete(modSym) => root.modules.getOrElse(modSym, Nil).collect {
        case sym: EnumSym => sym
      }
      case _ => Nil
    }
  }

  private def matches(sym: EnumSym, suffix: String): Boolean = {
    sym.name.startsWith(suffix)
  }

 private def getUseEnumTagCompletion(cse: CaseSym): UseEnumTagCompletion = {

 }

}
