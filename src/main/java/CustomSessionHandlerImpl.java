/**
 * Complete clone of io.vertx.ext.web.handler.impl.SessionHandlerImpl with the addStoreSessionHandler method modified
 * so that is is not asynchronous. This prevents the race condition between the context.addHeadersEndHandler call
 * and the response writes.
 *
 * The changes will be marked with **** for easy identification.
 */

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.sstore.SessionStore;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
public class CustomSessionHandlerImpl implements SessionHandler {

  private static final Logger log = LoggerFactory.getLogger(CustomSessionHandlerImpl.class);

  private final SessionStore sessionStore;
  private String sessionCookieName;
  private long sessionTimeout;
  private boolean nagHttps;
  private boolean sessionCookieSecure;
  private boolean sessionCookieHttpOnly;

  public CustomSessionHandlerImpl(SessionStore sessionStore) {
    this("__sessionCookieName", 100000, false, false, false, sessionStore);
  }

  public CustomSessionHandlerImpl(String sessionCookieName, long sessionTimeout, boolean nagHttps, boolean sessionCookieSecure, boolean sessionCookieHttpOnly, SessionStore sessionStore) {
    this.sessionCookieName = sessionCookieName;
    this.sessionTimeout = sessionTimeout;
    this.nagHttps = nagHttps;
    this.sessionStore = sessionStore;
    this.sessionCookieSecure = sessionCookieSecure;
    this.sessionCookieHttpOnly = sessionCookieHttpOnly;
  }

  @Override
  public SessionHandler setSessionTimeout(long timeout) {
    this.sessionTimeout = timeout;
    return this;
  }

  @Override
  public SessionHandler setNagHttps(boolean nag) {
    this.nagHttps = nag;
    return this;
  }

  @Override
  public SessionHandler setCookieSecureFlag(boolean secure) {
    this.sessionCookieSecure = secure;
    return this;
  }

  @Override
  public SessionHandler setCookieHttpOnlyFlag(boolean httpOnly) {
    this.sessionCookieHttpOnly = httpOnly;
    return this;
  }

  @Override
  public SessionHandler setSessionCookieName(String sessionCookieName) {
    this.sessionCookieName = sessionCookieName;
    return this;
  }

  @Override
  public void handle(RoutingContext context) {
    context.response().ended();

    if (nagHttps) {
      String uri = context.request().absoluteURI();
      if (!uri.startsWith("https:")) {
        log.warn("Using session cookies without https could make you susceptible to session hijacking: " + uri);
      }
    }

    // Look for existing session cookie
    Cookie cookie = context.getCookie(sessionCookieName);
    if (cookie != null) {
      // Look up session
      String sessionID = cookie.getValue();
      sessionStore.get(sessionID, res -> {
        if (res.succeeded()) {
          Session session = res.result();
          if (session != null) {
            context.setSession(session);
            session.setAccessed();
            addStoreSessionHandler(context);
          } else {
            // Cannot find session - either it timed out, or was explicitly destroyed at the server side on a
            // previous request.
            // Either way, we create a new one.
            createNewSession(context);
          }
        } else {
          context.fail(res.cause());
        }
        context.next();
      });
    } else {
      createNewSession(context);
      context.next();
    }
  }

  private void addStoreSessionHandler(RoutingContext context) {
    context.addHeadersEndHandler(fut -> {
      Session session = context.session();
      if (!session.isDestroyed()) {
        // Store the session
        session.setAccessed();
        sessionStore.put(session, res -> {
          if (res.succeeded()) {
            // **** Don't do this here, as this is one of the async paths that cause the race condition
            // **** fut.complete();
          } else {
            // Failed to store session
            // **** The below line is invalid now in this case
            // **** context.fail(res.cause());
          }
        });
      } else {
        sessionStore.delete(session.id(), res -> {
          if (res.succeeded()) {
            // **** Don't do this here, as this is one of the async paths that cause the race condition
            // **** fut.complete();
          } else {
            // Failed to store session
            // **** The below line is invalid now in this case
            // **** context.fail(res.cause());
          }
        });
      }
      // **** Resolve the future here as this will resolve immediately and not allow the response stream to reach the race
      // **** condition. Again... not saying this is the fix, just demonstrating where the Race condition exists and its impacts.
      fut.complete();
    });
  }

  private void createNewSession(RoutingContext context) {
    Session session = sessionStore.createSession(sessionTimeout);
    context.setSession(session);
    Cookie cookie = Cookie.cookie(sessionCookieName, session.id());
    cookie.setPath("/");
    cookie.setSecure(sessionCookieSecure);
    cookie.setHttpOnly(sessionCookieHttpOnly);
    // Don't set max age - it's a session cookie
    context.addCookie(cookie);
    addStoreSessionHandler(context);
  }
}
