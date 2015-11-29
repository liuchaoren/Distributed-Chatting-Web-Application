package actors

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.event.LoggingReceive
import play.api.libs.json.JsValue
import play.api.libs.json.Json
import akka.actor.ActorRef
import akka.actor.Props
import scala.xml.Utility
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await

case class ComminicationError(message: String) extends Exception(message)

class UserActor(uid: String, board: ActorRef, out: ActorRef) extends Actor with ActorLogging {
  implicit val timeout = Timeout(10 seconds)

  private var userspool = Set[ActorRef]()
  override def preStart() = {
    BoardActor() ! Subscribe
    val future = ask(BoardActor(), AcquireUsers).mapTo[Set[ActorRef]]
    val users = Await.result(future, timeout.duration)
    userspool = users
  }

  def receive = LoggingReceive {
    case Message(muid, s) => {
      val js = Json.obj("type" -> "message", "uid" -> muid, "msg" -> s)
      out ! js
    }
    case js: JsValue => {
      val m = (js \ "msg").validate[String] map { Utility.escape(_) }
      for (u <- userspool)
        m map { u ! Message(uid, _ ) }
    }
    case user: ActorRef => userspool += user
    case other => log.error("unhandled: " + other)
  }
}

object UserActor {
  def props(uid: String)(out: ActorRef) = Props(new UserActor(uid, BoardActor(), out))
}
