package org.freeswitch.esl.client;

import com.google.common.base.Throwables;
import org.freeswitch.esl.client.inbound.Client;
import org.freeswitch.esl.client.internal.IModEslApi.EventFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class ClientExample {
    private static final Logger L = LoggerFactory.getLogger(ClientExample.class);

    public static void main(String[] args) {
        try {
            args = new String[] {"ClueCon"};
            if (args.length < 1) {
                System.out.println("Usage: java ClientExample PASSWORD");
                return;
            }

            String password = "ClueCon";

            Client client = new Client();

            client.addEventListener((ctx, event) -> L.info("Received event: {}", event.getEventName()));

            client.connect(new InetSocketAddress("192.168.56.111", 8021), password, 10);
            client.setEventSubscriptions(EventFormat.PLAIN, "all");

            TimeUnit.MINUTES.sleep(10);
        } catch (Throwable t) {
            Throwables.propagate(t);
        }
    }
}
