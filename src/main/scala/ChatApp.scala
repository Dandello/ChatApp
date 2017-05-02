import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorRef
import util.control.Breaks._
import ChatServer._

object ChatApp extends App {
  var chatters: Map[ActorRef,String] = Map.empty
  var currentClient: ActorRef = null;

  println("///////////////\nCommands:\n-new $name - create new user\n-login $name\n-message $name $text\n-history\n-exit\n///////////////")

  val system = ActorSystem("chat-app")

  val server =
    system.actorOf(Props[ChatServer], "chat-server")

    newClient ("Ivan")
    newClient ("Peter")
    newClient ("Lena")
    newClient ("Alex")

    breakable {
      for (ln <- io.Source.stdin.getLines) {
        var myList = ln.split(" ") 
        for ( i <- 0 to (myList.length - 1)) {
           if(i == 0){
            myList(i) match {
            case "-new" => if (myList.length > 1) { newClient(myList(1))} else {println("-new $name")} // создаем клиент
            case "-login" => if (myList.length > 1) {
              currentClient = getClientForName(myList(1))
                if(currentClient == null){
                  newClient(myList(1))
                  currentClient = getClientForName(myList(1))
                }
              } else {println("-login $name")}
            case "-logout" => if (currentClient != null) {
              chatters -= currentClient
              currentClient ! Leave
              currentClient = null
              } else {println("you are not logged in")}
            case "-message" => if (myList.length > 2 && currentClient != null) {currentClient ! SendMessage(myList(1), myList(2))} else { if (currentClient != null) {println("-message $name $text")} else {println("you are not logged in")}}
            case "-history" => if (currentClient != null) {
              currentClient ! History
              } else {println("you are not logged in")}
            case "-exit" => break // выходим из цикла и завершаем приложение
            case whatever => println("Command not found")
           }
          } 
        }
      }
    }
    // если break вызвался
    system.shutdown 

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

    def newClient (name: String) = {
      val client = system.actorOf(Props(new ChatClient(name, server)), name)
      chatters += client -> name
    }
  }