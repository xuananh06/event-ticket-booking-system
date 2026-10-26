package com.eventbooking.integration;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent cookie jar; assertions never include live token/cookie values. */
final class HttpBrowser {
    private final URI base=URI.create(System.getProperty("auth.baseUrl"));
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private String loginToken;
    String staleToken(){return loginToken;}
    HttpResponse<String> get(String path) throws Exception {return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).GET().build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));}
    HttpResponse<String> post(String path,Map<String,String> fields) throws Exception {
        String body=fields.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));
        return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20)).header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    void login(String email,String password,String destination) throws Exception {
        String token=token(get("login").body());
        loginToken=token;
        redirect(post("login",fields("_csrf",token,"email",email,"password",password)),destination);
    }
    String path(String relative) {return base.getPath()+(relative.startsWith("/")?relative.substring(1):relative);}
    void redirect(HttpResponse<String> response,String destination) {
        status(303,response);assertEquals(path(destination),response.headers().firstValue("Location").orElseThrow());
    }
    static void status(int expected,HttpResponse<String> response) {
        assertEquals(expected,response.statusCode(),"Unexpected HTTP status; response body suppressed.");
        assertFalse(response.body().contains("jdbc:sqlserver"));
        assertFalse(response.body().contains("java.sql."));
        assertFalse(response.body().contains("$2a$12$"));
        assertFalse(response.body().contains("${"));
    }
    static String token(String body) {
        Matcher m=Pattern.compile("name=\"_csrf\" value=\"([A-Za-z0-9_-]{43})\"").matcher(body);
        assertTrue(m.find(),"Missing form token; body suppressed.");return m.group(1);
    }
    record Form(String action,String token) {
        Map<String,String> fields(String... fields) {Map<String,String> values=HttpBrowser.fields(fields);values.put("_csrf",token);return values;}
    }
    static Form form(HttpResponse<String> response,String action,Long id) {
        Matcher forms=Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>").matcher(response.body());
        while(forms.find()) {
            String attributes=forms.group(1),body=forms.group(2);
            if(!attributes.contains("method=\"post\"") || !body.contains("name=\"action\" value=\""+action+"\"")){continue;}
            if(id!=null && !body.contains("name=\"id\" value=\""+id+"\"")){continue;}
            Matcher url=Pattern.compile("action=\"([^\"]+)\"").matcher(attributes);assertTrue(url.find());
            return new Form(url.group(1).replace("&amp;","&"),token(body));
        }
        throw new AssertionError("Expected rendered mutation form was not found; body suppressed.");
    }
    static Map<String,String> fields(String... values) {Map<String,String> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2){result.put(values[i],values[i+1]);}return result;}
    private static String encode(String text) {return URLEncoder.encode(text,StandardCharsets.UTF_8);}
}
