package wust.api.serialize

import wust.api._
import wust.ids._
import wust.graph._
import io.circe._, io.circe.generic.extras.semiauto._

object Circe extends wust.ids.serialize.Circe {

  //TODO anyval with circe?
  implicit val nodeMetaDecoder: Decoder[NodeMeta] = deriveDecoder[NodeMeta]
  implicit val nodeMetaEncoder: Encoder[NodeMeta] = deriveEncoder[NodeMeta]
  implicit val PostDecoder: Decoder[Node] = deriveDecoder[Node]
  implicit val PostEncoder: Encoder[Node] = deriveEncoder[Node]
  implicit val ConnectionDecoder01: Decoder[NodeUserEdge] = deriveDecoder[NodeUserEdge]
  implicit val ConnectionEncoder01: Encoder[NodeUserEdge] = deriveEncoder[NodeUserEdge]
  implicit val ConnectionDecoder02: Decoder[ContentEdge] = deriveDecoder[ContentEdge]
  implicit val ConnectionEncoder02: Encoder[ContentEdge] = deriveEncoder[ContentEdge]
  implicit val ConnectionDecoder1: Decoder[Edge.Child] = deriveDecoder[Edge.Child]
  implicit val ConnectionEncoder1: Encoder[Edge.Child] = deriveEncoder[Edge.Child]
  implicit val ConnectionDecoder3: Decoder[Edge.Member] = deriveDecoder[Edge.Member]
  implicit val ConnectionEncoder3: Encoder[Edge.Member] = deriveEncoder[Edge.Member]
  implicit val ConnectionDecoder4: Decoder[Edge.Author] = deriveDecoder[Edge.Author]
  implicit val ConnectionEncoder4: Encoder[Edge.Author] = deriveEncoder[Edge.Author]
  implicit val connectionDecoder5: Decoder[Edge.LabeledProperty] = deriveDecoder[Edge.LabeledProperty]
  implicit val connectionEncoder5: Encoder[Edge.LabeledProperty] = deriveEncoder[Edge.LabeledProperty]
  implicit val connectionDecoder6: Decoder[Edge.DerivedFromTemplate] = deriveDecoder[Edge.DerivedFromTemplate]
  implicit val connectionEncoder6: Encoder[Edge.DerivedFromTemplate] = deriveEncoder[Edge.DerivedFromTemplate]
  implicit val ConnectionDecoder: Decoder[Edge] = deriveDecoder[Edge]
  implicit val ConnectionEncoder: Encoder[Edge] = deriveEncoder[Edge]

  implicit val UserAssumedDecoder: Decoder[AuthUser.Assumed] = deriveDecoder[AuthUser.Assumed]
  implicit val UserAssumedEncoder: Encoder[AuthUser.Assumed] = deriveEncoder[AuthUser.Assumed]
  implicit val UserVerifiedDecoder: Decoder[AuthUser.Persisted] = deriveDecoder[AuthUser.Persisted]
  implicit val UserVerifiedEncoder: Encoder[AuthUser.Persisted] = deriveEncoder[AuthUser.Persisted]
  implicit val UserImplicitDecoder: Decoder[AuthUser.Implicit] = deriveDecoder[AuthUser.Implicit]
  implicit val UserImplicitEncoder: Encoder[AuthUser.Implicit] = deriveEncoder[AuthUser.Implicit]
  implicit val userDecoder: Decoder[AuthUser] = deriveDecoder[AuthUser]
  implicit val userEncoder: Encoder[AuthUser] = deriveEncoder[AuthUser]
  implicit val AuthenticationDecoder: Decoder[Authentication] = deriveDecoder[Authentication]
  implicit val AuthenticationEncoder: Encoder[Authentication] = deriveEncoder[Authentication]
  implicit val GraphChangesDecoder: Decoder[GraphChanges] = deriveDecoder[GraphChanges]
  implicit val GraphChangesEncoder: Encoder[GraphChanges] = deriveEncoder[GraphChanges]

  implicit val connectionContentTypeKeyDecoder: KeyDecoder[EdgeData.Type] =
    KeyDecoder[String].map(EdgeData.Type(_))
  implicit val connectionContentTypeKeyEncoder: KeyEncoder[EdgeData.Type] =
    KeyEncoder[String].contramap[EdgeData.Type](identity)

  implicit val GraphDecoder: Decoder[Graph] = deriveDecoder[Graph]
  implicit val GraphEncoder: Encoder[Graph] = deriveEncoder[Graph]
}
