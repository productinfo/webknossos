/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package oxalis.actor

import akka.actor.{Actor, PoisonPill, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import com.typesafe.scalalogging.LazyLogging

trait Passivation extends ALogging with LazyLogging {
  this: Actor =>

  protected def passivate(receive: Receive): Receive = receive.orElse{
    // tell parent actor to send us a poisinpill
    case ReceiveTimeout =>
      logger.info(s" $self ReceiveTimeout: passivating. ")
      context.parent ! Passivate(stopMessage = PoisonPill)

    // stop
    case PoisonPill => context.stop(self.logInfo( s => s" $s PoisonPill"))
  }
}
