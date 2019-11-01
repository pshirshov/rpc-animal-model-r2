package rpcmodel.rt.transport.http.servers.undertow.http

case class PrefixTree[K, V](values: Seq[V], children: Map[K, PrefixTree[K, V]]) {
  def findSubtree(prefix: List[K]): Option[PrefixTree[K, V]] = {
    prefix match {
      case Nil =>
        Some(this)
      case head :: tail =>
        children.get(head).map(_.findSubtreeOrRoot(tail))
    }
  }

  def findSubtreeOrRoot(prefix: List[K]): PrefixTree[K, V] = {
    prefix match {
      case Nil =>
        this
      case head :: tail =>
        children.get(head) match {
          case Some(value) =>
            value.findSubtreeOrRoot(tail)
          case None =>
            this
        }
    }
  }

  def subtreeValues: Seq[V] = {
    values ++ children.values.flatMap(_.subtreeValues)
  }
}

object PrefixTree {
  def build[P, V](pairs: Seq[(Seq[P], V)]): PrefixTree[P, V] = {
    val (currentValues, subValues) = pairs.partition(_._1.isEmpty)

    val next = subValues
      .map {
        case (k :: tail, v) =>
          (k, (tail, v))
      }
      .groupBy(_._1)
      .toSeq
      .map {
        case (k, group) =>
          k -> build(group.map(_._2))
      }
      .toMap

    PrefixTree(currentValues.map(_._2), next)
  }
}
