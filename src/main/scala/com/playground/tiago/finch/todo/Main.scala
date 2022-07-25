package  com.playground.tiago.finch.todo

import com.twitter.finagle.{Http, ListeningServer}
import com.twitter.util.Future

import cats.effect.{IO, IOApp, Blocker, ExitCode, Resource}
import cats.effect.syntax._

import io.finch._
import io.finch.catsEffect._
import io.finch.circe._

import io.circe.generic.auto._

import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import doobie._
import doobie.implicits._

import com.playground.tiago.todo.models.Todo

object Main extends IOApp {
  implicit val cs = IO.contextShift(ExecutionContexts.syncronous)

  val xa = Transactor.fromDriverManager[IO](
    "org.sqlite.JDBC", "jdbc:sqlite:data.db", "", "",
    Blocker.liftExecutionContext(ExecutionContexts.syncronous)
  )
  
  val root: String = "todos"

  val createDb = sql"""
      CREATE TABLE IF NOT EXISTS todo (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        description TEXT,
        done NUMERIC
      )
    """
    .update
    .run

  val create: Endpoint[IO, Todo] = post(root :: jsonBody[Todo]) {
    todo: Todo => for {
      id <- sql"INSERT INTO todo (name, description, done) 
        VALUES (${todo.name}, ${todo.description}, ${todo.done})"
        .update
        .withUniqueGeneralKeys[Int]("id")
        .transact(xa)

      created <- sql"SQL * FROM todo WHERE id = $id"
        .query[Todo]
        .unique
        .transact(xa)
    } yield Created(created)
  }
}