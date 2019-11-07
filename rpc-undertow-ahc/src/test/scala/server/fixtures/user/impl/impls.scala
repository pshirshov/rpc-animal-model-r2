package server.fixtures.user.impl

import izumi.functional.bio.BIO
import izumi.functional.bio.BIO._
import server.fixtures.generated.ICalc
import server.fixtures.generated.ICalc._


class CalcServerImpl[F[+ _, + _] : BIO, Ctx] extends ICalc.Interface[F, Ctx] {
  override def sum(c: Ctx, a: Int, b: Int): F[Nothing, Int] = {
    F.pure(a + b)
  }

  override def div(c: Ctx, a: Int, b: Int): F[ZeroDivisionError, Int] = {
    if (b != 0) {
      F.pure(a / b)
    } else {
      F.fail(ZeroDivisionError())
    }
  }
}
//
//class IAccountServerImpl[F[+ _, + _] : BIO, Ctx] extends IAccountServer[F, Ctx] {
//  private var balance: Int = 0
//
//  override def topup(c: Ctx, a: Int): F[Nothing, Int] = {
//    for {
//      _ <- F.sync(balance += a)
//    } yield {
//      balance
//    }
//
//  }
//
//  override def spend(c: Ctx, a: Int, comment: String): F[NotEnoughMoney, Int] = {
//    if (balance >= a) {
//      for {
//        _ <- F.sync(balance += a)
//      } yield {
//        balance
//      }
//    } else {
//      F.fail(NotEnoughMoney(balance))
//    }
//  }
//}
