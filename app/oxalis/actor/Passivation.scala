/*
 * Copyright (C) 20011-2014 Scalable minds UG (haftungsbeschränkt) & Co. KG. <http://scm.io>
 */
package oxalis.actor

import akka.actor.{Actor, PoisonPill, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate

trait Passivation extends ALogging {
  this: Actor =>

  protected def passivate(receive: Receive): Receive = receive.orElse{
    // tell parent actor to send us a poisinpill
    case ReceiveTimeout =>
      self.logInfo( s => s" $s ReceiveTimeout: passivating. ")
      context.parent ! Passivate(stopMessage = PoisonPill)

    // stop
    case PoisonPill => context.stop(self.logInfo( s => s" $s PoisonPill"))
  }
}
