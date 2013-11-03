package com.github.eventsource.client;

import java.net.URI;
import java.util.concurrent.CountDownLatch;

public class DebugClient {
  public static void main(String[] args) throws InterruptedException {
    String uri =
        "http://dev.push.mtaxi.ee/signalr/hubs/signalr/connect?transport=serverSentEvents&connectionToken="
            + "%2FBmrJWPx4oLBhK%2B5VfO41MqBB0ZXeqKI6gAhfkJFDAe24w0Fie5Z8Ek0o9Uh7Fkxy7MQW8gqOH5i7E3nj6E9zwTROnIDqBAfyqkfATSE3jdpcGagIkbfU6YGNDAbqVc2"
            + "&connectionData=%5B%7B%22name%22%3A%22ActiveInstantRequestsListHub%22%7D%5D&customGroupToken=driver:4:23:1";
    EventSource es = new EventSource(URI.create(uri), new EventSourceHandler() {
      @Override
      public void onConnect() {
        System.out.println("CONNECTED");
      }

      @Override
      public void onMessage(String event, MessageEvent message) {
        System.out.println("event = " + event + ", message = " + message);
      }

      @Override
      public void onError(Throwable t) {
        System.err.println("ERROR");
        t.printStackTrace();
      }
    });

    es.connect();
    new CountDownLatch(1).await();
  }
}
