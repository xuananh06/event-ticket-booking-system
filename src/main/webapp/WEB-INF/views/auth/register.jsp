<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
<head><%@ include file="../common/head.jspf" %></head>
<body>
<%@ include file="../common/navigation.jspf" %>
<main id="main-content" class="container page-content">
    <section class="auth-card mx-auto">
        <p class="eyebrow">WELCOME TO THE COMMUNITY</p>
        <h1 class="h2 fw-bold">Create your account</h1>
        <p class="text-secondary mb-4">A personal space to connect with your campus.</p>
        <%@ include file="../common/messages.jspf" %>
        <form method="post" action="<c:out value='${registerUrl}'/>">
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <div class="mb-3">
                <label class="form-label" for="fullName">Full name</label>
                <input class="form-control" id="fullName" name="fullName" autocomplete="name" maxlength="150" required value="<c:out value='${fullName}'/>" aria-describedby="fullName-error">
                <div id="fullName-error" class="field-error"><c:out value="${errors.fullName}"/></div>
            </div>
            <div class="mb-3">
                <label class="form-label" for="email">Email address</label>
                <input class="form-control" id="email" name="email" type="email" autocomplete="email" maxlength="254" required value="<c:out value='${email}'/>" aria-describedby="email-error">
                <div id="email-error" class="field-error"><c:out value="${errors.email}"/></div>
            </div>
            <div class="mb-3">
                <label class="form-label" for="password">Password</label>
                <input class="form-control" id="password" name="password" type="password" autocomplete="new-password" maxlength="72" required aria-describedby="password-help password-error">
                <div id="password-help" class="form-text">Use 8-72 UTF-8 bytes. Some characters use more than one byte.</div>
                <div id="password-error" class="field-error"><c:out value="${errors.password}"/></div>
            </div>
            <div class="mb-4">
                <label class="form-label" for="confirmation">Confirm password</label>
                <input class="form-control" id="confirmation" name="confirmation" type="password" autocomplete="new-password" maxlength="72" required aria-describedby="confirmation-error">
                <div id="confirmation-error" class="field-error"><c:out value="${errors.confirmation}"/></div>
            </div>
            <button type="submit" class="btn btn-primary w-100">Create account</button>
        </form>
        <p class="text-center text-secondary mt-4 mb-0">Already a member? <a href="<c:out value='${loginUrl}'/>">Sign in</a></p>
    </section>
</main>
<%@ include file="../common/footer.jspf" %>
</body>
</html>
