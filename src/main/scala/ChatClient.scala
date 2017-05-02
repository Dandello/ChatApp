import akka.actor.Actor
import ChatServer._
import akka.actor.ActorRef
import scala.collection.mutable.ArrayBuffer

class ChatClient(clientName: String, chatServer: ActorRef) extends Actor {
  var history: ArrayBuffer[String] = ArrayBuffer.empty[String]

  def receive: Actor.Receive = {
  	case SendMessage(name, msg) => 
      history += s"$clientName -> $msg"
      chatServer ! Message (name, msg)
  	case Leave => chatServer ! Leave
    case History => history.foreach(println)
  	case Message(senderName, msg) => 
      history += s"$senderName -> $msg"
  		println(s"$clientName got message from $senderName with text: $msg")
    case Broadcast(msg) =>
      println(s"$clientName: received $msg")
    case Login(name) =>
      println(s"$name: logged in")
  }

  chatServer ! Join(clientName)
  //chatServer ! Message (s"client1", s"Hi, I'm $name")
  //chatServer ! Broadcast(s"Hi! I'm $name")
}