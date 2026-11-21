package com.nexvance.loginapp.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TXT lookups with a plain DNS query to the machine's own resolver (the first "nameserver" in /etc/resolv.conf): UDP first, and
 * TCP when the answer does not fit. No library and no JNDI. Short timeouts; anything unexpected is an empty answer.
 */
public final class UdpDnsTxtLookup implements DnsTxtLookup {

    private static final Logger LOG = LoggerFactory.getLogger(UdpDnsTxtLookup.class);
    private static final int TIMEOUT_MS = 2500;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public List<String> txt(String name) {
        try {
            InetAddress resolver = resolver();
            int id = RANDOM.nextInt(0x10000);
            byte[] query = query(id, name);
            byte[] answer = udp(resolver, query);
            if (answer == null || (answer.length > 2 && (answer[2] & 0x02) != 0)) {   // truncated: ask again over TCP
                answer = tcp(resolver, query);
            }
            return answer == null ? List.of() : parse(answer, id);
        } catch (IOException | RuntimeException e) {
            LOG.info("DNS lookup of {} failed: {}", name, e.toString());
            return List.of();
        }
    }

    private static InetAddress resolver() throws IOException {
        try {
            for (String line : Files.readAllLines(Path.of("/etc/resolv.conf"))) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equals("nameserver")) {
                    return InetAddress.getByName(parts[1]);
                }
            }
        } catch (IOException e) {
            LOG.debug("no /etc/resolv.conf: {}", e.toString());
        }
        return InetAddress.getByName("1.1.1.1");
    }

    public static byte[] query(int id, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeShort(id);
        d.writeShort(0x0100);   // recursion desired
        d.writeShort(1);
        d.writeShort(0);
        d.writeShort(0);
        d.writeShort(0);
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IOException("bad label in " + name);
            }
            d.writeByte(bytes.length);
            d.write(bytes);
        }
        d.writeByte(0);
        d.writeShort(16);   // TXT
        d.writeShort(1);    // IN
        return out.toByteArray();
    }

    private static byte[] udp(InetAddress resolver, byte[] query) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);
            socket.send(new DatagramPacket(query, query.length, resolver, 53));
            byte[] buffer = new byte[4096];
            DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
            socket.receive(reply);
            return java.util.Arrays.copyOf(buffer, reply.getLength());
        } catch (java.net.SocketTimeoutException e) {
            return null;
        }
    }

    private static byte[] tcp(InetAddress resolver, byte[] query) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(resolver, 53), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(query.length);
            out.write(query);
            out.flush();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            int length = in.readUnsignedShort();
            byte[] answer = new byte[length];
            in.readFully(answer);
            return answer;
        } catch (java.net.SocketTimeoutException e) {
            return null;
        }
    }

    public static List<String> parse(byte[] m, int id) throws IOException {
        List<String> records = new ArrayList<>();
        if (m.length < 12 || (((m[0] & 0xff) << 8) | (m[1] & 0xff)) != id || (m[3] & 0x0f) != 0) {
            return records;   // not our answer, or the name does not exist
        }
        int questions = ((m[4] & 0xff) << 8) | (m[5] & 0xff);
        int answers = ((m[6] & 0xff) << 8) | (m[7] & 0xff);
        int p = 12;
        for (int i = 0; i < questions; i++) {
            p = skipName(m, p) + 4;
        }
        for (int i = 0; i < answers && p < m.length; i++) {
            p = skipName(m, p);
            int type = ((m[p] & 0xff) << 8) | (m[p + 1] & 0xff);
            int length = ((m[p + 8] & 0xff) << 8) | (m[p + 9] & 0xff);
            p += 10;
            if (type == 16) {
                StringBuilder text = new StringBuilder();
                int end = p + length;
                int q = p;
                while (q < end) {
                    int n = m[q] & 0xff;
                    text.append(new String(m, q + 1, n, StandardCharsets.UTF_8));
                    q += 1 + n;
                }
                records.add(text.toString().trim());
            }
            p += length;
        }
        return records;
    }

    private static int skipName(byte[] m, int p) throws IOException {
        while (true) {
            if (p >= m.length) {
                throw new IOException("short DNS answer");
            }
            int n = m[p] & 0xff;
            if (n == 0) {
                return p + 1;
            }
            if ((n & 0xc0) == 0xc0) {   // a pointer ends the name
                return p + 2;
            }
            p += 1 + n;
        }
    }
}
