package com.capslock.redis.cache.record

import akka.actor.{Actor, ActorLogging, Stash}
import com.capslock.redis.command.response.RespCommand.{INTEGER_RESP_COMMAND, BULK_STRING_RESP_COMMAND}
import com.capslock.redis.command.response.{ERROR_RESP, NOT_NULL_BULK_STRING, NULL_BULK_STRING}
import com.capslock.redis.command.string.StringCommand._
import com.capslock.redis.command.{ERROR_RESP_COMMAND, OK_RESP_COMMAND}
import com.capslock.redis.utils.StringUtils

/**
  * Created by capsl on 2016/2/9.
  */
class StringRecord extends Actor with ActorLogging with Stash {
  var value = ""

  private def increaseNumberWithStep(step: Int): Either[String, Int] = {
    if (value.isEmpty) {
      value = "0"
    }
    StringUtils.safeStringToInt(value) match {
      case Some(number) => value = (number + step).toString; Right(number + step)
      case _ => Left("not a number")
    }
  }

  override def receive: Receive = {
    case SET(_, newValue) =>
      value = newValue
      sender() ! OK_RESP_COMMAND

    case SET_AND_SUSPEND(newValue) =>
      val oldValue = value
      value = newValue
      sender() ! OK_RESP_COMMAND
      context.become({
        case RESUME =>
          unstashAll()
          context.unbecome()
        case ROLL_BACK =>
          value = oldValue
          unstashAll()
          context.unbecome()
        case _ => println("stash"); stash()
      })

    case GET(_) =>
      sender() ! BULK_STRING_RESP_COMMAND(value)

    case QUERY(_) =>
      println("QUERY")
      sender() ! NOT_NULL_BULK_STRING(value)

    case STRLEN(_) =>
      sender() ! INTEGER_RESP_COMMAND(value.length)

    case INCR(_) =>
      increaseNumberWithStep(1) match {
        case Left(errorMsg) => sender() ! ERROR_RESP_COMMAND(ERROR_RESP(errorMsg))
        case Right(newValue) => sender() ! INTEGER_RESP_COMMAND(newValue)
      }

    case INCRBY(_, step) =>
      increaseNumberWithStep(step.toInt) match {
        case Left(errorMsg) => sender() ! ERROR_RESP_COMMAND(ERROR_RESP(errorMsg))
        case Right(newValue) => sender() ! INTEGER_RESP_COMMAND(newValue)
      }

    case DECR(_) =>
      increaseNumberWithStep(-1) match {
        case Left(errorMsg) => sender() ! ERROR_RESP_COMMAND(ERROR_RESP(errorMsg))
        case Right(newValue) => sender() ! INTEGER_RESP_COMMAND(newValue)
      }

    case DECRBY(_, step) =>
      increaseNumberWithStep(-step.toInt) match {
        case Left(errorMsg) => sender() ! ERROR_RESP_COMMAND(ERROR_RESP(errorMsg))
        case Right(newValue) => sender() ! INTEGER_RESP_COMMAND(newValue)
      }

    case GETRANGE(key, start, end) =>
      val startIndex = StringUtils.safeStringToInt(start)
      val endIndex = StringUtils.safeStringToInt(end)
      if (startIndex.isDefined && endIndex.isDefined) {
        val subString = StringUtils.subString(value, start.toInt, end.toInt)
        sender() ! BULK_STRING_RESP_COMMAND(subString)
      } else {
        sender() ! NULL_BULK_STRING
      }

  }
}
