package actors

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.event.LoggingReceive
import play.api.libs.json.{JsSuccess, JsResult, JsValue, Json}
import akka.actor.ActorRef
import akka.actor.Props
import play.libs.Akka
import scala.xml.Utility
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.collection.mutable.{Map,Queue}
//import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.concurrent.Execution.Implicits._

case class ComminicationError(message: String) extends Exception(message)

class UserActor(uid: String, board:ActorRef, out:ActorRef) extends Actor with ActorLogging {
  implicit val timeout = Timeout(10 seconds)

  private var userspool = Set[ActorRef]()
  private var vectorC =  Map[String, Int]()
  private var messageBuffer = Map[String, Queue[Message]]()
  private var userIDmap = Map[ActorRef, String]()
//  private val infinity:Int = 100000000

  override def preStart() = {
    BoardActor() ! Subscribe(uid)
    val future = ask(BoardActor(), AcquireUsers).mapTo[Set[ActorRef]]
    val users = Await.result(future, timeout.duration)
    userspool = users
    val future1 = ask(BoardActor(), AcquireUsersID).mapTo[Set[String]]
    val usersID = Await.result(future1, timeout.duration)
    for (eachuser <- usersID)
      vectorC += (eachuser -> 0)

    val future2 = ask(BoardActor(), AcquireUserIDMap).mapTo[Map[ActorRef, String]]
    userIDmap = Await.result(future2, timeout.duration)

    for (user <- userspool)
      if (user != self)
        user ! newUser(self,uid)


  }

  def biggerVC(vc1:Map[String, Int], vc2:Map[String, Int], uid1:String, uid2:String): Boolean = {
    for (eachkey <- vc1.keysIterator) {
      if (eachkey != uid1 && eachkey != uid2)
//        if (vc1.get(eachkey).get != infinity && vc2.get(eachkey).get != infinity)
        if (vc1.get(eachkey).get > vc2.get(eachkey).get)
          return true
    }
    return false
  }

  def buffer(m:Message): Unit = {
    if (messageBuffer.get(m.uuid).isDefined)
      messageBuffer.get(m.uuid).get += m
    else {
      messageBuffer += (m.uuid -> Queue(m))

    }

  }

  def createRunnable(u:ActorRef, m:JsResult[String], uid:String, vc:Map[String,Int]): Runnable = {
    val aRunnable = new Runnable {
      override def run(): Unit = {
        m map (u ! Message(uid, vc, _))
      }
    }
    return aRunnable

  }

  def deliverM(m:Message): Unit = {
    val js = Json.obj("type" -> "message", "uid" -> m.uuid, "msg" -> m.s)
    out ! js
//    messageBuffer -= m
    vectorC.update(uid, vectorC.get(uid).get+1)
    vectorC.update(m.uuid, m.senderClock.get(m.uuid).get)

  }

//    if (uid == "2"){
//      print("My local vector is ", vectorC, "My buffer is", messageBuffer)
//    }

  def cleanBuffer():Int = {
    for (eachsender <- messageBuffer.keysIterator) {
      val thisqueue = messageBuffer.get(eachsender).get
      if (thisqueue.nonEmpty) {
        if (! biggerVC(thisqueue.front.senderClock, vectorC, uid, thisqueue.front.uuid)) {
          deliverM(thisqueue.dequeue())
          return 1
        }
      }
    }
    return 0

  }


  def receive = LoggingReceive {
    case Message(muid, senderVC, s) => {
//      print(uid, muid, vectorC)
//      printf("%s get a message from %s\n", uid, muid)
//      print(vectorC)
//      print(userspool)
//      val js = Json.obj("type" -> "message", "uid" -> muid, "msg" -> s)
//      print(senderVC)
      if (! biggerVC(senderVC, vectorC, uid, muid)) {
        deliverM(Message(muid, senderVC, s))
        var returnsatus=1
        while (returnsatus == 1)
          returnsatus = cleanBuffer()
      } else {
        buffer(Message(muid, senderVC, s))
//        print(messageBuffer)
      }
//      out ! js
    }

    case MessageToMyself(muid, s)  => {
      val js = Json.obj("type" -> "message", "uid" -> muid, "msg" -> s)
      out ! js

    }
    case js: JsValue => {
//      printf("%s print vectorC:\n", uid)
//      print(vectorC)
//      print(userspool)
      val newClock:Int = vectorC.get(uid).get + 1
      vectorC.update(uid,newClock)


      val m = (js \ "msg").validate[String] map { Utility.escape(_) }
//      print(m)
      for (u <- userspool) {
        if (u == self)
          m map (u ! MessageToMyself(uid, _))
        else
          if (uid == "1" && userIDmap.get(u).get == "2") {
            val saveMyOldVector = vectorC.clone()
            Akka.system().scheduler.scheduleOnce(10 seconds, createRunnable(u, m, uid, saveMyOldVector))
          }
          else {
            print(uid, "is sending message of ", m map {Message(uid, vectorC.clone(), _)})
            m map (u ! Message(uid, vectorC.clone(), _))
          }
      }


//        if (u != self)
//          m map (u ! Message(uid, vectorC, _ ))
//        else
//          m map (u ! MessageToMyself(uid, _))
//        if (uid != "1") {
//          if (u != self) {
//            m map (u ! Message(uid, vectorC, _))
//            if (uid == "3")
//              print(uid, " local clock is", vectorC)
//          }
//          else {
//            m map (u ! MessageToMyself(uid, _))
//          }
//
//        }
//        else {
//          if (u == self)
//            m map (u ! MessageToMyself(uid, _))
//          else
//            if (userIDmap.get(u).get == "2") {
//              val saveMyOldVector = vectorC.clone()
//              Akka.system().scheduler.scheduleOnce(10 seconds, createRunnable(u, m, uid, saveMyOldVector))
//            }
//            else
//              m map (u ! Message(uid, vectorC, _))
//        }

    }
    case nu:newUser => {
      userspool += nu.user
      vectorC += (nu.id -> 0)
      userIDmap += (nu.user -> nu.id)
//      sender ! myClock(uid, vectorC.get(uid).get) // tell my new user my current clock

    }

//    case mc:myClock => {
//      vectorC.update(mc.uid, mc.clock)
//      for (eachm <- messageBuffer)
//        if(! biggerVC(eachm.senderClock, vectorC, uid, eachm.uuid))
//          deliverM(eachm)
//    }

    case other => log.error("unhandled: " + other)
  }
}

object UserActor {
  def props(uid: String)(out: ActorRef) = Props(new UserActor(uid, BoardActor(), out))
}

case class myClock(uid:String, clock:Int)
