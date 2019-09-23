package rpcmodel.generated



object ICalc {
  sealed trait Err
  case class ZeroDivisionError() extends Err

  trait Server[F[_, _], Ctx] {
    def sum(c: Ctx, a: Int, b: Int): F[Nothing, Int]
    def div(c: Ctx, a: Int, b: Int): F[ZeroDivisionError, Int]
  }

  trait Client[F[_, _]] {
    def sum(a: Int, b: Int): F[Nothing, Int]
    def div(a: Int, b: Int): F[ZeroDivisionError, Int]
  }
}

//trait IAccountServer[F[_, _], Ctx] {
//  def topup(c: Ctx, a: Int): F[Nothing, Int]
//  def spend(c: Ctx, a: Int, comment: String): F[NotEnoughMoney, Int]
//}
//
//object IAccountServer {
//  sealed trait Err
//  case class NotEnoughMoney(balance: Int) extends Err
//}
//



