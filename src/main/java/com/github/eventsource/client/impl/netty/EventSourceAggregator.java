package com.github.eventsource.client.impl.netty;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.frame.TooLongFrameException;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpMessage;

/**
 * @author Vassili Jakovlev
 */
public class EventSourceAggregator extends SimpleChannelUpstreamHandler {

  private final int maxContentLength;
  private HttpMessage currentMessage;

  private ChannelBuffer chunkBuffer;

  /**
   * Creates a new instance.
   * 
   * @param maxContentLength the maximum length of the aggregated content. If the length of the aggregated content
   *          exceeds this value, a {@link TooLongFrameException} will be raised.
   */
  public EventSourceAggregator(int maxContentLength) {
    if (maxContentLength <= 0) {
      throw new IllegalArgumentException("maxContentLength must be a positive integer: " + maxContentLength);
    }
    this.maxContentLength = maxContentLength;
  }

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {

    Object msg = e.getMessage();

    if (msg instanceof HttpMessage) {
      currentMessage = (HttpMessage) msg;
      ctx.sendUpstream(e);
    } else if (msg instanceof HttpChunk) {
      // Sanity check
      if (currentMessage == null) {
        throw new IllegalStateException("received " + HttpChunk.class.getSimpleName() + " without "
            + HttpMessage.class.getSimpleName());
      }

      if (chunkBuffer == null) {
        chunkBuffer = ChannelBuffers.dynamicBuffer(e.getChannel().getConfig().getBufferFactory());
      }

      // Merge the received chunk into the content of the current message.
      HttpChunk chunk = (HttpChunk) msg;
      if (chunkBuffer.readableBytes() > maxContentLength - chunk.getContent().readableBytes()) {
        // TODO: Respond with 413 Request Entity Too Large and discard the traffic or close the connection.
        // No need to notify the upstream handlers - just log. If decoding a response, just throw an exception.
        throw new TooLongFrameException("HTTP content length exceeded " + maxContentLength + " bytes.");
      }

      chunkBuffer.writeBytes(chunk.getContent());
      byte[] bytes = chunk.getContent().array();
      boolean endWithNewLines = bytes.length >= 2 && bytes[bytes.length - 2] == 10 && bytes[bytes.length - 1] == 10;
      if (chunk.isLast() || endWithNewLines) {
        chunk.setContent(chunkBuffer);
        ctx.sendUpstream(e);
        chunkBuffer = null;
      }
    } else {
      // Neither HttpMessage or HttpChunk
      ctx.sendUpstream(e);
    }
  }
}
