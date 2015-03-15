package org.cpsc538B;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Disposable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;

/**
 * Created by newmanne on 14/03/15.
 */
public class GoSender implements Disposable {

    private Process goProcess;
    private final Queue<Object> goEvents = new ArrayBlockingQueue<Object>(20);
    private InetAddress goAddress;
    private DatagramSocket serverSocket;
    private int goPort;

    void init(final String masterAddress) {
        // spawn server
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DatagramSocket serverSocket;
                try {
                    serverSocket = new DatagramSocket(0);
                } catch (SocketException e) {
                    e.printStackTrace();
                    throw new RuntimeException("Couldn't make server", e);
                }
                Gdx.app.log(TronP2PGame.SERVER_TAG, "UDP server started on port " + serverSocket.getLocalPort());
                GoSender.this.serverSocket = serverSocket;
                // spawn go
                // stuff runs from core/assets
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {

                            Runtime r = Runtime.getRuntime();
                            goProcess = new ProcessBuilder("go", "run", "../../go/server.go", Integer.toString(serverSocket.getLocalPort()), masterAddress).start();

                            BufferedReader stdInput = new BufferedReader(new InputStreamReader(goProcess.getInputStream()));
                            BufferedReader stdError = new BufferedReader(new InputStreamReader(goProcess.getErrorStream()));
                            // read the output from the command
                            System.out.println("Here is the standard output of the command:\n");
                            String s;
                            while ((s = stdInput.readLine()) != null) {
                                System.out.println("GO: " + s);
                            }
                            // read any errors from the attempted command
                            System.out.println("Here is the standard error of the command (if any):\n");
                            while ((s = stdError.readLine()) != null) {
                                System.out.println("GO: " + s);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }).start();

                // server stuff
                byte[] receiveData = new byte[2048];

                while (true) {
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    try {
                        serverSocket.receive(receivePacket);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    String sentence = new String(receivePacket.getData()).trim();
                    System.out.println("RECEIVED: " + sentence);
                    try {
                        goPort = receivePacket.getPort();
                        goAddress = receivePacket.getAddress();
                        final JsonNode jsonNode = JSONUtils.getMapper().readTree(sentence);
                        final String name = jsonNode.get("eventName").asText();
                        Gdx.app.log(TronP2PGame.SERVER_TAG, "Event recieved is of type " + name);
                        final Object event = JSONUtils.getMapper().treeToValue(jsonNode.get(name), nameToEvent.get(name));
                        goEvents.add(event);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();

    }

    public void sendToGo(Object event) {
        final String jsonString = JSONUtils.toString(event);
        Gdx.app.log(TronP2PGame.SERVER_TAG, "Sending message " + System.lineSeparator() + jsonString);
        byte[] sendData = jsonString.getBytes();
        DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, goAddress, goPort);
        try {
            serverSocket.send(sendPacket);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Collection<Object> getGoEvents() {
        List<Object> events = new ArrayList<>();
        while (!goEvents.isEmpty()) {
            events.add(goEvents.poll());
        }
        return events;
    }

    @Override
    public void dispose() {
        goProcess.destroyForcibly();
    }

    final ImmutableBiMap<String, Class<?>> nameToEvent = ImmutableBiMap.of("roundStart", RoundStartEvent.class, "myMove", MoveEvent.class, "moves", MovesEvent.class);

    @Data
    public static class RoundStartEvent {
        String eventName = "roundStart";
        int round;
        int pid;
    }

    @Data
    public static class MoveEvent {
        String eventName = "myMove";
        public MoveEvent(){}

        public MoveEvent(PositionAndDirection positionAndDirection, int pid) {
            this.x = positionAndDirection.getX();
            this.y = positionAndDirection.getY();
            this.direction = positionAndDirection.getDirection();
            this.pid = pid;
        }

        int x;
        int y;
        GameScreen.Direction direction;

        @JsonIgnore
        public PositionAndDirection getPositionAndDirection() {
            return new PositionAndDirection(x, y, direction);
        }
        int pid;
    }

    @Data
    @JsonDeserialize(using = MovesEventDeserializer.class)
    public static class MovesEvent {
        String eventName = "moves";
        List<MoveEvent> moves;
    }

    public static class MovesEventDeserializer extends JsonDeserializer<MovesEvent> {

        @Override
        public MovesEvent deserialize(JsonParser jsonParser, DeserializationContext ctxt) throws IOException, JsonProcessingException {
            if (jsonParser.getCurrentToken() == JsonToken.START_ARRAY) {
                List<MoveEvent> permissions = new ArrayList<>();
                while(jsonParser.nextToken() != JsonToken.END_ARRAY) {
                    permissions.add(jsonParser.readValueAs(MoveEvent.class));
                }
                MovesEvent movesEvent = new MovesEvent();
                movesEvent.setMoves(permissions);
                return movesEvent;
            }
            throw new IllegalStateException();
        }
    }

}
