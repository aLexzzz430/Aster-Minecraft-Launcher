package cn.aster.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Reads the same Java server-list status that the bundled game client sees. */
final class MinecraftStatus {
    record Snapshot(boolean reachable, int online, int max, String version) {
        static Snapshot unavailable() { return new Snapshot(false, 0, 0, ""); }
    }

    static Snapshot query(String address) throws Exception {
        String[] parts = address.split(":", 2);
        String host = parts[0];
        int port = parts.length == 2 ? Integer.parseInt(parts[1]) : 25565;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            socket.setSoTimeout(2500);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0);
            writeVarInt(handshake, 767);
            writeString(handshake, host);
            handshake.write(port >>> 8);
            handshake.write(port);
            writeVarInt(handshake, 1);
            writePacket(out, handshake.toByteArray());
            writePacket(out, new byte[]{0});

            DataInputStream in = new DataInputStream(socket.getInputStream());
            int packetLength = readVarInt(in);
            if (packetLength <= 0 || packetLength > 262144 || readVarInt(in) != 0)
                throw new java.io.IOException("无效的服务器状态响应");
            int jsonLength = readVarInt(in);
            if (jsonLength < 1 || jsonLength > packetLength || jsonLength > 262144)
                throw new java.io.IOException("无效的服务器状态内容");
            byte[] bytes = in.readNBytes(jsonLength);
            if (bytes.length != jsonLength) throw new java.io.EOFException("服务器状态响应不完整");
            JsonObject status = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject players = status.getAsJsonObject("players");
            JsonObject version = status.getAsJsonObject("version");
            return new Snapshot(true, players.get("online").getAsInt(), players.get("max").getAsInt(),
                    version.get("name").getAsString());
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writePacket(DataOutputStream out, byte[] bytes) throws Exception {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        writeVarInt(packet, bytes.length);
        packet.writeBytes(bytes);
        out.write(packet.toByteArray());
        out.flush();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7f) != 0) {
            out.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(DataInputStream in) throws Exception {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int part = in.readUnsignedByte();
            value |= (part & 0x7f) << shift;
            if ((part & 0x80) == 0) return value;
        }
        throw new java.io.IOException("无效的服务器状态长度");
    }
}
