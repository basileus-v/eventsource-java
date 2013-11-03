package com.github.eventsource.client.impl.netty;

import com.github.eventsource.client.EventSourceClient;
import com.github.eventsource.client.EventSourceException;
import com.github.eventsource.client.EventSourceHandler;
import com.github.eventsource.client.impl.ConnectionHandler;
import com.github.eventsource.client.impl.EventStreamParser;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpHeaders.Names;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;

public class EventSourceChannelHandler extends SimpleChannelUpstreamHandler implements ConnectionHandler {

  private final EventSourceHandler eventSourceHandler;
  private final EventSourceClient client;
  private final URI uri;
  private final EventStreamParser messageDispatcher;

  private static final Timer TIMER = new HashedWheelTimer();
  private Channel channel;
  private boolean reconnectOnClose = true;
  private long reconnectionTimeMillis;
  private String lastEventId;
  private final AtomicBoolean reconnecting = new AtomicBoolean(false);

  public EventSourceChannelHandler(EventSourceHandler eventSourceHandler,
                                   long reconnectionTimeMillis,
                                   EventSourceClient client,
                                   URI uri) {
    this.eventSourceHandler = eventSourceHandler;
    this.reconnectionTimeMillis = reconnectionTimeMillis;
    this.client = client;
    this.uri = uri;
    this.messageDispatcher = new EventStreamParser(uri.toString(), eventSourceHandler, this);
  }

  @Override
  public void handleUpstream(ChannelHandlerContext ctx, ChannelEvent e) throws Exception {
    super.handleUpstream(ctx, e);
  }

  @Override
  public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
    HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri.toString());
    request.addHeader(Names.ACCEPT, "text/event-stream");
    request.addHeader(Names.HOST, uri.getHost());
    request.addHeader(Names.ORIGIN, "http://" + uri.getHost());
    request.addHeader(Names.CACHE_CONTROL, "no-cache");
    // application/x-www-form-urlencoded; charset=UTF-8
    if (lastEventId != null) {
      request.addHeader("Last-Event-ID", lastEventId);
    }
    e.getChannel().write(request);
    channel = e.getChannel();
  }

  @Override
  public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
    channel = null;
  }

  @Override
  public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
    if (reconnectOnClose) {
      reconnect();
    }
  }

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    String line = null;
    if (e.getMessage() instanceof DefaultHttpResponse) {
      DefaultHttpResponse httpMessage = (DefaultHttpResponse) e.getMessage();
      HttpVersion protocolVersion = httpMessage.getProtocolVersion();
      if (!HttpResponseStatus.OK.equals(httpMessage.getStatus())) {
        eventSourceHandler.onError(new EventSourceException("Bad status from " + uri + ": " + httpMessage.getStatus()));
        reconnect();
        return;
      }
      if (!HttpVersion.HTTP_1_1.equals(protocolVersion)) {
        eventSourceHandler.onError(new EventSourceException("Not HTTP? " + uri + ": " + line));
        reconnect();
        return;
      }
      if (!"text/event-stream".equals(httpMessage.getHeader(HttpHeaders.Names.CONTENT_TYPE))) {
        eventSourceHandler.onError(new EventSourceException("Not event stream: " + uri
            + " (expected Content-Type: text/event-stream"));
        reconnect();
        return;
      }
      eventSourceHandler.onConnect();
      line = httpMessage.getContent().toString(Charset.defaultCharset());
    } else if (e.getMessage() instanceof HttpChunk) {
      line = ((HttpChunk) e.getMessage()).getContent().toString(Charset.defaultCharset());
    } else {
      line = (String) e.getMessage();
    }
    // split event by line separators in event parser instead of channel pipeline
    messageDispatcher.lines(line);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
    Throwable error = e.getCause();
    if (error instanceof ConnectException) {
      error = new EventSourceException("Failed to connect to " + uri, error);
    }
    eventSourceHandler.onError(error);
    ctx.getChannel().close();
  }

  @Override
  public void setReconnectionTimeMillis(long reconnectionTimeMillis) {
    this.reconnectionTimeMillis = reconnectionTimeMillis;
  }

  @Override
  public void setLastEventId(String lastEventId) {
    this.lastEventId = lastEventId;
  }

  public EventSourceChannelHandler close() {
    reconnectOnClose = false;
    if (channel != null) {
      channel.close();
    }
    return this;
  }

  public ChannelFuture connect() {
    return client.connect(getConnectAddress(), this);
  }

  public EventSourceChannelHandler join() throws InterruptedException {
    if (channel != null) {
      channel.getCloseFuture().await();
    }
    return this;
  }

  private void reconnect() {
    if (reconnectionTimeMillis >= 0) {
      if (!reconnecting.get()) {
        reconnecting.set(true);
        TIMER.newTimeout(new TimerTask() {
          @Override
          public void run(Timeout timeout) throws Exception {
            reconnecting.set(false);
            connect().await();
          }
        }, reconnectionTimeMillis, TimeUnit.MILLISECONDS);
      }
    }
  }

  public InetSocketAddress getConnectAddress() {
    return new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort());
  }
}