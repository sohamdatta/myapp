package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.UdpDnsTxtLookup;

/** The DNS query and answer handling, on made-up packets. No network. */
class UdpDnsTxtLookupTest {

    private static byte[] answer(int id, int rcode, String... txtRecords) throws IOException {
        byte[] question = UdpDnsTxtLookup.query(id, "_nexvance-verify.acme.com");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        d.writeShort(id);
        d.writeShort(0x8180 | rcode);
        d.writeShort(1);
        d.writeShort(txtRecords.length);
        d.writeShort(0);
        d.writeShort(0);
        d.write(Arrays.copyOfRange(question, 12, question.length));   // the question, as echoed
        for (String text : txtRecords) {
            byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            d.writeShort(0xc00c);   // the name, as a pointer to the question
            d.writeShort(16);
            d.writeShort(1);
            d.writeInt(300);
            d.writeShort(bytes.length + 1);
            d.writeByte(bytes.length);
            d.write(bytes);
        }
        return out.toByteArray();
    }

    @Test
    void theQueryAsksForTxtOfTheName() throws Exception {
        byte[] q = UdpDnsTxtLookup.query(0x1234, "_nexvance-verify.acme.com");
        assertEquals(0x12, q[0] & 0xff);
        assertEquals(0x34, q[1] & 0xff);
        assertEquals(0x01, q[2] & 0xff, "recursion desired");
        assertEquals(1, q[5], "one question");
        assertEquals("_nexvance-verify".length(), q[12]);
        assertEquals(16, q[q.length - 3], "type TXT");
        assertEquals(1, q[q.length - 1], "class IN");
        assertThrows(IOException.class, () -> UdpDnsTxtLookup.query(1, "a..b"));
        assertThrows(IOException.class, () -> UdpDnsTxtLookup.query(1, "a".repeat(64) + ".com"));
    }

    @Test
    void theTextRecordsOfAnAnswerAreRead() throws Exception {
        assertEquals(List.of("nexvance-verify=abc", "v=spf1 -all"), UdpDnsTxtLookup.parse(answer(7, 0, "nexvance-verify=abc", "v=spf1 -all"), 7));
        assertEquals(List.of(), UdpDnsTxtLookup.parse(answer(7, 0), 7), "no records");
    }

    @Test
    void anAnswerToAnotherQuestionOrToAMissingNameIsEmpty() throws Exception {
        assertEquals(List.of(), UdpDnsTxtLookup.parse(answer(7, 0, "nexvance-verify=abc"), 8), "another id");
        assertEquals(List.of(), UdpDnsTxtLookup.parse(answer(7, 3, "nexvance-verify=abc"), 7), "NXDOMAIN");
        assertEquals(List.of(), UdpDnsTxtLookup.parse(new byte[3], 7), "too short");
    }
}
