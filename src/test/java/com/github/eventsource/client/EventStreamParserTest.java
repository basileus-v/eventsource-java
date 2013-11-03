package com.github.eventsource.client;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.github.eventsource.client.impl.ConnectionHandler;
import com.github.eventsource.client.impl.EventStreamParser;
import com.github.eventsource.client.stubs.StubHandler;
import org.junit.Before;
import org.junit.Test;

public class EventStreamParserTest {
    private static final String ORIGIN = "http://host.com:99/foo";
    public EventSourceHandler eh;
    public ConnectionHandler ch;
    public EventStreamParser esp;

    @Before
    public void setup() {
        eh = mock(EventSourceHandler.class);
        ch = mock(ConnectionHandler.class);
        esp = new EventStreamParser(ORIGIN, eh, ch);
    }

    @Test
    public void dispatchesSingleLineMessage() throws Exception {
        esp.line("data: hello");
        esp.line("");

        verify(eh).onMessage(eq("message"), eq(new MessageEvent("hello", null, ORIGIN)));
    }

    @Test
    public void dispatchLongSingleLineMessage() throws Exception {
      String data = "{\"C\":\"d-A1F1A1E0-Uk,0|Xg,4|Xh,3|Xi,0\",\"M\":[{\"H\":\"ActiveInstantRequestsListHub\",\"M\":\"requestchanged\",\"A\":[{\"BookingTransactionId\":893,\"Driver\":null,\"CompanyId\":4,\"Cancellation\":null,\"Status\":7,\"Type\":1,\"AddressAvailability\":1,\"Availability\":1,\"ClientId\":0,\"ClientInfo\":{\"Name\":\"Eduard\",\"PhoneNumber\":\"123\"},\"Itinerary\":{\"Distance\":4.26,\"Price\":10.0,\"Currency\":{\"Id\":1,\"Code\":\"EUR\",\"IsoCode\":\"EUR\",\"Symbol\":\"â‚¬\",\"Description\":\"Euro\"},\"StartAddress\":{\"AddressLine1\":\"Soo 3, 10414 Tallinn, Estonia\",\"GeoLocation\":{\"Latitude\":59.4451098,\"Longitude\":24.741345700000011},\"Porch\":null},\"StopPoints\":[{\"StopOrder\":0,\"StopAddress\":{\"AddressLine1\":\"Helme 3, 10614 Tallinn, Estonia\",\"GeoLocation\":{\"Latitude\":59.43724069999999,\"Longitude\":24.702487499999961},\"Porch\":null}}]},\"SmsDeliveryStatus\":null,\"PickupTime\":\"2013-10-30T22:19:20.1318822\",\"DriverEstimatedArrivalTime\":null,\"Comment\":null,\"IsInZoneRequest\":false,\"IsActive\":true,\"DriverZoneAttachments\":[]}]},{\"H\":\"ActiveInstantRequestsListHub\",\"M\":\"requestchanged\",\"A\":[{\"BookingTransactionId\":893,\"Driver\":null,\"CompanyId\":4,\"Cancellation\":null,\"Status\":7,\"Type\":1,\"AddressAvailability\":1,\"Availability\":1,\"ClientId\":0,\"ClientInfo\":{\"Name\":\"Eduard\",\"PhoneNumber\":\"123\"},\"Itinerary\":{\"Distance\":4.26,\"Price\":10.0,\"Currency\":{\"Id\":1,\"Code\":\"EUR\",\"IsoCode\":\"EUR\",\"Symbol\":\"â‚¬\",\"Description\":\"Euro\"},\"StartAddress\":{\"AddressLine1\":\"Soo 3, 10414 Tallinn, Estonia\",\"GeoLocation\":{\"Latitude\":59.4451098,\"Longitude\":24.741345700000011},\"Porch\":null},\"StopPoints\":[{\"StopOrder\":0,\"StopAddress\":{\"AddressLine1\":\"Helme 3, 10614 Tallinn, Estonia\",\"GeoLocation\":{\"Latitude\":59.43724069999999,\"Longitude\":24.702487499999961},\"Porch\":null}}]},\"SmsDeliveryStatus\":null,\"PickupTime\":\"2013-10-30T22:19:20.1318822\",\"DriverEstimatedArrivalTime\":null,\"Comment\":null,\"IsInZoneRequest\":false,\"IsActive\":true,\"DriverZoneAttachments\":[]}]}]}";
      esp.line("data: " + data);
      esp.line("");
      verify(eh).onMessage(eq("message"), eq(new MessageEvent(data, null, ORIGIN)));
    }
    
    @Test
    public void doesntFireMultipleTimesIfSeveralEmptyLines() throws Exception {
        esp.line("data: hello");
        esp.line("");
        esp.line("");

        verify(eh).onMessage(eq("message"), eq(new MessageEvent("hello", null, ORIGIN)));
        verifyNoMoreInteractions(eh);
    }
    
    @Test
    public void dispatchesSingleLineMessageWithId() throws Exception {
        esp.line("data: hello");
        esp.line("id: 1");
        esp.line("");

        verify(eh).onMessage(eq("message"), eq(new MessageEvent("hello", "1", ORIGIN)));
    }

    @Test
    public void dispatchesSingleLineMessageWithCustomEvent() throws Exception {
        esp.line("data: hello");
        esp.line("event: beeroclock");
        esp.line("");

        verify(eh).onMessage(eq("beeroclock"), eq(new MessageEvent("hello", null, ORIGIN)));
    }

    @Test
    public void ignoresLinesStartingWithColon() throws Exception {
        esp.line(": ignore this");
        esp.line("data: hello");
        esp.line(": this too");
        esp.line("");

        verify(eh).onMessage(eq("message"), eq(new MessageEvent("hello", null, ORIGIN)));
    }

    @Test
    public void dispatchesSingleLineMessageWithoutColon() throws Exception {
        esp.line("data");
        esp.line("");

        verify(eh).onMessage(eq("message"), eq(new MessageEvent("", null, ORIGIN)));
    }

    @Test
    public void setsRetryTimeToSevenSeconds() throws Exception {
        esp.line("retry: 7000");
        esp.line("");

        verify(ch).setReconnectionTimeMillis(7000);
    }

    @Test
    public void doesntSetRetryTimeUnlessEntireValueIsNumber() throws Exception {
        esp.line("retry: 7000L");
        esp.line("");

        verifyNoMoreInteractions(eh);
    }

    @Test
    public void usesTheEventIdOfPreviousEventIfNoneSet() throws Exception {
        esp.line("data: hello");
        esp.line("id: reused");
        esp.line("");
        esp.line("data: world");
        esp.line("");

        verify(eh).onMessage(eq("message"), eq(new MessageEvent("hello", "reused", ORIGIN)));
        verify(eh).onMessage(eq("message"), eq(new MessageEvent("world", "reused", ORIGIN)));
    }

    @Test
    public void eventStreamDataCanBeEasilyParsedInTests() throws Exception {
        StubHandler stubHandler = new StubHandler();
        EventStreamParser esp = new EventStreamParser(null, stubHandler, stubHandler);
        esp.lines("" +
                "data: hello\n" +
                "data: world\n" +
                "\n" +
                "data: bonjour\n" +
                "data: monde\n" +
                "\n");
        assertEquals(asList(new MessageEvent("hello\nworld"), new MessageEvent("bonjour\nmonde")), stubHandler.getMessageEvents());
    }
}
