package com.eventbooking.integration;

import java.net.http.HttpResponse;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Parses actual rendered hidden inputs; never includes token values in assertion messages. */
final class FinancialForms {
    private FinancialForms() { }
    record Form(String action, Map<String, String> fields, String body) {
        Map<String, String> submit(String outcome) {
            Map<String, String> values = new LinkedHashMap<>(fields);
            if (outcome != null) {
                assertTrue(body.contains("name=\"outcome\" value=\"" + outcome + "\""));
                values.put("outcome", outcome);
            }
            return values;
        }
    }
    static Form form(HttpResponse<String> response, String action) {
        Matcher forms = Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>").matcher(response.body());
        while (forms.find()) {
            String attributes = forms.group(1), body = forms.group(2);
            if (!attributes.contains("method=\"post\"") || !body.contains("name=\"action\" value=\"" + action + "\"")) { continue; }
            Matcher url = Pattern.compile("action=\"([^\"]+)\"").matcher(attributes);
            assertTrue(url.find());
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher hidden = Pattern.compile("<input\\b[^>]*type=\"hidden\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"[^>]*>").matcher(body);
            while (hidden.find()) { assertNull(fields.put(hidden.group(1), decode(hidden.group(2))), "Duplicate rendered hidden field."); }
            assertNotNull(fields.get("_csrf"));
            return new Form(decode(url.group(1)), fields, body);
        }
        throw new AssertionError("Expected financial form missing; body suppressed.");
    }
    static String decode(String value) { return value.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">"); }
}
