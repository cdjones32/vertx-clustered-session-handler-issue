/**
 * Created by chrisjones on 16/09/15.
 */

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.sstore.ClusteredSessionStore;
import io.vertx.ext.web.sstore.LocalSessionStore;

/**
 *
 * @author <a href="https://github.com/InfoSec812">Deven Phillips</a>
 */
public class DemonstrateIssue extends AbstractVerticle {

  private static final Logger LOG = LoggerFactory.getLogger(DemonstrateIssue.class);

  public static void main(String[] args) {
    LOG.debug("Deploying Main verticle.");
    Vertx.clusteredVertx(new VertxOptions().setClustered(true), handler -> {
      handler.result().deployVerticle(new DemonstrateIssue());
    });
  }

  @Override
  public void start() throws Exception {
    Router router = Router.router(vertx);
    HttpClient client = vertx.createHttpClient();

    // Standard SessionHandler and clustered SessionStore (exhibits race condition)
    SessionHandler sessionHandlerWithClusteredSession = SessionHandler.create(ClusteredSessionStore.create(vertx)).setNagHttps(false);

    // Standard SessionHandler and LocalSessionStore (doesn't exhibit race condition)
    SessionHandler sessionHandlerWithLocalSession = SessionHandler.create(LocalSessionStore.create(vertx)).setNagHttps(false);

    // Custom SessionHandler using the Clustered session store, but doesn't implement an asychronous context.addHeadersEndHandler (doesn't exhibit race condition)
    SessionHandler sessionHandlerWithCustomAddStoreSessionHandler = new CustomSessionHandlerImpl(ClusteredSessionStore.create(vertx));

    router.route().handler( ctx -> {
      // Pause the request, otherwise the request will be marked as ended after the session handler defers the processing
      // to get the Cluster map/session value.
      ctx.request().pause();
      ctx.next();
    });



    // Initialise the standard Clustered session handler. The corruption issue is caused by the asynchronous
    // calls inside the SessionHandlerImpl.addStoreSessionHandler method.
    // I *think* the corruption occurs because the write to the response (in this case by a Pump, but it also seems to
    // occur if you perform multiple writes directly to the response (e.g. chunked response).
    router.route().handler( ctx -> {
      String sessionHandler = ctx.request().params().get("sessionHandler");

      if ("clustered".equals(sessionHandler)) {
        sessionHandlerWithClusteredSession.handle(ctx);
      } else if ("custom".equals(sessionHandler)) {
        sessionHandlerWithCustomAddStoreSessionHandler.handle(ctx);
      } else {
        sessionHandlerWithLocalSession.handle(ctx);
      }
    });

    // Set up a simple proxy that just retrieves the Google logo. I just do it this way so that the corruption is visibly
    // easy to see. The corruption occurs in any type of proxied request.
    // This code is probably more complex than it needs to be, but it was taken from a working example of a Proxy: https://github.com/InfoSec812/simple-vertx-proxy-example
    router.route("/get_image.png").handler(ctx -> {
      LOG.info("Sending proxied request.");

      HttpClientRequest clientReq = client.request(HttpMethod.GET, 80, "www.google.com", "/images/branding/googlelogo/2x/googlelogo_color_272x92dp.png");

      // Because we are just proxying, copy all request headers to the proxied request. Omit Host because we need to set that to the proxy target.
      clientReq.headers().addAll(ctx.request().headers().remove("Host"));


      clientReq.putHeader("Host", "www.google.com");

      // Not really used in this instance, but kept in from the example
      if (ctx.request().method().equals(HttpMethod.POST) || ctx.request().method().equals(HttpMethod.PUT)) {
        if (ctx.request().headers().get("Content-Length")==null) {
          clientReq.setChunked(true);
        }
      }

      // Set up a handler for the proxied image request
      clientReq.handler(pResponse -> {
        LOG.info("Getting response from target");

        ctx.response().headers().addAll(pResponse.headers());
        if (pResponse.headers().get("Content-Length") == null) {
          ctx.response().setChunked(true);
        }

        ctx.response().setStatusCode(pResponse.statusCode());
        ctx.response().setStatusMessage(pResponse.statusMessage());

        // Set up a Pump to pump the proxy response back to the original client response
        Pump targetToProxy = Pump.pump(pResponse, ctx.response());
        targetToProxy.start();
        pResponse.endHandler(v -> ctx.response().end());
      });

      // Pump the original request through to the Proxy target. Doesn't do much for a GET, but will work for other types.
      Pump proxyToTarget = Pump.pump(ctx.request(), clientReq);
      proxyToTarget.start();
      ctx.request().endHandler(v -> clientReq.end());

      // Resume the original request processing because had to pause it before the ClusteredSession handler was called.
      ctx.request().resume();
    });

    // This is just a demo route that makes multiple requests to the image proxy above so the corruption is easy to see.
    setupIndexPageRoute(router);

    vertx.createHttpServer().requestHandler(router::accept).listen(8080);
  }


  /**
   * Sets up a simple handler to display the image x number of times without caching. Allows the corruption to be seen easily.
   *
   * @param router
   */
  private void setupIndexPageRoute(Router router) {

    router.route().handler(ctx -> {
      String sessionHandler = ctx.request().params().get("sessionHandler");

      StringBuilder sb = new StringBuilder();

      sb.append("<html><body>\n");

      sb.append("<div><a href='/?sessionHandler=local'>Local (no corruption - no async context.addHeadersEndHandler)</a><div>\n");
      sb.append("<div><a href='/?sessionHandler=custom'>Custom (no corruption - no async context.addHeadersEndHandler)</a><div>\n");
      sb.append("<div><a href='/?sessionHandler=clustered'>Clustered (exhibits corruption - async context.addHeadersEndHandler)</a><strong>You may need to stop the page load after you try this one to try the others again</strong><div>\n");

      if (sessionHandler != null) {
        for (int i = 0; i < 10; i++) {
          sb.append("  <img src='/get_image.png?x=" + System.nanoTime() + "&sessionHandler=" + sessionHandler + "'/>\n");
        }
      }

      sb.append("</body></html>");

      ctx.request().resume();
      ctx.response().end( sb.toString() );
    });
  }
}