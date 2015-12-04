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
  private val infinity:Int = 100000000

  override def preStart() = {
    BoardActor() ! Subscribe(uid)
    // ask BoardActor about the users set
    val future = ask(BoardActor(), AcquireUsers).mapTo[Set[ActorRef]]
    val users = Await.result(future, timeout.duration)
    userspool = users // set of users' ActorRef
    val future1 = ask(BoardActor(), AcquireUsersID).mapTo[Set[String]]
    val usersID = Await.result(future1, timeout.duration) // set of users' id
    // initialize the vector clock when joining in, clock of itself is 0 and clocks of others is infinity
    for (eachuser <- usersID)
      vectorC += (eachuser -> infinity)
    vectorC.update(uid,0)


    val future2 = ask(BoardActor(), AcquireUserIDMap).mapTo[Map[ActorRef, String]]
    userIDmap = Await.result(future2, timeout.duration) // userIDmap is the mapping between users' ActorRef and users' id

    // tell every other users about new joined user
    for (user <- userspool)
      if (user != self)
        user ! newUser(self,uid)


  }



  // compare two given vector clocks, vc1 (sender) and vc2 (receiver). uid1 is sender id and uid2 is receiver id
  def biggerVC(vc1:Map[String, Int], vc2:Map[String, Int], uid1:String, uid2:String): Boolean = {
    for (eachkey <- vc1.keysIterator) {
      if (eachkey != uid1 && eachkey != uid2)
        if (vc1.get(eachkey).get != infinity && vc2.get(eachkey).get != infinity)
          if (vc1.get(eachkey).get > vc2.get(eachkey).get)
            return true
    }
    return false
  }


  // if causal ordering is broken, buffer message and wait
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
    vectorC.update(uid, vectorC.get(uid).get+1)
    vectorC.update(m.uuid, m.senderClock.get(m.uuid).get)

  }



  // look up buffer, and deliver deliverable messages
  def cleanBuffer():Int = {
    for (eachsender <- messageBuffer.keysIterator) {
      val thisqueue = messageBuffer.get(eachsender).get
      if (thisqueue.nonEmpty) {
        if (! biggerVC(thisqueue.front.senderClock, vectorC, thisqueue.front.uuid, uid)) {
          deliverM(thisqueue.dequeue())
          return 1
        }
      }
    }
    return 0

  }




  def receive = LoggingReceive {
    // message to other users
    case Message(muid, senderVC, s) => {
      if (! biggerVC(senderVC, vectorC, muid, uid)) {
        deliverM(Message(muid, senderVC, s))
        var returnsatus=1
        while (returnsatus == 1)
          returnsatus = cleanBuffer()
      } else {
        buffer(Message(muid, senderVC, s))
      }
    }



    // message to user itself
    case MessageToMyself(muid, s)  => {
      val js = Json.obj("type" -> "message", "uid" -> muid, "msg" -> s)
      out ! js

    }


    // receive message from client
    case js: JsValue => {
      val newClock:Int = vectorC.get(uid).get + 1
      vectorC.update(uid,newClock)
      val m = (js \ "msg").validate[String] map { Utility.escape(_) }
//       test by intentionally slowing messages from user 1 to user 2
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

//      for (u <- userspool) {
//          if (u != self)
//            m map (u ! Message(uid, vectorC.clone(), _ ))
//          else
//            m map (u ! MessageToMyself(uid, _))
//      }
    }




    // new user join in
    case nu:newUser => {
      userspool += nu.user
      vectorC += (nu.id -> 0)
      userIDmap += (nu.user -> nu.id)

    }





    case other => log.error("unhandled: " + other)
  }
}





object UserActor {
  def props(uid: String)(out: ActorRef) = Props(new UserActor(uid, BoardActor(), out))
}



