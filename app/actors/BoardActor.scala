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
  var userIDmap = Map[ActorRef, String]()

  def receive = LoggingReceive {
    case s:Subscribe => {
      users += sender
      usersid += s.userID
      userIDmap += (sender -> s.userID)
      context watch sender

    }
    case AcquireUsers => sender ! users
    case AcquireUsersID => sender ! usersid
    case AcquireUserIDMap => sender ! userIDmap
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
object AcquireUserIDMap
case class newUser(user:ActorRef, id:String)