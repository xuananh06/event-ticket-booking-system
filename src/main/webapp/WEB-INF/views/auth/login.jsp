<%@ page contentType="text/html;charset=UTF-8" pageEncoding="UTF-8" isELIgnored="false" session="false" %>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<!doctype html>
<html lang="en">
<head><%@ include file="../common/head.jspf" %></head>
<body>
<%@ include file="../common/navigation.jspf" %>
<main id="main-content" class="container page-content">
    <section class="auth-card mx-auto">
        <p class="eyebrow">GOOD TO SEE YOU AGAIN</p>
        <h1 class="h2 fw-bold">Welcome back</h1>
        <p class="text-secondary mb-4">Sign in to your Campus Events account.</p>
        <%@ include file="../common/messages.jspf" %>
        <form method="post" action="<c:out value='${loginUrl}'/>">
            <input type="hidden" name="_csrf" value="<c:out value='${csrfToken}'/>">
            <div class="mb-3">
                <label class="form-label" for="email">Email address</label>
                <input class="form-control" id="email" name="email" type="email" autocomplete="username" maxlength="254" required value="<c:out value='${email}'/>" aria-describedby="email-error">
                <div id="email-error" class="field-error"><c:out value="${errors.email}"/></div>
            </div>
            <div class="mb-4">
                <label class="form-label" for="password">Password</label>
                <input class="form-control" id="password" name="password" type="password" autocomplete="current-password" maxlength="72" required aria-describedby="password-error">
                <div id="password-error" class="field-error"><c:out value="${errors.password}"/></div>
            </div>
            <button type="submit" class="btn btn-primary w-100">Sign in</button>
        </form>
        <p class="text-center text-secondary mt-4 mb-0">New here? <a href="<c:out value='${registerUrl}'/>">Create an account</a></p>
    </section>
</main>
<%@ include file="../common/footer.jspf" %>
</body>
</html>
