/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.livy.server

import javax.servlet.http.HttpServletRequest

import org.scalatra._
import scala.concurrent._
import scala.concurrent.duration._

import org.apache.livy.{LivyConf, Logging}
import org.apache.livy.rsc.RSCClientFactory
import org.apache.livy.server.batch.BatchSession
import org.apache.livy.sessions.{Session, SessionManager}
import org.apache.livy.sessions.Session.RecoveryMetadata

object SessionServlet extends Logging

/**
 * Base servlet for session management. All helper methods in this class assume that the session
 * id parameter in the handler's URI is "id".
 *
 * Type parameters:
 *  S: the session type
 */
abstract class SessionServlet[S <: Session, R <: RecoveryMetadata](
    private[livy] val sessionManager: SessionManager[S, R],
    val livyConf: LivyConf,
    accessManager: AccessManager)
  extends JsonServlet
  with ApiVersioningSupport
  with MethodOverride
  with UrlGeneratorSupport
  with GZipSupport
{
  /**
   * Creates a new session based on the current request. The implementation is responsible for
   * parsing the body of the request.
   */
  protected def createSession(req: HttpServletRequest): S

  /**
   * Returns a object representing the session data to be sent back to the client.
   */
  protected def clientSessionView(session: S, req: HttpServletRequest): Any = session

  override def shutdown(): Unit = {
    sessionManager.shutdown()
  }

  before() {
    contentType = "application/json"
  }

  get("/") {
    val from = params.get("from").map(_.toInt).getOrElse(0)
    val size = params.get("size").map(_.toInt).getOrElse(100)

    val sessions = sessionManager.all()

    Map(
      "from" -> from,
      "total" -> sessionManager.size(),
      "sessions" -> sessions.view(from, from + size).map(clientSessionView(_, request))
    )
  }

  val getSession = get("/:id") {
    withUnprotectedSession { session =>
      clientSessionView(session, request)
    }
  }

  get("/:id/state") {
    withUnprotectedSession { session =>
      Map("id" -> session.id, "state" -> session.state.toString)
    }
  }

  get("/:id/log") {
    withViewAccessSession { session =>
      val from = params.get("from").map(_.toInt)
      val size = params.get("size").map(_.toInt)
      val (from_, total, logLines) = serializeLogs(session, from, size)

      Map(
        "id" -> session.id,
        "from" -> from_,
        "total" -> total,
        "log" -> logLines)
    }
  }

  delete("/:id") {
    withModifyAccessSession { session =>
      sessionManager.delete(session.id) match {
        case Some(future) =>
          Await.ready(future, Duration.Inf)
          Ok(Map("msg" -> "deleted"))

        case None =>
          NotFound(s"Session ${session.id} already stopped.")
      }
    }
  }

  def tooManySessions(): Boolean = {
    val totalChildProceses = RSCClientFactory.childProcesses().get() +
      BatchSession.childProcesses.get()
    totalChildProceses >= livyConf.getInt(LivyConf.SESSION_MAX_CREATION)
  }

  post("/") {
    if (tooManySessions) {
      BadRequest("Rejected, too many sessions are being created!")
    } else {
      /////////////////////////////////////////////////////////////
      println("post---->creat session")
      ////////////////////////////////////////////////////////////
      val session = sessionManager.register(createSession(request))
      // Because it may take some time to establish the session, update the last activity
      // time before returning the session info to the client.
      session.recordActivity()
      Created(clientSessionView(session, request),
        headers = Map("Location" ->
          (getRequestPathInfo(request) + url(getSession, "id" -> session.id.toString))))
    }
  }

  private def getRequestPathInfo(request: HttpServletRequest): String = {
    if (request.getPathInfo != null && request.getPathInfo != "/") {
      request.getPathInfo
    } else {
      ""
    }
  }

  error {
    case e: IllegalArgumentException => BadRequest(e.getMessage)
  }

  /**
   * Returns the remote user for the given request. Separate method so that tests can override it.
   */
  protected def remoteUser(req: HttpServletRequest): String = req.getRemoteUser()

  /**
   * Checks that the request's user can impersonate the target user.
   *
   * If the user does not have permission to impersonate, then halt execution.
   *
   * @return The user that should be impersonated. That can be the target user if defined, the
   *         request's user - which may not be defined - otherwise, or `None` if impersonation is
   *         disabled.
   */
  protected def checkImpersonation(
      target: Option[String],
      req: HttpServletRequest): Option[String] = {
    if (livyConf.getBoolean(LivyConf.IMPERSONATION_ENABLED)) {
      if (!target.map(hasSuperAccess(_, req)).getOrElse(true)) {
        halt(Forbidden(s"User '${remoteUser(req)}' not allowed to impersonate '$target'."))
      }
      target.orElse(Option(remoteUser(req)))
    } else {
      None
    }
  }

  /**
   * Check that the request's user has view access to resources owned by the given target user.
   */
  protected def hasViewAccess(target: String, req: HttpServletRequest): Boolean = {
    val user = remoteUser(req)
    user == target || accessManager.checkViewPermissions(user)
  }

  /**
   * Check that the request's user has modify access to resources owned by the given target user.
   */
  protected def hasModifyAccess(target: String, req: HttpServletRequest): Boolean = {
    val user = remoteUser(req)
    user == target || accessManager.checkModifyPermissions(user)
  }

  /**
   * Check that the request's user has admin access to resources owned by the given target user.
   */
  protected def hasSuperAccess(target: String, req: HttpServletRequest): Boolean = {
    val user = remoteUser(req)
    user == target || accessManager.checkSuperUser(user)
  }

  /**
   * Performs an operation on the session, without checking for ownership. Operations executed
   * via this method must not modify the session in any way, or return potentially sensitive
   * information.
   */
  protected def withUnprotectedSession(fn: (S => Any)): Any = doWithSession(fn, true, None)

  /**
   * Performs an operation on the session, verifying whether the caller has view access of the
   * session.
   */
  protected def withViewAccessSession(fn: (S => Any)): Any =
    doWithSession(fn, false, Some(hasViewAccess))

  /**
   * Performs an operation on the session, verifying whether the caller has view access of the
   * session.
   */
  protected def withModifyAccessSession(fn: (S => Any)): Any =
    doWithSession(fn, false, Some(hasModifyAccess))

  private def doWithSession(fn: (S => Any),
      allowAll: Boolean,
      checkFn: Option[(String, HttpServletRequest) => Boolean]): Any = {
    val sessionId = params("id").toInt
    sessionManager.get(sessionId) match {
      case Some(session) =>
        if (allowAll || checkFn.map(_(session.owner, request)).getOrElse(false)) {
          fn(session)
        } else {
          Forbidden()
        }
      case None =>
        NotFound(s"Session '$sessionId' not found.")
    }
  }

  private def serializeLogs(session: S, fromOpt: Option[Int], sizeOpt: Option[Int]) = {
    val lines = session.logLines()

    var size = sizeOpt.getOrElse(100)
    var from = fromOpt.getOrElse(-1)
    if (size < 0) {
      size = lines.length
    }
    if (from < 0) {
      from = math.max(0, lines.length - size)
    }
    val until = from + size

    (from, lines.length, lines.view(from, until))
  }
}
