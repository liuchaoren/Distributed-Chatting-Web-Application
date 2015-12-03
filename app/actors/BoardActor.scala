package actors

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.event.LoggingReceive
import akka.actor.ActorRef
import akka.actor.Terminated
import play.libs.Akka
import akka.actor.Props
import scala.collection.mutable.Map

class BoardActor extends Actor with ActorLogging {
  var users = Set[ActorRef]()
  var usersid = Set[String]()

  def receive = LoggingReceive {
//    case m:Message => users map { _ ! m}
    case s:Subscribe => {
//      users map {_ ! newUser(sender,s.userID)}
      users += sender
      usersid += s.userID
      context watch sender

    }
    case AcquireUsers => sender ! users
    case AcquireUsersID => sender ! usersid
    case Terminated(user) => users -= user
  }
}

object BoardActor {
  lazy val board = Akka.system().actorOf(Props[BoardActor])
  def apply() = board
}

case class Message(uuid: String, senderClock: Map[String, Int], s: String)
case class MessageToMyself(uuid:String, s:String)
case class Subscribe(userID:String)
object AcquireUsers
object AcquireUsersID
case class newUser(user:ActorRef, id:String)