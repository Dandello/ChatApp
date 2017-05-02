import language.postfixOps
import akka.actor._
import Array._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.math.Ordering.String
import scala.concurrent.ExecutionContext.Implicits._
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import akka.dispatch._
import akka.pattern.ask

case object Run
case object Disconnect
case object Reconnect
case object IsConnected
case object InGame
case class FindGame(idx: Int)
case class WantToPlay(rate:Int)
case object GameFinished
case object Ready
case object Reject
case object Playing
case object hello
case object Accept
case object GameStart
case class Refuse (num: Int)
case class SetNum(num: Int)
case class SetRate(rate: Int)
case class SetRateInterval(min_rate: Int, max_rate: Int)
case class GetRate()
case class IsConnected()
case class DecRate(dec_rate: Int)
case class IncRate(inc_rate: Int)
case class PlayerDisc(player: ActorRef, game:ActorRef, min_rate: Int, max_rate: Int)
case class GameResult(win: Boolean)
case class Sleep(duration: FiniteDuration)
//case class NewPlayer(player: ActorRef) // <- это сообщения с передаваемыми параметрами
case class NewPlayer(player: ActorRef, rate:Int) // <- это сообщения с передаваемыми параметрами
class Player(scheduler: ActorRef, ratee: Int) extends Actor {
  var Connected = 1
  var rate = ratee
  scheduler ! NewPlayer (self,rate)
  def receive = {
    case r: SetRate =>
      rate = r.rate
    case r: GetRate =>
      sender ! rate
    case IsConnected =>
      sender ! Connected
    case IncRate (inc_rate:Int) =>
      println(self + " won the game! Old rate - " + rate + ", new rate - " + (rate + inc_rate))
      rate = rate + inc_rate
    case DecRate (dec_rate:Int) =>
      println(self + " lose the game! Old rate - " + rate + ", new rate - " + (rate - dec_rate))
      rate = rate + dec_rate
  }
}

class PlayerGen(manager: ActorContext) extends Actor with ActorLogging{
  var i: Int = 0
  def receive = {

    case Run =>
      val rnd = scala.util.Random
      val rate = rnd.nextInt(5000)
      val player = manager.actorOf(Props(new Player(manager.self, rate)), "Player"+i)
      i += 1
      println("player "+ i +" with rate " + rate + " created")
      if (i==80) self ! PoisonPill
      self ! Sleep(100 milliseconds)

    case Sleep(duration) =>
      case object WakeUp
      context.system.scheduler.scheduleOnce(duration, self, WakeUp)
      context.become({
        case WakeUp => context.unbecome(); self ! Run
      }, discardOld = false);
  }
}

class Team(manager: ActorRef) extends Actor {

  var players: List[ActorRef] = List()
  var player_count = 0
  var full = false
  var in_game = false
  def receive = {
    case NewPlayer(player:ActorRef,rate:Int) =>
      players = players :+ player;
      player_count += 1;
      if(player_count>=5) {
        full=true;
        sender ! Ready
      }
    case InGame =>
      in_game = true
    case GameResult(win:Boolean) =>
      in_game = false
      player_count=0
      for (i <- 0 to players.size-1) {
        if(win)
          players.apply(i) ! IncRate(50)
        else
          players.apply(i) ! DecRate(50)
        players.apply(i) ! PoisonPill
      }
      players=players.drop(players.size)
      full = true

  }
}

class Game(manager: ActorContext) extends Actor {
  var min_rate = 0
  var player_count = 0
  var i = 0
  var max_rate = 0
  var InGame = false
  var teamCount = 2
  var full = false
  var number = 0
  var in_game = false
  var ready = 0
  implicit val timeout = Timeout(5 seconds)
  var teams: List[ActorRef] = List()
  var players: List[ActorRef] = List()

  for (i <- 1 to teamCount) {
    // создаем команды
    val team = context.actorOf(Props(new Team(self)))
    teams = teams :+ team
  }

  def receive = {
    case r: SetRateInterval =>
      min_rate = r.min_rate
      max_rate = r.max_rate

    case NewPlayer(player: ActorRef, rate: Int) =>
      if (!full) {
        players = players :+ player
        player_count += 1
        sender ! Accept
        if (player_count >= 10) {
          full = true
          CheckConnection()
          if (full)
            TeamFill()
        }
      }
      else sender ! Refuse(number)
    case SetNum (num: Int) =>
      number = num
    case Ready =>
      ready += 1;
      if (ready >= 2) {
        teams.head ! GameStart
        teams.tail.head ! GameStart
        in_game = true
        println("Game " +self+" has started!")
        self ! Sleep(15 seconds)
      }
    case GameFinished =>
      val rnd = scala.util.Random
      val result = rnd.nextInt(2)
      if (result==0) {
        teams.head ! GameResult(true)
        teams.tail.head ! GameResult(false)
      }
      else {
        teams.head ! GameResult(false)
        teams.tail.head ! GameResult(true)
      }
      full = false
      in_game = false
      player_count = 0
      ready = 0
      println(self+" has finished!");
    case Sleep(duration) =>
      case object WakeUp
      context.system.scheduler.scheduleOnce(duration, self, WakeUp)
      context.become({
        case WakeUp => context.unbecome(); self ! GameFinished
        case NewPlayer(player: ActorRef, rate:Int) =>
          sender ! Refuse(number)
      }, discardOld = false)

  }


  def CheckConnection(): Unit = {
    for(i <- 0 to 9) {
      //     players.apply(i) ! hello
      val future = players.apply(i) ? IsConnected
      val connected = Await.result(future, timeout.duration).asInstanceOf[Int]
      if (connected == 0) {
        full = false
        player_count -= 1
        println("Player left the game " + number + ". Trying to connect another player...")
        sender ! PlayerDisc (players.apply(i), self, min_rate, max_rate)
      }
    }

  }
  def TeamFill(): Unit = {
    for (i <- 1 to 5){
      teams.head ! NewPlayer(players.head,0)
      players = players.drop(1)
    }
    for (i <- 1 to 5) {
      teams.tail.head ! NewPlayer(players.head,0)
      players = players.drop(1)
    }
  }
}

class Scheduler() extends Actor with ActorLogging {
  var i: Int = 0
  implicit val timeout = Timeout(5 seconds)
  var game_rate: List[Array[Int]] = List(Array(0,0))
  var players: List[ActorRef] = List()
  var player_rate: List[Int] = List()
  var games: List[ActorRef] = List()

  val playerSpawner = context.actorOf(Props(new PlayerGen(context)), "PlayerGen")
  playerSpawner ! Run

  game_rate = game_rate.tail

  def receive = {
    case NewPlayer(player: ActorRef, rate: Int) =>
      players = players :+ player
      player_rate = player_rate :+ rate
      if (players.size > 0)
        self ! FindGame(0)

    case FindGame(idx) => // ищем игру с подходящим рейтингом (начиная с idx)
      if (players.size > 0) {
        val res_rate = player_rate.head
        var game_index = idx
        game_index = GetNextGame(idx, res_rate)
        if (game_index == -1)
          game_index = CreateGame(res_rate - 1000, res_rate + 1000)
        //  println(game_index + " " + idx + " " + res_rate)
        games.apply(game_index) ! NewPlayer(players.head, 0)
      }
    case Accept =>
      if (players.size > 0) {
        println("Player " + players.head + " found game " + sender + "!")
        players = players.tail
        player_rate = player_rate.tail
        self ! FindGame(0)
      }
    case Refuse (num: Int) =>
      println(players.head + " refused!")
      self ! FindGame(num+1)
    case PlayerDisc(player:ActorRef, game:ActorRef, min_rate: Int, max_rate: Int) =>  // игрок вышел, заменяем
      val player_idx = players.indexOf(player)
      if (player == players.head) {
        players = players.tail
        player_rate = player_rate.tail
      }
      else {
        players = players.take(player_idx) ++ players.drop(player_idx + 1)
        player_rate = player_rate.take(player_idx) ++ player_rate.drop(player_idx + 1)
      }
      sender ! NewPlayer (players.apply(FindPLayer(min_rate,max_rate)),0)

    case Sleep(duration) => // прост
      case object WakeUp
      context.system.scheduler.scheduleOnce(duration, self, WakeUp)
      context.become({
        case WakeUp => context.unbecome()
      }, discardOld = false)

  }
  def GetNextGame(index:Int, rate: Int) : Int = {
    var j:Int = 0
    if (index == game_rate.size)
      return -1
    for (j <- index to game_rate.size - 1) {
      if(game_rate.apply(j)(0) <= rate && game_rate.apply(j)(1) >= rate) {
        //   println("Game found! " + j + " " + rate)
        return j
      }

    }
    return -1
  }

  def FindPLayer(min_rate:Int, max_rate: Int) : Int = {
    var j:Int = 0
    for (j <- 0 to players.size - 1) {
      if(player_rate.apply(j) <= max_rate && player_rate.apply(j) >= min_rate)
        return j
    }
    return -1
  }
  def CreateGame(min_rate:Int, max_rate:Int): Int = {
    i = games.size
    val game = context.actorOf(Props(new Game(context)), "Game"+i)
    println("Game " + i + " created! Rates: " + min_rate + " - " + max_rate)
    game ! SetRateInterval (min_rate, max_rate)
    game ! SetNum (i)
    game_rate = game_rate :+ Array(min_rate,max_rate)
    games = games :+ game
    return i

  }
}


object CounterApp extends App {
  val system = ActorSystem("GameServer")
  val inbox = Inbox.create(system)
  val s = system.actorOf(Props(new Scheduler))
  s ! Run
}

