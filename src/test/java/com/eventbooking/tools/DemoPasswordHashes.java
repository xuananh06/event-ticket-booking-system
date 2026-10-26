package com.eventbooking.tools;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.io.Console;
import java.util.Arrays;

/**
 * Offline seed utility, deliberately excluded from the application WAR.
 */
public final class DemoPasswordHashes {
    private DemoPasswordHashes() {
    }

    public static void main(String[] arguments) {
        char[] password;
        if (arguments.length == 1 && arguments[0].equals("--demo")) {
            password = "DemoOnly!ChangeMe".toCharArray();
        } else if (arguments.length == 0 && System.console() != null) {
            Console console = System.console();
            password = console.readPassword("Demo password (8-72 UTF-8 bytes): ");
        } else {
            throw new IllegalArgumentException("Use an interactive console or --demo for public demo hashes.");
        }
        if (password == null) {
            throw new IllegalArgumentException("No password supplied.");
        }
        try {
            int byteLength = new String(password).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (byteLength < 8 || byteLength > 72) {
                throw new IllegalArgumentException("Password must be 8-72 UTF-8 bytes.");
            }
            for (int i = 0; i < 3; i++) {
                System.out.println(BCrypt.withDefaults().hashToString(12, password));
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
