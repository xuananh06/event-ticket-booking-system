package com.eventbooking.tools;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeedPasswordTest {
    @Test
    void allDemoAccountsContainDistinctUsableBcryptHashes() throws Exception {
        String seed = Files.readString(Path.of("database/seed.sql"));
        Matcher matcher = Pattern.compile("'([\\$]2a[\\$]12[\\$][./A-Za-z0-9]{53})'").matcher(seed);
        Set<String> hashes = new HashSet<>();
        int count = 0;
        while (matcher.find()) {
            String hash = matcher.group(1);
            assertTrue(BCrypt.verifyer().verify("DemoOnly!ChangeMe".toCharArray(), hash).verified);
            assertFalse(BCrypt.verifyer().verify("incorrect-password".toCharArray(), hash).verified);
            assertTrue(hashes.add(hash), "Each account needs an independent salt.");
            count++;
        }
        assertEquals(3, count, "Verify the actual seed hashes, not a copied test fixture.");
    }
}
