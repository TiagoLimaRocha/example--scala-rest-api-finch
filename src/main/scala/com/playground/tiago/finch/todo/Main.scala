package com.playground.tiago.finch.todo

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

import com.playground.tiago.finch.todo.models.Todo

object Main extends IOApp {
  implicit val cs = IO.contextShift(ExecutionContexts.synchronous)

  val xa = Transactor.fromDriverManager[IO](
    "org.sqlite.JDBC",
    "jdbc:sqlite:data.db",
    "",
    "",
    Blocker.liftExecutionContext(ExecutionContexts.synchronous)
  )

  val createDb = sql"""
    CREATE TABLE IF NOT EXISTS todo (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      description TEXT,
      done NUMERIC
    )
    """.update.run

  val root: String = "todos"

  val create: Endpoint[IO, Todo] = post(root :: jsonBody[Todo]) { todo: Todo =>
    for {
      id <-
        sql"INSERT INTO todo (name, description, done) VALUES (${todo.name}, ${todo.description}, ${todo.done})".update
          .withUniqueGeneratedKeys[Int]("id")
          .transact(xa)

      created <- sql"SELECT * FROM todo WHERE id = $id"
        .query[Todo]
        .unique
        .transact(xa)
    } yield Created(created)
  }

  val findOne: Endpoint[IO, Todo] = get(root :: path[Int]) { id: Int =>
    for {
      todos <- sql"SELECT * FROM todo WHERE id = $id"
        .query[Todo]
        .to[Set]
        .transact(xa)
    } yield todos.headOption match {
      case None       => NotFound(new Exception("Record not found"))
      case Some(todo) => Ok(todo)
    }
  }

  val update: Endpoint[IO, Todo] =
    put(root :: path[Int] :: jsonBody[Todo]) { (id: Int, todo: Todo) =>
      for {
        _ <-
          sql"UPDATE todo SET name = ${todo.name}, description = ${todo.description}, done = ${todo.done} WHERE id = $id".update.run
            .transact(xa)

        todo <- sql"SELECT * FROM todo WHERE id = $id"
          .query[Todo]
          .unique
          .transact(xa)
      } yield Ok(todo)
    }

  val findMany: Endpoint[IO, Seq[Todo]] = get(root) {
    for {
      todos <- sql"SELECT * FROM todo"
        .query[Todo]
        .to[Seq]
        .transact(xa)
    } yield Ok(todos)
  }

  val deleteOne: Endpoint[IO, Unit] = delete(root :: path[Int]) { id: Int =>
    for {
      _ <- sql"DELETE FROM todo WHERE id = $id".update.run
        .transact(xa)
    } yield NoContent
  }

  def startServer: IO[ListeningServer] =
    createDb.transact(xa).flatMap { _ =>
      IO(
        Http.server.serve(
          ":8081",
          (create :+: update :+: deleteOne :+: findOne :+: findMany)
            .toServiceAs[Application.Json]
        )
      )
    }

  def run(args: List[String]): IO[ExitCode] = {
    val server = Resource.make(startServer)(s =>
      IO.suspend(implicitly[ToAsync[Future, IO]].apply(s.close()))
    )

    server.use(_ => IO.never).as(ExitCode.Success)
  }
}
