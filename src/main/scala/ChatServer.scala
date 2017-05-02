import akka.actor.Actor
import akka.actor.ActorRef

import ChatServer._

class ChatServer extends Actor {
  var chatters: Map[ActorRef,String] = Map.empty

  override def receive: Actor.Receive = {
    case Login(name) => getClientForName(name) ! Login(name)
    case Join(name) => chatters += sender -> name
      println(s"$name joined the chat")
    case Leave =>
      for {
        senderName <- chatters.get(sender)
      }
        println(s"$senderName left the chat")
      chatters -= sender
    case Broadcast(msg) =>
      for {
        senderName <- chatters.get(sender)
      }
      chatters.keys foreach (_ ! Broadcast(s"$senderName: $msg"))
    case Message(name, msg) =>
      for {
        senderName <- chatters.get(sender)
      }
      chatters foreach {
      case (key, value) =>
        if(value==name)
        {
          key ! Message(senderName, msg)
        } 
      }
  }

  def getClientForName (name: String): ActorRef = {
    chatters foreach {
      case (key, value) =>
        if(value==name)
        {
         return key
        } 
      }
      null
  }
}

object ChatServer {
  sealed trait ChatServerMsg
  case class Join(name: String) extends ChatServerMsg
  case object Leave
  case class Broadcast(msg: String)
  case class Message(name: String, msg: String)
  case class Login(name: String)
  case class SendMessage(name: String, msg: String)
  case object History
}