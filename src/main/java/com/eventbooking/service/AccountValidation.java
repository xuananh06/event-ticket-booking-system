package com.eventbooking.service;

import com.eventbooking.exception.ValidationException;
import com.eventbooking.security.PasswordHasher;
import java.util.*;
import java.util.regex.Pattern;

/** Shared account input contract for public registration and administrative creation/editing. */
public final class AccountValidation {
    private static final Pattern EMAIL=Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    private AccountValidation() { }
    public record Fields(String name,String email) { }
    public static String normalize(String email) {return email==null?"":email.trim().toLowerCase(Locale.ROOT);}
    private static Map<String,String> emailErrors(String email) {
        Map<String,String> errors=new LinkedHashMap<>();
        if(email.length()>254 || email.indexOf('@')>64 || !EMAIL.matcher(email).matches()) {errors.put("email","Enter a valid ASCII email address of at most 254 characters.");}
        return errors;
    }
    public static String credentials(String email,String password,PasswordHasher passwords) {
        String normalized=normalize(email);Map<String,String> errors=emailErrors(normalized);
        passwordErrors(errors,password,passwords);if(!errors.isEmpty()){throw new ValidationException(errors);}return normalized;
    }
    private static void passwordErrors(Map<String,String> errors,String password,PasswordHasher passwords) {
        if(!passwords.isValidLength(password)){errors.put("password","Use 8-72 UTF-8 bytes. Some characters use more than one byte.");}
    }
    public static Fields profile(String name,String email) {return validate(name,email,null,null,null);}
    public static Fields registration(String name,String email,String password,String confirmation,PasswordHasher passwords) {
        return validate(name,email,password,confirmation,passwords);
    }
    private static Fields validate(String name,String email,String password,String confirmation,PasswordHasher passwords) {
        String normalized=normalize(email),display=name==null?"":name.strip();
        Map<String,String> errors=emailErrors(normalized);
        if(display.isEmpty() || display.length()>150 || display.codePoints().anyMatch(Character::isISOControl)){errors.put("fullName","Enter a name of 1-150 characters without control characters.");}
        if(passwords!=null) {
            passwordErrors(errors,password,passwords);
            if(password==null || !password.equals(confirmation)){errors.put("confirmation","The password confirmation does not match.");}
        }
        if(!errors.isEmpty()){throw new ValidationException(errors);}
        return new Fields(display,normalized);
    }
}
