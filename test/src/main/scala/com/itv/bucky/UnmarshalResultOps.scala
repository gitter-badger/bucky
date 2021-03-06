package com.itv.bucky

import UnmarshalResult.{Failure, Success}
import org.scalatest.Assertions._

object UnmarshalResultOps {

  implicit class UnmarshalResultOps[T](result: UnmarshalResult[T]) {
    def success: T = result match {
      case Success(actualElem) => actualElem
      case Failure(reason, cause) =>
        val message = s"Unmarshal result ops for '$reason'"
        cause.fold(fail(message))(exception => fail(message, exception))
    }
    def failure: String = result match {
      case Success(actualElem) => fail(s"It should not convert when an invalid payload is provided: $actualElem")
      case Failure(reason, cause) => reason
    }
  }
}
