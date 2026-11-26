package io.github.c1589190.agentmosire.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalHarnessTest {
    @Test
    void approvedWritesPersistInTheSessionAndDeniedWritesLeaveThePreviousValue() throws IOException {
        String transcript = run("读\n记 第一条\ny\n读\n记 第二条\n\n读\n/exit\n");
        assertTrue(transcript.contains("便笺为空。"), transcript);
        assertTrue(transcript.contains("回执 OK"), transcript);
        assertTrue(transcript.contains("回执 DENIED"), transcript);
        assertTrue(transcript.contains("便笺：第一条"), transcript);
        assertFalse(transcript.contains("便笺：第二条"), transcript);
        assertTrue(transcript.lastIndexOf("便笺：第一条") > transcript.indexOf("回执 DENIED"), transcript);
    }

    @Test
    void endingInputAtAnApprovalDoesNotExecuteThePendingWrite() throws IOException {
        String transcript = run("记 仍待确认\n");
        assertTrue(transcript.contains("批准 note__write"), transcript);
        assertFalse(transcript.contains("回执 OK"), transcript);
    }

    private static String run(String lines) throws IOException {
        var transcript = new StringWriter();
        TerminalHarness.run(new DemoModel(), new BufferedReader(new StringReader(lines)), new PrintWriter(transcript));
        return transcript.toString();
    }
}
