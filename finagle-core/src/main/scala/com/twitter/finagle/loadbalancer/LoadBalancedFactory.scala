package com.twitter.finagle.loadbalancer

import annotation.tailrec
import collection.mutable.ArrayBuffer
import util.Random

import com.twitter.util.{Future, Return, Time, Try}

import com.twitter.finagle.{Service, ServiceFactory}
import com.twitter.finagle.NoBrokersAvailableException

/**
 * A LoadBalancerStrategy produces a sequence of factories and weights.
 */
trait LoadBalancerStrategy {
  def apply[Req, Rep](
    factories: Seq[ServiceFactory[Req, Rep]]
  ): Seq[(ServiceFactory[Req, Rep], Float)]
}

/**
 * returns a service by applying a Seq of LoadBalancerStrategies to a Seq of
 * ServiceFactories, multiplying the weights for each factory, and choosing a factory
 * of the highest weight.
 */
class LoadBalancedFactory[Req, Rep](
    factories: Seq[ServiceFactory[Req, Rep]],
    strategies: LoadBalancerStrategy*)
  extends ServiceFactory[Req, Rep]
{
  // initialize using Time.now for predictable test behavior
  private[this] val rng = new Random(Time.now.inMillis)

  def make(): Future[Service[Req, Rep]] = {
    val available = availableOrAll
    if (available.isEmpty)
      return Future.exception(new NoBrokersAvailableException)
    val base = available map((_, 1F))
    val weightedFactories = applyWeights(base, strategies.toList)
    max(weightedFactories).make()
  }

  private[this] def availableOrAll: Seq[ServiceFactory[Req, Rep]] = {
    // We first create a snapshot since the underlying seq could
    // change out from under us
    val snapshot = factories.toSeq

    val available = snapshot filter { _.isAvailable }

    // If none are available, we load balance over all of them. This
    // is to remedy situations where the health checking becomes too
    // pessimistic.
    if (available.isEmpty)
      snapshot
    else
      available
  }

  @tailrec
  private[this] def applyWeights(
    weightedFactories: Seq[(ServiceFactory[Req, Rep], Float)],
    strategies: List[LoadBalancerStrategy]
  ): Seq[(ServiceFactory[Req, Rep], Float)] = strategies match {
    case Nil =>
      weightedFactories
    case strategy :: rest =>
      val (factories, weights) = weightedFactories unzip
      val (newFactories, newWeights) = strategy(factories) unzip
      val combinedWeights = (weights zip newWeights) map (Function.tupled { _ * _ })
      applyWeights(newFactories zip combinedWeights, rest)
  }

  private[this] def max(
    weightedFactories: Seq[(ServiceFactory[Req, Rep], Float)]
  ): ServiceFactory[Req, Rep] = {
    var maxWeight = Float.MinValue
    val maxes = new ArrayBuffer[ServiceFactory[Req, Rep]]

    weightedFactories foreach { case (factory, weight) =>
      if (weight > maxWeight) {
        maxes.clear()
        maxes += factory
        maxWeight = weight
      } else if (weight == maxWeight) {
        maxes += factory
      }
    }

    val index = if (maxes.size == 1) 0 else rng.nextInt(maxes.size)
    maxes(index)
  }

  override def isAvailable = factories.exists(_.isAvailable)

  override def close() = factories foreach { _.close() }
}
