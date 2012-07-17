/*
 * Copyright (C) 2011-2012 spray.cc
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

package cc.spray.routing

import cc.spray.httpx.unmarshalling.{MalformedContent, Deserializer}
import shapeless._


abstract class Directive[L <: HList] { self =>
  def happly(f: L => Route): Route

  def | (that: Directive[L]) = new Directive[L] {
    def happly(f: L => Route) = { ctx =>
      self.happly(f) {
        ctx.withRouteResponseHandling {
          case Rejected(rejections) => that.happly(f) {
            ctx.withRejectionsTransformed(rejections ++ _)
          }
        }
      }
    }
  }

  def & [L2 <: HList](that: Directive[L2])(implicit prepend : Prepend[L, L2]) = new Directive[prepend.Out] {
    def happly(f: prepend.Out => Route) =
      self.happly { values =>
        that.happly { values2 =>
          f(values ::: values2)
        }
      }
  }

  def as[T](deserializer: HListDeserializer[L, T]) = new Directive[T :: HNil] {
    def happly(f: T :: HNil => Route) =
      self.happly { values => ctx =>
        deserializer(values) match {
          case Right(t) => f(t :: HNil)(ctx)
          case Left(MalformedContent(msg)) => ctx.reject(ValidationRejection(msg))
          case Left(error) => ctx.reject(ValidationRejection(error.toString))
        }
      }
  }

  def map[HF](f: HF)(implicit m: Mapper[HF, L]): Directive[m.Out] = transformed(_.map(f))

  def flatMap[HF](f: HF)(implicit fm: FlatMapper[HF, L]): Directive[fm.Out] = transformed(_.flatMap(f))

  def transformed[L2 <: HList](f: L => L2): Directive[L2] =
    new Directive[L2] {
      def happly(g: L2 => Route) = self.happly { values => g(f(values)) }
    }
}

object Directive {
  implicit def pimpApply[L <: HList](directive: Directive[L])
                                    (implicit hac: ApplyConverter[L]): hac.In => Route = { f =>
    directive.happly(hac(f))
  }
}

abstract class Directive0 extends Directive[HNil] { self =>
  def happly(f: HNil => Route) = apply(f(HNil))

  def apply(inner: Route): Route

  override def | (that: Directive[HNil]) = new Directive0 {
    def apply(inner: Route) = { ctx =>
      self(inner) {
        ctx.withRouteResponseHandling {
          case Rejected(rejections) => that.happly(_ => inner) {
            ctx.withRejectionsTransformed(rejections ++ _)
          }
        }
      }
    }
  }
}

object Directive0 {
  def apply(f: Route => Route) = new Directive0 {
    def apply(inner: Route) = f(inner)
  }

  implicit def fromGeneral(d: Directive[HNil]) = new Directive0 {
    def apply(inner: Route) = happly(_ => inner)
  }
}